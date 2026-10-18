package com.enderthor.kghost.engine

import com.enderthor.kghost.data.sanitizeTargetMs

/** The five fields the route branch mutates per race tick (today KGhostExtension fields
 *  firstMoveElapsedS, prevTickElapsedS, integLastRiderDist, integInitialised, historyVerdictSeen). */
class RaceStepState {
    @Volatile var moveStartS: Double? = null
    @Volatile var prevTickElapsedS: Double? = null
    @Volatile var lastRiderDist: Double = 0.0
    @Volatile var initialised: Boolean = false
    @Volatile var historyVerdictSeen: Boolean = false

    fun reset() {
        moveStartS = null; prevTickElapsedS = null; lastRiderDist = 0.0
        initialised = false; historyVerdictSeen = false
    }

    /** A fresh integrator: re-anchor and re-latch, but keep the race clock and the odometer baseline. */
    fun resetForNewRace() {
        initialised = false; historyVerdictSeen = false
    }
}

/** tag: "SEG" (historyPace answered), "GRADE" (gradePace answered), "TGT" (TARGET with a pace), "GP" (neutral).
 *  ticked: integ.onTick ran this call (false when the pendingHold guard skipped it).
 *  pace: the s/m pace handed to onTick (after the verdict gates), null = neutral. */
data class RaceStepResult(val tag: String, val ticked: Boolean, val pace: Double?)

/**
 * One race tick, extracted verbatim in behaviour from the route branch of the extension: the moving-time
 * race-clock freeze, the freshness-gated pace choice, and the guarded integrator tick. Caller guarantees
 * `st.moveStartS != null`. [gradePace] is consulted only when [gradeFresh] (a stale gradient describes a hill
 * the rider left).
 */
fun advanceRace(
    st: RaceStepState, integ: GhostIntegrator, comparator: RaceComparator, coast: CoastingEstimator,
    riderDist: Double, elapsedS: Double, speedMs: Double?, lat: Double, lng: Double, hdg: Double,
    fixAgeOk: Boolean, gradeFresh: Boolean, targetSpeedMs: Double,
    historyPace: () -> Double?, gradePace: () -> Double?,
): RaceStepResult {
    var moveStart = checkNotNull(st.moveStartS) { "advanceRace without a race clock origin" }
    val prevEl = st.prevTickElapsedS
    val stoppedNow = speedMs != null && speedMs < StalenessLogic.MIN_MOVING_MS
    if (prevEl != null && elapsedS > prevEl &&
        raceClockFreezes(
            stoppedNow = stoppedNow,
            settledPending = coast.settledPending,
            odometerAdvanced = riderDist > st.lastRiderDist,
            pendingSample = coast.pendingSample,
        )) {
        moveStart += (elapsedS - prevEl)
        st.moveStartS = moveStart
    }
    st.prevTickElapsedS = elapsedS

    val fixFresh = verdictAllowed(fixAgeOk, coast.quality)
    var tag = "GP"
    val paceNow = selectRacePace(
        comparator = comparator,
        verdictAllowed = fixFresh,
        targetSpeedMs = targetSpeedMs,
        historyPace = { historyPace()?.also { tag = "SEG" } },
        gradePace = { if (gradeFresh) gradePace()?.also { tag = "GRADE" } else null },
    )
    if (comparator == RaceComparator.TARGET && paceNow != null) tag = "TGT"

    // Keyed on pendingHold (not pendingSample) so a genuine stop inside a pending interval is skipped too;
    // `initialised` forces the first call through so a restored lead re-anchors inside it.
    if (coast.pendingHold && st.initialised) return RaceStepResult(tag, ticked = false, pace = null)
    val matchedBefore = integ.matchedM
    integ.onTick(riderDist, lat, lng, hdg, elapsedS - moveStart) { _, _, _ -> paceNow }
    // Latch on metres actually CONSUMED, not on a returned pace.
    if (comparator == RaceComparator.HISTORY && consumedHistory(matchedBefore, integ.matchedM)) {
        st.historyVerdictSeen = true
    }
    st.initialised = true
    st.lastRiderDist = riderDist
    return RaceStepResult(tag, ticked = true, pace = paceNow)
}

/** [advanceRace] with NO history tier: PacePatch is built from the route corridor, so after the route ends a
 *  HISTORY race falls to GradePace → neutral. Production and the rigs both call this, so post-route can't
 *  consult a patch by construction. */
fun postRouteStep(
    st: RaceStepState, integ: GhostIntegrator, comparator: RaceComparator, coast: CoastingEstimator,
    riderDist: Double, elapsedS: Double, speedMs: Double?, lat: Double, lng: Double, hdg: Double,
    fixAgeOk: Boolean, gradeFresh: Boolean, targetSpeedMs: Double, gradePace: () -> Double?,
): RaceStepResult = advanceRace(
    st, integ, comparator, coast, riderDist, elapsedS, speedMs, lat, lng, hdg,
    fixAgeOk, gradeFresh, targetSpeedMs, historyPace = { null }, gradePace = gradePace,
)

/**
 * The post-route GapState (sign convention of [GapState]: positive seconds = behind). Ahead/level: the
 * integrator's breadcrumb metres. Behind there is no route curve, so metres = deficit × ghost pace
 * ([postRouteBehindMetres]): TARGET → the target pace (exact on every tick); HISTORY → [step]'s pace, the one
 * the race actually used this tick, so a stale fix / stale gradient gives NaN ("---") — freshness is gated in
 * the step, never re-gated by the caller.
 */
fun postRouteGap(
    integ: GhostIntegrator, comparator: RaceComparator, step: RaceStepResult,
    riderDist: Double, targetSpeedMs: Double, estimated: Boolean,
): GapState {
    val metres = if (integ.gapTimeS < 0.0) {
        val ghostTpm = when (comparator) {
            RaceComparator.TARGET -> 1.0 / sanitizeTargetMs(targetSpeedMs)
            RaceComparator.HISTORY -> step.pace
        }
        postRouteBehindMetres(-integ.gapTimeS, ghostTpm)
    } else {
        integ.gapDistM
    }
    return GapState(
        gapTimeS = -integ.gapTimeS,
        gapDistanceM = metres,
        progressM = riderDist,
        ghostProgressM = riderDist - integ.gapDistM,
        ahead = integ.gapTimeS > 0.0,
        estimated = estimated,
        active = true,
    )
}
