package com.enderthor.kghost.engine

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
 *  pace: the s/m pace handed to onTick (after the verdict gates and the interstitial neutraliser), null = neutral. */
data class RaceStepResult(val tag: String, val ticked: Boolean, val pace: Double?)

/**
 * One race tick, extracted verbatim in behaviour from the route branch of the extension: the moving-time
 * race-clock freeze, the freshness-gated pace choice, and the guarded integrator tick. Caller guarantees
 * `st.moveStartS != null`. [gradePace] is consulted only when [gradeFresh] (a stale gradient describes a hill
 * the rider left). [neutralizeHistoryStep] neutral-fills the one HISTORY step after a no-route interstitial.
 */
fun advanceRace(
    st: RaceStepState, integ: GhostIntegrator, comparator: RaceComparator, coast: CoastingEstimator,
    riderDist: Double, elapsedS: Double, speedMs: Double?, lat: Double, lng: Double, hdg: Double,
    fixAgeOk: Boolean, gradeFresh: Boolean, targetSpeedMs: Double,
    historyPace: () -> Double?, gradePace: () -> Double?,
    neutralizeHistoryStep: Boolean,
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
    val tickPace = paceAfterInterstitial(comparator, neutralizeHistoryStep, paceNow)
    integ.onTick(riderDist, lat, lng, hdg, elapsedS - moveStart) { _, _, _ -> tickPace }
    // Latch on metres actually CONSUMED, not on a returned pace.
    if (comparator == RaceComparator.HISTORY && consumedHistory(matchedBefore, integ.matchedM)) {
        st.historyVerdictSeen = true
    }
    st.initialised = true
    st.lastRiderDist = riderDist
    return RaceStepResult(tag, ticked = true, pace = tickPace)
}
