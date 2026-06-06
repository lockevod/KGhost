package com.enderthor.kvpartner.engine

/**
 * Immutable output of the gap engine.
 *
 * Sign convention (mathematical): being ahead means [gapTimeS] is negative (you reach your
 * current distance faster than the ghost) and [gapDistanceM] is positive (you are further along
 * than the ghost at the current elapsed time). The UI/display layer is responsible for flipping
 * the sign for human-readable presentation (e.g. "+20 s" when ahead).
 *
 * @param gapTimeS      elapsedS − curve.timeAt(progressM). Negative when ahead.
 * @param gapDistanceM  progressM − curve.distanceAt(elapsedS). Positive when ahead.
 * @param progressM     Your current position on the ghost's distance axis (metres).
 * @param ghostProgressM Ghost's current position at elapsedS (metres). Useful for rendering.
 * @param ahead         True when gapTimeS <= 0 (you are at least as fast as the ghost).
 * @param stale         True when the GPS/distance source is not fresh.
 * @param active        False when no target is configured or the ride is not recording.
 */
data class GapState(
    val gapTimeS: Double,
    val gapDistanceM: Double,
    val progressM: Double,
    val ghostProgressM: Double,
    val ahead: Boolean,
    val stale: Boolean,
    val active: Boolean,
) {
    companion object {
        /** Returns an inactive state — no target configured or ride not in Recording state. */
        fun inactive() = GapState(
            gapTimeS = 0.0,
            gapDistanceM = 0.0,
            progressM = 0.0,
            ghostProgressM = 0.0,
            ahead = false,
            stale = false,
            active = false,
        )
    }
}
