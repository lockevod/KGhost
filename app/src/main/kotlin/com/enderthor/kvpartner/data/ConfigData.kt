package com.enderthor.kvpartner.data

import com.enderthor.kvpartner.engine.GhostPick
import kotlinx.serialization.Serializable

/** Current schema version. Bump this and add a branch in [migrateToLatest] when defaults change. */
const val CONFIG_VERSION = 4

/** Default Virtual Partner target speed (12 km/h) used when the user hasn't set one. */
val DEFAULT_TARGET_SPEED_MS: Double = kmhToMs(12.0)

/** Controls which gap metric is displayed on the data fields. */
enum class GapDisplay { TIME, DISTANCE, BOTH }

/** Which icon to draw for the ghost on the map. Resolved to a drawable by the extension. */
enum class GhostIcon { GHOST, CYCLIST, ARROW, DOT }

/** On-map ghost icon size. The SDK has no size field, so each maps to a different-sized drawable. */
enum class GhostSize { SMALL, MEDIUM, LARGE }

/**
 * Persisted configuration for the KVPartner extension.
 *
 * Stored as a single JSON blob under the key `kvpartnerconfig` in the DataStore.
 * [targetSpeedMs] defaults to 12 km/h ([DEFAULT_TARGET_SPEED_MS]) when the user hasn't set a
 * target. 0.0 means the target was explicitly cleared (Virtual Partner inactive).
 */
@Serializable
data class KVPartnerConfig(
    val version: Int = CONFIG_VERSION,
    /** Target speed in m/s. Defaults to 12 km/h; 0.0 = target explicitly cleared (VP inactive). */
    val targetSpeedMs: Double = DEFAULT_TARGET_SPEED_MS,
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
    /** Whether to draw the ghost's live position on the map during a segment race. */
    val showGhostOnMap: Boolean = true,
    /** Which icon to draw for the ghost on the map. */
    val ghostIcon: GhostIcon = GhostIcon.GHOST,
    /** On-map ghost icon size (selects a different-sized drawable; the SDK has no size field). */
    val ghostSize: GhostSize = GhostSize.MEDIUM,
    /**
     * Epoch millis of the last successful history-import scan. Used by "Import new only" to skip
     * files not modified since. 0L means no scan has run yet (import everything).
     */
    val lastScanEpoch: Long = 0L,
) {
    /**
     * Returns the target speed if valid (> 0), or null when the target was explicitly cleared (0.0).
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
    var c = this
    // v1 → v2: race fields added with defaults; just stamp the version.
    if (c.version < 2) c = c.copy(version = 2)
    // v2 → v3: a never-set target (stored 0.0, the old default) becomes the new 12 km/h default.
    if (c.version < 3) c = c.copy(
        targetSpeedMs = if (c.targetSpeedMs <= 0.0) DEFAULT_TARGET_SPEED_MS else c.targetSpeedMs,
        version = 3,
    )
    // v3 → v4: ghost icon/size selection added; defaults (GHOST, MEDIUM) apply, just stamp.
    if (c.version < 4) c = c.copy(version = 4)
    return c
}
