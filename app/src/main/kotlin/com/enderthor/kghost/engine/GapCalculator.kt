package com.enderthor.kghost.engine

/**
 * Pure function: combines your progress and elapsed time with a ghost curve to produce a [GapState].
 *
 * Formulas:
 *   gapTimeS     = elapsedS − curve.timeAt(progressM)
 *   gapDistanceM = progressM − curve.distanceAt(elapsedS)
 *   ghostProgressM = curve.distanceAt(elapsedS)
 *   ahead        = gapTimeS <= 0
 *   estimated    = !fresh   (a prolonged-GPS-loss dead-reckoned estimate; shown marked, not blanked)
 *   active       = true
 */
object GapCalculator {

    /**
     * Computes the gap between the rider and the ghost.
     *
     * @param progressM  Rider's current accumulated distance in metres.
     * @param elapsedS   Ride elapsed time in seconds.
     * @param curve      Ghost curve (bidirectional interpolation).
     * @param fresh      Whether the distance source is LIVE (a real fix, a brief in-window coast, or a
     *                   legitimate stop). False only during a prolonged GPS loss → [GapState.estimated].
     * @return           A fully populated, active [GapState].
     */
    fun compute(progressM: Double, elapsedS: Double, curve: GhostCurve, fresh: Boolean): GapState {
        // Reject non-finite inputs (NaN/±Inf) — a corrupt stream value must not propagate into the
        // gap state and crash the formatters/renderers downstream.
        if (!progressM.isFinite() || !elapsedS.isFinite()) return GapState.inactive()
        val ghostTimeAtMyDistance = curve.timeAt(progressM)
        val ghostDistanceNow = curve.distanceAt(elapsedS)
        val gapTimeS = elapsedS - ghostTimeAtMyDistance
        val gapDistanceM = progressM - ghostDistanceNow
        return GapState(
            gapTimeS = gapTimeS,
            gapDistanceM = gapDistanceM,
            progressM = progressM,
            ghostProgressM = ghostDistanceNow,
            ahead = gapTimeS <= 0.0,
            estimated = !fresh,
            active = true,
        )
    }
}
