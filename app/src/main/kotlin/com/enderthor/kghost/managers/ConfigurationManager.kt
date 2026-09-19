package com.enderthor.kghost.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.migrateToLatest
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import com.enderthor.kghost.geo.atomicWriteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File
import kotlin.random.Random

/**
 * Process-wide DataStore instance for KGhost settings.
 * Defined once here so both [ConfigurationManager] and MainActivity can import it.
 * The DataStore API requires exactly one delegate per [name] per [Context] — never create
 * another `preferencesDataStore("kghost")` anywhere else in this process.
 *
 * The [ReplaceFileCorruptionHandler] is NOT optional here. Without it, a process kill in the middle
 * of an `edit {}` leaves `settings.preferences_pb` unparseable and DataStore then rethrows
 * `CorruptionException` on EVERY read, forever — a crash loop before any UI, with no way out for the
 * rider but clearing app data. KGhost writes config *during* a ride (tidy sweep at tick start,
 * profile learning on a mid-route profile switch, the permission-alert counter) and the Karoo OS
 * kills extension processes at ride end, so that window recurs on every ride. The handler turns the
 * permanent crash loop into an empty store; [CONFIG_MIRROR_FILE] turns the empty store back into the
 * rider's settings.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kghost",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Filename (under `filesDir`) of the plain-JSON mirror of the config blob.
 *
 * App-PRIVATE storage on purpose. The mirror only has to survive the one event it exists for — a
 * corruption reset, which happens in place with the app's data intact — and app-private storage is
 * always writable (no all-files permission, no shared-storage fallback dance). Putting it in the
 * tracks dir would also make it survive a "clear app data" fresh start, which is exactly when
 * restoring the old settings would be WRONG, and would expose it to being restored stale from a
 * user's backup of /sdcard/KGhost. So: survives a corruption reset and a reinstall-free crash;
 * does not survive clear-data or an uninstall — by design, both of those mean "start fresh".
 *
 * Mirror EXISTENCE is therefore also the signal that separates a restore from a genuine first run:
 * a fresh install has no mirror, a corruption reset still has one. (The install id can't serve that
 * role — it lives in the very same preferences file, so a corruption reset erases it too, leaving it
 * indistinguishable from first run.)
 */
internal const val CONFIG_MIRROR_FILE = "kghostconfig-mirror.json"

/**
 * Filename (under `filesDir`) of the install-id mirror — the 6-hex "Anon tag" that groups a device's
 * diagnostic logs. The id lives in the same preferences file the corruption erases, so without this
 * the tag changes across exactly the incident a maintainer wants to trace. Plain text, not JSON:
 * six hex characters need no schema.
 */
internal const val INSTALL_ID_MIRROR_FILE = "kghost-installid.txt"

private val INSTALL_ID_RE = Regex("[0-9a-f]{6}")

/** The install id held by [INSTALL_ID_MIRROR_FILE], or null if absent/unreadable/not a valid tag. */
internal fun installIdFromMirror(text: String?): String? = text?.trim()?.takeIf { INSTALL_ID_RE.matches(it) }

/**
 * Process-wide write monitors keyed by canonical mirror path — the same idiom [AggregateStore] uses
 * for the identical hazard: [atomicWriteText] names its temp `<name>.tmp`, so two concurrent writers
 * of one target share it, the second truncating the temp the first is about to rename, and the failed
 * rename then degrades to a plain non-atomic `writeText` onto the live file. Serialising per target
 * closes that window; a kill inside it would otherwise leave a permanently zero-byte mirror, which
 * still reads as "present" and so fires the restore signal while restoring nothing.
 */
private val mirrorLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

/** Writes [text] to [file] atomically AND serialised against any other writer of the same file. */
internal fun writeMirrorFile(file: File, text: String) {
    val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    synchronized(mirrorLocks.computeIfAbsent(key) { Any() }) { atomicWriteText(file, text) }
}

