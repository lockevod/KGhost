package com.enderthor.kghost.extension

import com.enderthor.kghost.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends the KGhost diagnostic log to the developer via a Telegram bot — only when the rider has the
 * (off-by-default) diagnostic log enabled.
 *
 * Privacy:
 *  - **Credentials never in source / never in any settings screen**: BOT_TOKEN and CHAT_ID come from
 *    [BuildConfig], injected from `local.properties` (gitignored) at compile time. The log can only
 *    ever go to the developer's own chat; an absent key makes this a no-op.
 *  - **No location data leaves the device**: [redactForUpload] strips every absolute `lat=`/`lng=`
 *    coordinate from the uploaded copy before it is sent. The on-device file keeps full detail; the
 *    route-relative diagnostics (routeDist, remaining, gap…) are not coordinates and are preserved.
 *
 * Overhead: stateless. If the caller never invokes [sendLogFile] there is no background work; a send
 * is a single multipart POST run on the caller's IO coroutine.
 */
object LogReporter {

    // Values come from BuildConfig (local.properties at compile time):
    //   calib.bot_token=<bot token from @BotFather>
    //   calib.chat_id=<developer chat id>
    private val BOT_TOKEN: String get() = BuildConfig.CALIB_BOT_TOKEN
    private val CHAT_ID: String get() = BuildConfig.CALIB_CHAT_ID

    private const val API_BASE = "https://api.telegram.org/bot"
    private const val TIMEOUT_MS = 60_000L

    /** Short timeout for the [isReachable] probe. A tiny `getMe` either answers in a second or two on a
     *  live link, or the link is down — so a few seconds is enough; no need to wait the full upload
     *  [TIMEOUT_MS] to learn the Companion is out of coverage. */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** A coordinate token anywhere in the log (`lat=41.4863`, `lng=-2.31`) → redacted to `lat=•`. */
    private val COORD = Regex("(lat|lng)=-?\\d+(\\.\\d+)?")
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
     * Sends [content] (redacted) as a Telegram document named [fileName], with [caption] as the
     * message text, to the hardcoded developer chat. Returns a [SendResult] whose [SendResult.message]
     * is suitable to surface in the rider-facing "Send now" UI.
     */
    suspend fun sendLogFile(
        content: String,
        fileName: String,
        caption: String,
        karooSystem: KarooSystemService,
    ): SendResult {
        if (BOT_TOKEN.isBlank() || BOT_TOKEN.startsWith("REPLACE") ||
            CHAT_ID.isBlank() || CHAT_ID.startsWith("REPLACE")
        ) {
            Timber.w("LogReporter: credentials not set in local.properties — skipping log send")
            return SendResult.Failure("Logging credentials not configured in this build.")
        }
        val redacted = redactForUpload(content)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")

        return try {
            val boundary = "KGhostBoundary_${System.currentTimeMillis()}"
            val body = buildMultipart(boundary, fileName, redacted, caption)
            val kb = body.size / 1024
            Timber.d("LogReporter: sending $kb KB to Telegram…")

            val response: HttpResponseState.Complete? = withTimeoutOrNull(TIMEOUT_MS) {
                karooSystem.httpRequest(
                    "POST",
                    "$API_BASE$BOT_TOKEN/sendDocument",
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
                    401 -> "Bot token rejected (check CALIB_BOT_TOKEN)."
                    400 -> tgDesc ?: "Bad request (check chat id)."
                    413 -> "File too big (Telegram limit is 50 MB)."
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
     * Cheap reachability probe — a tiny `getMe` GET with a short [PROBE_TIMEOUT_MS] timeout. Lets the
     * uploader learn in seconds that the Companion is out of coverage and SKIP the drain, whose first
     * chunk would otherwise burn the full [TIMEOUT_MS] pushing 58 KB into a dead link before it gives up,
     * retrying on the next short cycle instead. "Reachable" = we got ANY HTTP response back (even a 4xx
     * auth error means the network round-trip completed); only a timeout/exception counts as offline.
     * No creds → not reachable (the feature is a no-op anyway).
     */
    suspend fun isReachable(karooSystem: KarooSystemService): Boolean {
        if (BOT_TOKEN.isBlank() || BOT_TOKEN.startsWith("REPLACE") ||
            CHAT_ID.isBlank() || CHAT_ID.startsWith("REPLACE")
        ) return false
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                // Any HTTP response back = the network round-trip completed = reachable (a 4xx auth
                // error still means the link is up). Only a timeout or a transport error is "offline".
                karooSystem.httpRequest("GET", "$API_BASE$BOT_TOKEN/getMe", emptyMap(), null)
                true
            } catch (e: CancellationException) {
                throw e // let withTimeoutOrNull cancel cleanly — never swallow the timeout
            } catch (e: Exception) {
                false
            }
        } ?: false
    }

    /** Builds a `multipart/form-data` body for the Telegram Bot API `sendDocument` endpoint. */
    private fun buildMultipart(
        boundary: String,
        fileName: String,
        fileContent: String,
        caption: String,
    ): ByteArray {
        val crlf = "\r\n"
        return buildString {
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf")
            append(CHAT_ID); append(crlf)
            if (caption.isNotBlank()) {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                append(caption); append(crlf)
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf")
            append("Content-Type: text/plain; charset=UTF-8$crlf$crlf")
            append(fileContent); append(crlf)
            append("--$boundary--$crlf")
        }.toByteArray(Charsets.UTF_8)
    }
}
