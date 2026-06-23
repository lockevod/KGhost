package com.enderthor.kghost.engine

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
 * @param ahead         True when gapTimeS < 0 (strictly faster than the ghost); a dead-heat at 0 is
 *                      neither ahead nor behind (ahead=false), matching [inactive].
 * @param estimated     True when this value is a dead-reckoned ESTIMATE during a prolonged GPS loss
 *                      (the position has been frozen-while-moving past the coast window). The field
 *                      keeps SHOWING the value (it never blanks for GPS loss) but renders it in the
 *                      estimate colour to signal it is extrapolated, not measured. A brief dropout
 *                      (within the coast window) and a legitimate stop both stay false.
 * @param active        False when there is nothing to show: ride not recording, no first data
 *                      yet, a sustained GPS loss (give-up), or a non-finite gap. NOT for "no
 *                      target" — the Ghost Pace target is always present (defaults to 12 km/h).
 */
data class GapState(
    val gapTimeS: Double,
    val gapDistanceM: Double,
    val progressM: Double,
    val ghostProgressM: Double,
    val ahead: Boolean,
    val estimated: Boolean,
    val active: Boolean,
) {
    companion object {
        /** Returns an inactive state — nothing to show (not recording / no data yet / GPS give-up). */
        fun inactive() = GapState(
            gapTimeS = 0.0,
            gapDistanceM = 0.0,
            progressM = 0.0,
            ghostProgressM = 0.0,
            ahead = false,
            estimated = false,
            active = false,
        )
    }
}