/**
 * Decodes the config mirror written by [ConfigurationManager], or null when there is nothing usable
 * to restore (no mirror at all = genuine first run; an unparseable one = best-effort backup that
 * failed, degrade to defaults rather than throw).
 *
 * Two ride-churned epochs are deliberately NOT restored — they are stamps that say "work already
 * done", and a stale stamp silently SKIPS work:
 *  - `lastScanEpoch`: "Import new only" skips files not modified since. The tracks dir survives the
 *    corruption but the mirror's epoch may have been stamped for an import whose ride/write never
 *    completed — restoring it would permanently skip files that were never imported. 0 = re-scan
 *    everything; the processed-ledger dedups the re-scan, so the cost is time, not duplicates.
 *  - `tidySweepEpoch`: non-zero suppresses the one-time backlog sweep forever. Re-running a sweep
 *    that already ran is a no-op (the near-duplicates are already archived); never running one that
 *    didn't is a permanently un-tidied library. 0 = sweep once and re-stamp.
 *
 * Everything else is restored, including `permAlertFiredCount`/`permAlertLastFiredEpoch`: a stale
 * value there only shifts the reminder cadence by one alert, and nothing is skipped or lost.
 */
internal fun restoredFromMirror(mirrorText: String?): KGhostConfig? =
    mirrorText
        ?.let {
            runCatching { jsonWithUnknownKeys.decodeFromString<KGhostConfig>(it) }
                .onFailure { e -> Timber.w(e, "config mirror unparseable — falling back to defaults") }
                .getOrNull()
        }
        ?.copy(lastScanEpoch = 0L, tidySweepEpoch = 0L)

/**
 * The config a write transform must be applied to, given the [raw] blob currently in DataStore.
 * No Android and no IO of its own, so the write-path guard is directly testable — see
 * [ConfigurationManager.updateConfig] for why a present-but-undecodable [raw] must THROW here.
 * [mirrorText] is only consulted when the store has no blob at all, so the mirror can never write
 * stale settings over good ones.
 */
internal suspend fun configForUpdate(raw: String?, mirrorText: suspend () -> String?): KGhostConfig =
    if (raw != null) {
        jsonWithUnknownKeys.decodeFromString<KGhostConfig>(raw)
    } else {
        restoredFromMirror(mirrorText()) ?: KGhostConfig()
    }.migrateToLatest()

/**
 * Manages persistent configuration for the KGhost extension.
 * Stores a single [KGhostConfig] JSON blob under the key [CONFIG_KEY] in [dataStore].
 */
class ConfigurationManager(private val context: Context) {

    private val configKey = stringPreferencesKey("kghostconfig")
    private val installIdKey = stringPreferencesKey("install_id")

    /**
     * Returns a stable, opaque 6-hex "Anon tag" for this install — created once and persisted, so a
     * developer who receives diagnostic logs can group a device's logs across rides WITHOUT any
     * personal or device identifier. It is purely random (no time, no hardware id); shown to the
     * rider as the "Anon tag" in the upload caption. Stable across rides, restarts and config resets.
     */
    suspend fun getOrCreateInstallId(): String {
        context.dataStore.data.first()[installIdKey]?.let { return it }
        // Absent: a genuine first run, OR the corruption reset that wiped the whole preferences file.
        // Prefer the mirrored tag so a device's logs stay groupable across the incident worth tracing.
        val id = installIdFromMirror(readMirror(installIdMirrorFile))
            ?: "%06x".format(Random.nextInt(0x1000000))
        runCatching { context.dataStore.edit { it[installIdKey] = id } }
        writeMirror(installIdMirrorFile, id)
        return id
    }

    /**
     * Emits the current [KGhostConfig] and re-emits whenever it changes in DataStore.
     * Decodes with [jsonWithUnknownKeys] (forward-compat) and applies [migrateToLatest].
     * Falls back to [KGhostConfig] defaults on any decode or I/O error so the extension
     * never hangs waiting for a value that will never arrive.
     */
    fun loadConfigFlow(): Flow<KGhostConfig> =
        context.dataStore.data
            .map { prefs ->
                val raw = prefs[configKey]
                if (raw == null) {
                    // Empty store: either a genuine first run, or DataStore just replaced a corrupt
                    // file with empty prefs. The mirror tells the two apart (see [CONFIG_MIRROR_FILE]).
                    restoredFromMirror(readMirror(mirrorFile)) ?: KGhostConfig()
                } else {
                    decodeConfig(raw)
                }
            }
            .catch { e ->
                // Upstream DataStore I/O failure — the corruption handler keeps CorruptionException
                // away from here, but any other IOException still lands here having never reached the
                // mirror-consulting map above. Consult it here too, so an unreadable store degrades to
                // the rider's settings rather than to raw defaults; defaults remain the last resort so
                // consumers never hang waiting for a value.
                Timber.e(e, "KGhostConfig DataStore read failed — falling back to the config mirror")
                emit(restoredFromMirror(readMirror(mirrorFile)) ?: KGhostConfig())
            }
            .map { it.migrateToLatest() }
            .distinctUntilChanged()

