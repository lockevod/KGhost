package com.enderthor.kghost.extension

import com.enderthor.kghost.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Sends the KGhost diagnostic log to the developer's Telegram chat — only when the rider has the
 * (off-by-default) diagnostic log enabled.
 *
 * Privacy:
 *  - **No bot token in the APK**: logs go through a write-only relay (Cloudflare Worker) that holds the
 *    token and the chat id as secrets and only ever calls `sendDocument` to that fixed chat. The APK
 *    carries just [RELAY_URL] (from `local.properties` at compile time; not a secret). A token in
 *    BuildConfig would sit verbatim in the public `.dex` — R8 does not hide string constants. An
 *    absent URL makes this a no-op.
 *  - **No location data leaves the device**: [redactForUpload] strips every absolute `lat=`/`lng=`
 *    coordinate from the uploaded copy before it is sent. The on-device file keeps full detail; the
 *    route-relative diagnostics (routeDist, remaining, gap…) are not coordinates and are preserved.
 *
 * Overhead: stateless. If the caller never invokes [sendLogFile] there is no background work; a send
 * is a single multipart POST run on the caller's IO coroutine.
 */
object LogReporter {

    // From BuildConfig (local.properties at compile time): calib.relay_url=https://<worker>
    private val RELAY_URL: String get() = BuildConfig.LOG_RELAY_URL
    private val configured: Boolean get() = RELAY_URL.startsWith("https://")

    /** Relay allow-list key: the Worker answers 403 to any app not in its ALLOWED_APPS. */
    private const val APP_ID = "kghost"
    private const val TIMEOUT_MS = 60_000L

    /** Short timeout for the [isReachable] probe. A tiny GET either answers in a second or two on a
     *  live link, or the link is down — so a few seconds is enough; no need to wait the full upload
     *  [TIMEOUT_MS] to learn the Companion is out of coverage. */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** A coordinate token anywhere in the log (`lat=41.4863`, `lng=-2.31`) → redacted to `lat=•`. The
     *  decimal separator is `.` OR `,`: on a comma locale (`lat=41,4863`) a dot-only pattern redacts the
     *  integer degrees and uploads the decimals, which pin the rider to ~1 m once the region is known. */
    private val COORD = Regex("(lat|lng)=-?\\d+([.,]\\d+)?")
    // Rider-chosen NAMES (route / Karoo ride profile). Not coordinates, but a route named after a place
    // ("Casa-Trabajo") is still identifying, so they're stripped from the uploaded copy too. Anchored on
    // the stable surrounding tokens of each log line that prints a name (see KGhostExtension nav/profile
    // /avg/route-mode logs): `name=<X> routeLen=`, the active-profile line (name to EOL), and the
    // single-quoted `on '<X>' karooLen=` / `for '<X>' yet` forms.
    private val ROUTE_NAME = Regex("name=.*?(?= routeLen=)")
    private val PROFILE_NAME = Regex("(KVP active profile: id=\\S+ name=).*")
    private val QUOTED_NAME = Regex("\\b(on|for) '[^']*'")

    sealed class SendResult(val message: String) {
        class Success(message: String) : SendResult(message)
        class Failure(message: String) : SendResult(message)
        val ok: Boolean get() = this is Success
    }

    /** Removes GPS coordinates AND rider-chosen route/profile names so no location/identity is uploaded. */
    fun redactForUpload(content: String): String = content
        .replace(COORD, "$1=•")
        .replace(ROUTE_NAME, "name=•")
        .replace(PROFILE_NAME, "$1•")
        .replace(QUOTED_NAME, "$1 '•'")

