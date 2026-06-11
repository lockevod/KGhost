package com.enderthor.kghost.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.migrateToLatest
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import timber.log.Timber
import kotlin.random.Random

/**
 * Process-wide DataStore instance for KGhost settings.
 * Defined once here so both [ConfigurationManager] and MainActivity can import it.
 * The DataStore API requires exactly one delegate per [name] per [Context] — never create
 * another `preferencesDataStore("kghost")` anywhere else in this process.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kghost")

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
        val id = "%06x".format(Random.nextInt(0x1000000))
        runCatching { context.dataStore.edit { it[installIdKey] = id } }
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
                    KGhostConfig()
                } else {
                    decodeConfig(raw)
                }
            }
            .catch { e ->
                // Upstream DataStore I/O failure — emit defaults so consumers don't hang.
                Timber.e(e, "KGhostConfig DataStore read failed — emitting defaults")
                emit(KGhostConfig())
            }
            .map { it.migrateToLatest() }
            .distinctUntilChanged()

    /**
     * Writes [config] to DataStore, replacing any previously stored value.
     * Encodes with [jsonForStorage] (compact, no encodeDefaults).
     *
     * @return true if the write succeeded, false if it threw (already logged). Callers must not
     *         report success on false — the screens surface an error status instead of clearing.
     */
    suspend fun saveConfig(config: KGhostConfig): Boolean {
        return try {
            context.dataStore.edit { prefs ->
                prefs[configKey] = jsonForStorage.encodeToString(config)
            }
            true
        } catch (e: Throwable) {
            // Surface a serialization/encode failure (e.g. a release R8 strip of generated
            // serializers) instead of silently swallowing it.
            Timber.e(e, "Failed to save KGhostConfig")
            false
        }
    }

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
                val raw = prefs[configKey]
                // Decode WITHOUT the silent default-fallback that [decodeConfig] uses on the read path.
                // On the WRITE path a present-but-undecodable blob must NOT be replaced with defaults:
                // doing so would persist defaults over the rider's settings on the very next toggle,
                // permanently wiping them. Let a genuine decode failure throw → the edit aborts, the bad
                // blob is preserved, and the caller surfaces a save-failed status. Note this only fires
                // on a structurally broken blob: a stale/removed ENUM value still decodes fine because
                // [jsonWithUnknownKeys] has coerceInputValues=true (the common forward-compat case).
                val current = if (raw == null) {
                    KGhostConfig()
                } else {
                    jsonWithUnknownKeys.decodeFromString<KGhostConfig>(raw)
                }.migrateToLatest()
                prefs[configKey] = jsonForStorage.encodeToString(transform(current))
            }
            true
        } catch (e: Throwable) {
            Timber.e(e, "Failed to update KGhostConfig")
            false
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

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