    /**
     * Atomically updates the persisted config: reads the current value, applies [transform], and writes
     * it back INSIDE a single DataStore edit() so concurrent partial updates (e.g. a background import
     * advancing lastScanEpoch while the user toggles a switch) merge instead of clobbering each other.
     *
     * DataStore's [edit] serialises writes, so the transform always runs against the latest persisted
     * value — no lost update. The current value is decoded with [decodeConfig] and migrated with
     * [migrateToLatest] (mirroring [loadConfigFlow]) before [transform] is applied.
     *
     * @return true on success, false if it threw (already logged).
     */
    suspend fun updateConfig(transform: (KGhostConfig) -> KGhostConfig): Boolean {
        return try {
            context.dataStore.edit { prefs ->
                // [configForUpdate] decodes WITHOUT the silent default-fallback that [decodeConfig]
                // uses on the read path. On the WRITE path a present-but-undecodable blob must NOT be
                // replaced with defaults (nor with the mirror): doing so would persist stale values over
                // the rider's settings on the very next toggle. Let a genuine decode failure throw → the
                // edit aborts, the bad blob is preserved, and the caller surfaces a save-failed status.
                // Note this only fires on a structurally broken blob: a stale/removed ENUM value still
                // decodes fine because [jsonWithUnknownKeys] has coerceInputValues=true.
                val current = configForUpdate(prefs[configKey]) { readMirror(mirrorFile) }
                val next = jsonForStorage.encodeToString(transform(current))
                prefs[configKey] = next
                // INSIDE the edit on purpose. DataStore serialises edit blocks, so mirroring here makes
                // the mirror's write order the same as the store's. Mirrored AFTER the edit it was not:
                // two concurrent updates could land their mirror writes in the opposite order and leave
                // the mirror holding an OLDER config than the store — the rider's most recent change
                // silently missing from the copy a corruption restore reads back.
                writeMirror(mirrorFile, next)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled scope (a settings screen leaving composition, the extension scope at ride end)
            // is NOT a failed write: the authoritative store edit may well have committed. Swallowing it
            // would report a bogus save-failure and defeat callers that rethrow cancellation.
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Failed to update KGhostConfig")
            false
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private val mirrorFile: File get() = File(context.filesDir, CONFIG_MIRROR_FILE)
    private val installIdMirrorFile: File get() = File(context.filesDir, INSTALL_ID_MIRROR_FILE)

    /** Reads [file], or null if absent/unreadable. Never throws. */
    private suspend fun readMirror(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.readText() }
            .onFailure { Timber.w(it, "mirror read failed for %s", file.name) }
            .getOrNull()
    }

    /**
     * Mirrors [text] to [file] with [writeMirrorFile] (temp + fsync + rename under a per-target
     * monitor, so neither a kill mid-write nor a concurrent writer can produce a torn mirror). Best
     * effort: a mirror failure is logged, never surfaced — DataStore already holds the authoritative
     * value.
     *
     * Explicitly dispatched to IO because the settings screens call [updateConfig] from a Compose
     * `rememberCoroutineScope()`, i.e. on Main — DataStore does its own IO internally, this write
     * would not. Config writes are rare (a settings toggle, one profile switch per ride, the one-time
     * tidy stamp, the permission-alert counter, one per import flush), so the ride path is untouched.
     */
    private suspend fun writeMirror(file: File, text: String) = withContext(Dispatchers.IO) {
        runCatching { writeMirrorFile(file, text) }
            .onFailure { Timber.w(it, "mirror write failed for %s", file.name) }
        Unit
    }

    private fun decodeConfig(raw: String): KGhostConfig {
        return try {
            jsonWithUnknownKeys.decodeFromString<KGhostConfig>(raw)
        } catch (e: Throwable) {
            val snippet = raw.take(200).replace("\n", " ")
            Timber.e(
                e,
                "Failed to decode KGhostConfig (%s: %s) — raw[0..200] = %s",
                e.javaClass.simpleName,
                e.message,
                snippet,
            )
            KGhostConfig()
        }
    }
}
