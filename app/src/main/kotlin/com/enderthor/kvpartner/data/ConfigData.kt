package com.enderthor.kvpartner.data

import com.enderthor.kvpartner.engine.GhostPick
import kotlinx.serialization.Serializable

/** Current schema version. Bump this and add a branch in [migrateToLatest] when defaults change. */
const val CONFIG_VERSION = 2

/** Controls which gap metric is displayed on the data fields. */
enum class GapDisplay { TIME, DISTANCE, BOTH }

/**
 * Persisted configuration for the KVPartner extension.
 *
 * Stored as a single JSON blob under the key `kvpartnerconfig` in the DataStore.
 * [targetSpeedMs] = 0.0 means no target is configured (Virtual Partner inactive).
 */
@Serializable
data class KVPartnerConfig(
    val version: Int = CONFIG_VERSION,
    /** Target speed in m/s. 0.0 = no target configured (Virtual Partner inactive). */
    val targetSpeedMs: Double = 0.0,
    /** Which gap metric to display on the fields. */
    val gapDisplay: GapDisplay = GapDisplay.BOTH,
    /** Whether the Race Your Own feature is enabled. */
    val raceEnabled: Boolean = true,
    /** Whether to automatically record each ride as a GPS track for future ghost comparison. */
    val autoRecord: Boolean = true,
    /** Which past ghost to use when multiple recorded tracks cover the same segment. */
    val ghostPick: GhostPick = GhostPick.BEST,
    /** Whether to emit an in-ride alert when the rider enters a recorded segment. */
    val segmentEntryAlert: Boolean = false,
) {
    /**
     * Returns the target speed if valid (> 0), or null when no target is configured.
     * Data fields and the engine use this to decide whether the Virtual Partner is active.
     */
    fun validTargetOrNull(): Double? = targetSpeedMs.takeIf { it > 0.0 }
}

/** Converts a speed in km/h to m/s. */
fun kmhToMs(kmh: Double): Double = kmh / 3.6

/**
 * Converts a pace in min/km to m/s.
 * Returns 0.0 for non-positive input (guard against divide-by-zero).
 */
fun paceMinKmToMs(minPerKm: Double): Double = if (minPerKm > 0) 1000.0 / (minPerKm * 60.0) else 0.0

/**
 * Applies any pending schema migrations and returns an up-to-date [KVPartnerConfig].
 * Add `if (version < N) { return copy(…, version = N) }` branches when bumping CONFIG_VERSION.
 * Always called after JSON decoding so every consumer always sees the current schema.
 */
fun KVPartnerConfig.migrateToLatest(): KVPartnerConfig {
    // v1 → v2: race fields added with defaults; just stamp the version.
    if (version < 2) return copy(version = 2)
    return this
}
