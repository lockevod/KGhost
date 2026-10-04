package com.enderthor.kghost.engine

import com.enderthor.kghost.data.sanitizeTargetMs
import com.enderthor.kghost.geo.PolylinePath
import kotlin.math.min

/**
 * What a route race measures the rider against, chosen ONCE per race and never mixed tick to tick.
 * HISTORY: the rider's own past pace (PacePatch, then GradePace, else the neutral fill).
 * TARGET: the configured target speed over every metre — for routes history barely touches.
 *
 * Field logs 9fe84d/0cbb96: ONE candidate track brushing ~3% of the route made `hasHistory` true, so the
 * target tier never ran and 97% neutral fill pinned the gap at 0 for 1-2 h.
 */
enum class RaceComparator { HISTORY, TARGET }

/** Below this length-weighted coverage (and with no usable grade model) a route races the target.
 *  Product policy, not a continuity guarantee: a route sitting right at the line can classify either
 *  way across loads — latching the comparator to the race only removes the cliff MID-race. */
const val HISTORY_COVERAGE_MIN = 0.10

/**
 * Length-weighted fraction of the PLANNED [path] whose [stepM] samples [hit] answers. Production passes
 * `PacePatch.pace(..., LAST) != null` — the same tier-1 predicate the tick uses (hit/miss does not depend
 * on the pick). Each sample stands for the stretch up to the next one; the final stretch is usually
 * shorter and counts only its real length, so a short hit tail can't masquerade as a whole step.
 * A zero-length path → 0. [checkCancel] runs every 200 samples: a 200 km route is 8000 lookups.
 */
fun historyCoverage(
    path: PolylinePath,
    hit: (lat: Double, lng: Double, bearingDeg: Double) -> Boolean,
    stepM: Double = 25.0,
    checkCancel: () -> Unit = {},
): Double {
    require(stepM > 0.0) { "stepM must be > 0" }
    val total = path.totalM
    if (!(total > 0.0)) return 0.0
    var covered = 0.0
    var i = 0
    while (true) {
        // Index-derived, not accumulated: d stays exact so the tail length is exact too.
        val d = i * stepM
        if (d >= total) break
        val s = path.sampleAt(d)
        if (hit(s.location.lat, s.location.lng, s.bearingDeg)) covered += min(stepM, total - d)
        if (++i % 200 == 0) checkCancel()
    }
    return covered / total
}

/** TARGET iff nothing else can answer most of the route: no usable grade bin AND history under the floor.
 *  A non-finite coverage is treated as none. */
fun chooseComparator(coverage: Double, gradeUsable: Boolean): RaceComparator {
    val c = if (coverage.isFinite()) coverage else 0.0
    return if (!gradeUsable && c < HISTORY_COVERAGE_MIN) RaceComparator.TARGET else RaceComparator.HISTORY
}

/**
 * This tick's pace (s/m), or null for the neutral fill. [verdictAllowed] is the caller's freshness gate,
 * the same one tiers 1-2 already pass. TARGET never evaluates the history lambdas — a target race that
 * consulted a patch would mix comparators. HISTORY never falls to the target (the old tier 4): an
 * unanswered metre is neutral, so the lead it shows is history-earned only.
 */
fun selectRacePace(
    comparator: RaceComparator,
    verdictAllowed: Boolean,
    targetSpeedMs: Double,
    historyPace: () -> Double?,
    gradePace: () -> Double?,
): Double? {
    if (!verdictAllowed) return null
    return when (comparator) {
        RaceComparator.TARGET -> 1.0 / sanitizeTargetMs(targetSpeedMs)
        RaceComparator.HISTORY -> historyPace() ?: gradePace()
    }
}

enum class RouteGapPublication { INACTIVE, HOLD, PUBLISH }

/**
 * What the route gap shows this tick. A HISTORY race that has not consumed a single history metre shows
 * "---" (inactive) rather than a 0 that only means "nothing to compare" — and does so ahead of the hold,
 * else a frozen number from the previous mode (VP → route switch) would survive. The hold freezes only a
 * race that has already published, so a restored lead appears on the first tick even inside a hold.
 */
fun routeGapPublication(
    comparator: RaceComparator,
    historyVerdictSeen: Boolean,
    pendingHold: Boolean,
    publishedThisRace: Boolean,
): RouteGapPublication = when {
    comparator == RaceComparator.HISTORY && !historyVerdictSeen -> RouteGapPublication.INACTIVE
    pendingHold && publishedThisRace -> RouteGapPublication.HOLD
    else -> RouteGapPublication.PUBLISH
}

/**
 * Did this tick consume history? Only a rise in the integrator's matched metres counts: a first anchor,
 * a restore anchor or a dd<=0 tick can be handed a pace yet consume nothing (Codex counterexample), so
 * "a pace was returned" would latch the verdict on a tick that compared nothing.
 */
fun consumedHistory(matchedBefore: Double, matchedAfter: Double): Boolean = matchedAfter > matchedBefore