    /**
     * Sends [content] (redacted) through the relay as a Telegram document named [fileName], with
     * [caption] as the message text, to the developer chat the relay is bound to. Returns a [SendResult] whose [SendResult.message]
     * is suitable to surface in the rider-facing "Send now" UI.
     */
    suspend fun sendLogFile(
        content: String,
        fileName: String,
        caption: String,
        karooSystem: KarooSystemService,
    ): SendResult {
        if (!configured) {
            Timber.w("LogReporter: relay URL not set in local.properties — skipping log send")
            return SendResult.Failure("Log upload not configured in this build.")
        }
        val redacted = redactForUpload(content)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")

        return try {
            val boundary = "KGhostBoundary_${System.currentTimeMillis()}"
            val body = buildMultipart(boundary, "$fileName.gz", gzip(redacted), caption)
            val kb = body.size / 1024
            Timber.d("LogReporter: sending $kb KB gz (${redacted.length / 1024} KB raw) to Telegram…")

            val response: HttpResponseState.Complete? = withTimeoutOrNull(TIMEOUT_MS) {
                karooSystem.httpRequest(
                    "POST",
                    RELAY_URL,
                    mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                    body,
                )
            }
            if (response == null) {
                Timber.w("LogReporter: timeout ($kb KB)")
                return SendResult.Failure("Timeout after ${TIMEOUT_MS / 1000}s — $kb KB. Karoo Companion may be offline.")
            }
            val respBody = response.body?.toString(Charsets.UTF_8) ?: ""
            val ok = response.statusCode in 200..299 && respBody.contains("\"ok\":true")
            if (ok) {
                Timber.i("LogReporter: log delivered ✓ ($kb KB)")
                SendResult.Success("Sent ✓ ($kb KB)")
            } else {
                val tgDesc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(respBody)?.groupValues?.getOrNull(1)
                val hint = when (response.statusCode) {
                    401 -> "Relay rejected by Telegram (check the relay's BOT_TOKEN secret)."
                    403 -> "Relay refused this app (add kghost to ALLOWED_APPS)."
                    400 -> tgDesc ?: "Bad request (check the relay's CHAT_ID secret)."
                    413 -> "Chunk too big for the relay."
                    429 -> "Rate-limited by Telegram. Wait a minute."
                    in 500..599 -> "Telegram server error (try again)."
                    else -> tgDesc ?: "HTTP ${response.statusCode}"
                }
                Timber.w("LogReporter: delivery failed — HTTP ${response.statusCode}: ${respBody.take(200)}")
                SendResult.Failure("Send failed: $hint")
            }
        } catch (e: Exception) {
            Timber.e(e, "LogReporter: error during send")
            SendResult.Failure("Send error: ${e.javaClass.simpleName} — ${e.message ?: "no message"}")
        }
    }

    /**
     * Cheap reachability probe — a tiny GET to the relay with a short [PROBE_TIMEOUT_MS] timeout. Lets the
     * uploader learn in seconds that the Companion is out of coverage and SKIP the drain, whose first
     * chunk would otherwise burn the full [TIMEOUT_MS] pushing 58 KB into a dead link before it gives up,
     * retrying on the next short cycle instead. "Reachable" = we got ANY HTTP response back (even a 4xx
     * error — the relay answers a GET with 405 — means the round-trip completed); only a
     * timeout/exception counts as offline. No relay URL → not reachable (the feature is a no-op anyway).
     */
    suspend fun isReachable(karooSystem: KarooSystemService): Boolean {
        if (!configured) return false
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                // Any HTTP response back = the network round-trip completed = reachable (the relay's
                // 405 still means the link is up). Only a timeout or a transport error is "offline".
                karooSystem.httpRequest("GET", RELAY_URL, emptyMap(), null)
                true
            } catch (e: CancellationException) {
                throw e // let withTimeoutOrNull cancel cleanly — never swallow the timeout
            } catch (e: Exception) {
                false
            }
        } ?: false
    }

    /**
     * gzips [text] for upload. Diagnostic logs are highly repetitive and compress ~6-7.6:1 (measured
     * on a real 5 h ride log: 2,994,327 → 393,826 bytes), so this is the single biggest lever on the
     * energy this feature costs: the Companion BLE link is orders of magnitude more expensive per byte
     * than the CPU spent compressing. Telegram accepts arbitrary binary documents, so the only
     * visible change is the `.gz` suffix on the delivered file.
     */
    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /**
     * Builds the relay's `multipart/form-data` body (`app`, optional `caption`, `document`). The relay
     * adds the chat id itself and ignores any client-sent one.
     * The document part is raw BYTES (gzipped), so the body is assembled as byte arrays rather than
     * through a String — a gzip stream is not valid UTF-8 and would be mangled by a String round-trip.
     */
    private fun buildMultipart(
        boundary: String,
        fileName: String,
        fileBytes: ByteArray,
        caption: String,
    ): ByteArray {
        val crlf = "\r\n"
        val head = buildString {
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"app\"$crlf$crlf")
            append(APP_ID); append(crlf)
            if (caption.isNotBlank()) {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                append(caption); append(crlf)
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf")
            append("Content-Type: application/gzip$crlf$crlf")
        }.toByteArray(Charsets.UTF_8)
        val tail = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
        return head + fileBytes + tail
    }
}
