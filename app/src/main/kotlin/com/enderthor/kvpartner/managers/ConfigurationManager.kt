package com.enderthor.kvpartner.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.data.migrateToLatest
import com.enderthor.kvpartner.extension.jsonForStorage
import com.enderthor.kvpartner.extension.jsonWithUnknownKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import timber.log.Timber

/**
 * Process-wide DataStore instance for KVPartner settings.
 * Defined once here so both [ConfigurationManager] and MainActivity can import it.
 * The DataStore API requires exactly one delegate per [name] per [Context] — never create
 * another `preferencesDataStore("kvpartner")` anywhere else in this process.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kvpartner")

/**
 * Manages persistent configuration for the KVPartner extension.
 * Stores a single [KVPartnerConfig] JSON blob under the key [CONFIG_KEY] in [dataStore].
 */
class ConfigurationManager(private val context: Context) {

    private val configKey = stringPreferencesKey("kvpartnerconfig")

    /**
     * Emits the current [KVPartnerConfig] and re-emits whenever it changes in DataStore.
     * Decodes with [jsonWithUnknownKeys] (forward-compat) and applies [migrateToLatest].
     * Falls back to [KVPartnerConfig] defaults on any decode or I/O error so the extension
     * never hangs waiting for a value that will never arrive.
     */
    fun loadConfigFlow(): Flow<KVPartnerConfig> =
        context.dataStore.data
            .map { prefs ->
                val raw = prefs[configKey]
                if (raw == null) {
                    KVPartnerConfig()
                } else {
                    decodeConfig(raw)
                }
            }
            .catch { e ->
                // Upstream DataStore I/O failure — emit defaults so consumers don't hang.
                Timber.e(e, "KVPartnerConfig DataStore read failed — emitting defaults")
                emit(KVPartnerConfig())
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
    suspend fun saveConfig(config: KVPartnerConfig): Boolean {
        return try {
            context.dataStore.edit { prefs ->
                prefs[configKey] = jsonForStorage.encodeToString(config)
            }
            true
        } catch (e: Throwable) {
            // Surface a serialization/encode failure (e.g. a release R8 strip of generated
            // serializers) instead of silently swallowing it.
            Timber.e(e, "Failed to save KVPartnerConfig")
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
    suspend fun updateConfig(transform: (KVPartnerConfig) -> KVPartnerConfig): Boolean {
        return try {
            context.dataStore.edit { prefs ->
                val current = prefs[configKey]?.let { decodeConfig(it) }?.migrateToLatest() ?: KVPartnerConfig()
                prefs[configKey] = jsonForStorage.encodeToString(transform(current))
            }
            true
        } catch (e: Throwable) {
            Timber.e(e, "Failed to update KVPartnerConfig")
            false
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun decodeConfig(raw: String): KVPartnerConfig {
        return try {
            jsonWithUnknownKeys.decodeFromString<KVPartnerConfig>(raw)
        } catch (e: Throwable) {
            val snippet = raw.take(200).replace("\n", " ")
            Timber.e(
                e,
                "Failed to decode KVPartnerConfig (%s: %s) — raw[0..200] = %s",
                e.javaClass.simpleName,
                e.message,
                snippet,
            )
            KVPartnerConfig()
        }
    }
}
