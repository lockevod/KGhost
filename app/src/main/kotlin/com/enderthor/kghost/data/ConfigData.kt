package com.enderthor.kghost.data

import com.enderthor.kghost.engine.GhostPick
import kotlinx.serialization.Serializable

/** Current schema version. Bump this and add a branch in [migrateToLatest] when defaults change. */
const val CONFIG_VERSION = 5

/** Default Ghost Pace target speed (12 km/h) used when the user hasn't set one. */
val DEFAULT_TARGET_SPEED_MS: Double = kmhToMs(12.0)

/** Physically-plausible cycling ceiling for the VP target (30 m/s ≈ 108 km/h). */
const val MAX_TARGET_SPEED_MS: Double = 30.0

/**
 * Clamps a raw target speed (m/s) to a sane Ghost-Pace value: finite and > 0, capped at
 * [MAX_TARGET_SPEED_MS]; otherwise falls back to [DEFAULT_TARGET_SPEED_MS]. Shared by the global
 * [KGhostConfig.targetMs] and the per-profile resolver so both clamp identically.
 */
fun sanitizeTargetMs(raw: Double): Double =
    raw.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_TARGET_SPEED_MS) ?: DEFAULT_TARGET_SPEED_MS

/** Controls which gap metric is displayed on the data fields. */
enum class GapDisplay { TIME, DISTANCE, BOTH }

/** Which icon to draw for the ghost on the map. Resolved to a drawable by the extension. */
enum class GhostIcon { GHOST, CYCLIST, ARROW, DOT }

/** On-map ghost icon size. The SDK has no size field, so each maps to a different-sized drawable. */
enum class GhostSize { SMALL, MEDIUM, LARGE }

/**
 * Per-profile override, keyed by `RideProfile.id`, auto-learned the first time a profile is seen.
 * [useGlobal] = true → inherit the global Ghost-Pace target and stay enabled (the default for every
 * newly-seen profile). When false, [targetSpeedMs] and [enabled] apply to this profile only.
 */
@Serializable
data class ProfileSetting(
    val profileId: String = "",
    val profileName: String = "",
    val useGlobal: Boolean = true,
    val targetSpeedMs: Double = DEFAULT_TARGET_SPEED_MS,
    val enabled: Boolean = true,
)

/**
 * Persisted configuration for the KGhost extension.
 *
 * Stored as a single JSON blob under the key `kghostconfig` in the DataStore.
 * [targetSpeedMs] defaults to 12 km/h ([DEFAULT_TARGET_SPEED_MS]) when the user hasn't set a
 * target. 0.0 means the target was explicitly cleared (Ghost Pace inactive).
 */
@Serializable
data class KGhostConfig(
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
    /** Whether to emit an in-ride alert when the rider leaves a recorded segment. */
    val segmentExitAlert: Boolean = false,
    /** Whether to draw the ghost's live position on the map during a segment race. */
    val showGhostOnMap: Boolean = true,
    /** Which icon to draw for the ghost on the map. (Its SIZE follows the map zoom automatically.) */
    val ghostIcon: GhostIcon = GhostIcon.GHOST,
    /** Whether to write the diagnostic logs to a file so a ride can be studied later (default off). */
    val fileLogging: Boolean = false,
    /**
     * Epoch millis of the last successful history-import scan. Used by "Import new only" to skip
     * files not modified since. 0L means no scan has run yet (import everything).
     */
    val lastScanEpoch: Long = 0L,
    /** Master kill-switch: when false the whole extension is inert (no gap, recording, ghost, alerts). */
    val masterEnabled: Boolean = true,
    /** Auto-learned per-profile overrides, keyed by RideProfile.id. Empty = every profile uses global. */
    val profileSettings: List<ProfileSetting> = emptyList(),
) {
    /**
     * The Ghost Pace target speed (m/s) — ALWAYS valid and present. The VP can never be
     * deactivated: it is the fallback pace the ghost runs at on stretches with no recorded history and
     * the default mode when no route is loaded, so a target must always exist. Returns [targetSpeedMs]
     * when it is finite and > 0 (clamped to [MAX_TARGET_SPEED_MS]), otherwise [DEFAULT_TARGET_SPEED_MS]
     * (12 km/h) — so a never-set, zeroed, or out-of-range blob still drives a sane 12 km/h partner.
     * This is the single source of truth the engine and the Partner field read.
     */
    fun targetMs(): Double = sanitizeTargetMs(targetSpeedMs)
}

/** Metres in a statute mile (for imperial conversions). */
const val METRES_PER_MILE = 1609.344

/** Converts a speed in km/h to m/s. */
fun kmhToMs(kmh: Double): Double = kmh / 3.6

/** Converts a speed in mph to m/s. */
fun mphToMs(mph: Double): Double = mph * METRES_PER_MILE / 3600.0

/** m/s → km/h. */
fun msToKmh(ms: Double): Double = ms * 3.6

/** m/s → mph. */
fun msToMph(ms: Double): Double = ms * 3600.0 / METRES_PER_MILE

/**
 * Converts a pace in min/km to m/s.
 * Returns 0.0 for non-positive input (guard against divide-by-zero).
 */
fun paceMinKmToMs(minPerKm: Double): Double = if (minPerKm > 0) 1000.0 / (minPerKm * 60.0) else 0.0

/** Converts a pace in min/mile to m/s. Returns 0.0 for non-positive input. */
fun paceMinMiToMs(minPerMi: Double): Double = if (minPerMi > 0) METRES_PER_MILE / (minPerMi * 60.0) else 0.0

/** m/s → pace min/km (0.0 when not moving). */
fun msToPaceMinKm(ms: Double): Double = if (ms > 0) 1000.0 / ms / 60.0 else 0.0

/** m/s → pace min/mile (0.0 when not moving). */
fun msToPaceMinMi(ms: Double): Double = if (ms > 0) METRES_PER_MILE / ms / 60.0 else 0.0

/**
 * Applies any pending schema migrations and returns an up-to-date [KGhostConfig].
 * Add `if (version < N) { return copy(…, version = N) }` branches when bumping CONFIG_VERSION.
 * Always called after JSON decoding so every consumer always sees the current schema.
 */
fun KGhostConfig.migrateToLatest(): KGhostConfig {
    var c = this
    // v1 → v2: race fields added with defaults; just stamp the version.
    if (c.version < 2) c = c.copy(version = 2)
    // v2 → v3: a never-set target (stored 0.0, the old default) becomes the new 12 km/h default.
    if (c.version < 3) c = c.copy(
        targetSpeedMs = if (c.targetSpeedMs <= 0.0) DEFAULT_TARGET_SPEED_MS else c.targetSpeedMs,
        version = 3,
    )
    // v3 → v4: ghost icon selection added (size is automatic by zoom); default GHOST applies, just stamp.
    if (c.version < 4) c = c.copy(version = 4)
    // v4 → v5: master kill-switch + per-profile overrides added; both take Kotlin defaults
    // (masterEnabled = true, profileSettings = empty), so existing installs behave identically.
    if (c.version < 5) c = c.copy(version = 5)
    return c
}
