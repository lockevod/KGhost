package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.engine.GhostPick
import com.enderthor.kvpartner.engine.LiveSegment
import com.enderthor.kvpartner.engine.RecordedGhostSource
import java.util.Locale

/**
 * Turns the loaded route plus recorded history into raceable [LiveSegment]s.
 *
 * The geometry, step by step (sub-project ② core):
 *
 *  1. For each [RecordedTrack] build a [PolylinePath] from its points (needs >= 2 points;
 *     tracks with fewer are skipped).
 *  2. **Coverage** — sample the ROUTE at a fixed distance step, project each sample point onto the
 *     TRACK path and read [Projection.perpDistM]. Mark a sample "covered" when its perpendicular
 *     distance to the track is `< params.toleranceM`. (Sampling, rather than testing only the
 *     route's own vertices, is required so the algorithm also works on coarse routes such as a
 *     straight two-vertex route whose mid-stretch is covered — see [coverageStepM].)
 *  3. **Intervals** — turn contiguous runs of covered route samples into route-distance intervals
 *     `[startM, endM]`. Merge intervals whose gap is `< params.mergeGapM`. Drop intervals shorter
 *     than `params.minSegmentM`.
 *  4. **Slice** — for each surviving interval, take the track points whose projection ONTO THE
 *     ROUTE ([PolylinePath.nearestProjection].distanceAlongM) lands inside `[startM, endM]`,
 *     ordered by route-distance. Reverse detection: if the track's own `distanceM` DECREASES as
 *     route-distance increases (the track was ridden opposite to the route direction), reverse the
 *     slice so it is ascending in track distance before building the ghost. The slice needs >= 2
 *     points (after de-duplicating equal track distances, which [GhostCurve] forbids); else the
 *     interval is skipped.
 *  5. **Ghost + pick** — build the ghost with [RecordedGhostSource.fromTrackSlice]. When several
 *     tracks produce a segment overlapping the SAME route interval, group by overlapping interval
 *     and apply [pick]: `BEST` keeps the segment whose ghost `totalTimeS` is smallest; `LAST` keeps
 *     the one whose track `startedAtEpoch` is largest. One [LiveSegment] per group, whose
 *     `[routeStartM, routeEndM]` is the **winner's own interval** — so `ghost.totalDistanceM ≈
 *     routeEndM − routeStartM` and the ghost covers the full segment width without a frozen tail.
 *  6. `hasElevation = false`, `elevationProfile = null` for now (Task 9/10 fills these from the
 *     route elevation polyline). The result is sorted ascending by `routeStartM`.
 *
 * Pure Kotlin (no Android).
 *
 * Design notes for cases the tests do not cover (recall-first: never silently drop a real overlap):
 *  - When grouping per-track segments into merged route intervals, two segments are considered the
 *    same stretch when their `[routeStartM, routeEndM]` ranges overlap at all. Groups are formed
 *    greedily. The emitted [LiveSegment] uses the WINNER's own interval (not the union span) so
 *    the ghost always covers the full declared stretch without a frozen tail.
 *  - Equal consecutive track distances inside a slice are collapsed (keeping the first), because
 *    [GhostCurve] requires strictly increasing distance. The collapsed point's time is preserved.
 */
object SegmentMatcher {

    data class Params(
        val toleranceM: Double = 25.0,
        val minSegmentM: Double = 300.0,
        val mergeGapM: Double = 80.0,
    )

    /** A segment candidate produced by a single track before cross-track [GhostPick] resolution. */
    private data class Candidate(
        val routeStartM: Double,
        val routeEndM: Double,
        val ghost: com.enderthor.kvpartner.engine.GhostCurve,
        val ghostLabel: String,
        val totalTimeS: Double,
        val startedAtEpoch: Long,
    )

    fun match(
        route: PolylinePath,
        tracks: List<RecordedTrack>,
        pick: GhostPick,
        params: Params = Params(),
    ): List<LiveSegment> {
        val candidates = ArrayList<Candidate>()

        for (track in tracks) {
            val modelPoints = track.points.map { it.toModel() }
            if (modelPoints.size < 2) continue
            val trackPath = PolylinePath(modelPoints.map { LatLng(it.lat, it.lng) })

            // Step 2 + 3: sample the route by distance, test coverage by the track, build intervals.
            val intervals = coveredRunsToIntervals(route, trackPath, params)

            // Step 4 + 5 (ghost): one candidate per interval.
            for (interval in intervals) {
                val slice = extractTrackSlice(route, modelPoints, interval)
                    ?: continue // < 2 usable points after extraction/dedup
                val label0 = "" // filled below once we know the ghost time
                val ghost = RecordedGhostSource.fromTrackSlice(slice, label0).curve()
                val label = labelFor(pick, ghost.totalTimeS)
                candidates += Candidate(
                    routeStartM = interval.first,
                    routeEndM = interval.second,
                    ghost = ghost,
                    ghostLabel = label,
                    totalTimeS = ghost.totalTimeS,
                    startedAtEpoch = track.startedAtEpoch,
                )
            }
        }

        // Step 5: group overlapping candidates across tracks; resolve with `pick`.
        val groups = groupOverlapping(candidates)
        val segments = groups.map { group ->
            val chosen = when (pick) {
                GhostPick.BEST -> group.minByOrNull { it.totalTimeS }!!
                GhostPick.LAST -> group.maxByOrNull { it.startedAtEpoch }!!
            }
            // Use the winner's own interval so ghost.totalDistanceM ≈ routeEndM − routeStartM.
            // A union span wider than the winner's track coverage would freeze the gap reading in
            // the tail (GhostCurve.timeAt clamps beyond totalDistanceM).
            LiveSegment(
                routeStartM = chosen.routeStartM,
                routeEndM = chosen.routeEndM,
                ghost = chosen.ghost,
                ghostLabel = chosen.ghostLabel,
                hasElevation = false,
                elevationProfile = null,
            )
        }

        // Step 6: sort by route start.
        return segments.sortedBy { it.routeStartM }
    }

    /**
     * Route sampling step (metres) for the coverage scan. A fraction of the smallest meaningful
     * segment so even a `minSegmentM` overlap is sampled many times, while staying cheap.
     */
    private const val coverageStepM = 25.0

    /**
     * Samples the route at [coverageStepM], tests each sample's perpendicular distance to the
     * track, and converts covered runs into merged, length-filtered route-distance intervals.
     */
    private fun coveredRunsToIntervals(
        route: PolylinePath,
        trackPath: PolylinePath,
        params: Params,
    ): List<Pair<Double, Double>> {
        val total = route.totalM
        val step = coverageStepM.coerceAtMost(total)
        val samples = ArrayList<Pair<Double, Boolean>>() // (distanceAlong, covered)
        var d = 0.0
        while (d < total) {
            val p = route.pointAtDistance(d)
            samples += d to (trackPath.nearestProjection(p).perpDistM < params.toleranceM)
            d += step
        }
        // Always include the route end as a sample so a covered run reaching the end closes cleanly.
        run {
            val p = route.pointAtDistance(total)
            samples += total to (trackPath.nearestProjection(p).perpDistM < params.toleranceM)
        }

        val runs = ArrayList<Pair<Double, Double>>()
        var runStart = -1.0
        var prevDist = 0.0
        for ((dist, cov) in samples) {
            if (cov) {
                if (runStart < 0.0) runStart = dist
            } else if (runStart >= 0.0) {
                runs += runStart to prevDist
                runStart = -1.0
            }
            prevDist = dist
        }
        if (runStart >= 0.0) runs += runStart to total

        // Merge runs separated by gaps < mergeGapM.
        val merged = ArrayList<Pair<Double, Double>>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run.first - last.second < params.mergeGapM) {
                merged[merged.size - 1] = last.first to maxOf(last.second, run.second)
            } else {
                merged += run
            }
        }

        // Drop intervals shorter than minSegmentM.
        return merged.filter { it.second - it.first >= params.minSegmentM }
    }

    /**
     * Window (m) ahead of the previously kept point's route-distance used when seeding the
     * forward-biased projection inside [extractTrackSlice]. Generous enough to bridge the route
     * sampling/decimation spacing, small enough that the return pass of an out-and-back route (whose
     * route-distance runs the other way) falls outside it.
     */
    private const val sliceFwdWindowM = 250.0

    /**
     * Small back window (m) for the slice projection. Tolerates GPS jitter / equirectangular error
     * without letting the projection snap onto an earlier-overlapping pass.
     */
    private const val sliceBackWindowM = 30.0

    /**
     * Extracts ONE clean monotonic pass of the track over `[interval]` of the route.
     *
     * The old implementation projected every track point by GLOBAL nearest and bucketed by
     * `[startM, endM]`. On an out-and-back route the outbound track points were geometrically valid
     * on BOTH the outbound and return vertex ranges, so they smeared across both intervals: the
     * resulting per-interval slice was non-monotonic in route-distance, the dedup dropped points,
     * and the ghost shrank (frozen tail re-emerged).
     *
     * Instead we walk the TRACK in recorded order and collect the LONGEST contiguous run of points
     * whose route-projection — seeded forward-biased from the previous kept point's route-distance —
     * stays inside `[startM, endM]` and is monotonic in route-distance. That yields a single pass
     * over the interval. The first point of each candidate run is seeded with a GLOBAL projection so
     * a run can start anywhere; every following point is projected within a window ahead of the
     * previous one, so the OTHER pass (whose route-distance runs the opposite way) is never picked.
     *
     * Reverse detection is preserved: if the chosen run descends in track distance it is reversed so
     * the slice ascends before the ghost is built. Returns null when fewer than two usable
     * (strictly-increasing-distance) points remain in the best run.
     */
    private fun extractTrackSlice(
        route: PolylinePath,
        trackPoints: List<TrackPoint>,
        interval: Pair<Double, Double>,
    ): List<TrackPoint>? {
        val (startM, endM) = interval

        var bestRun: List<Pair<TrackPoint, Double>> = emptyList()
        var currentRun = ArrayList<Pair<TrackPoint, Double>>()
        var prevRouteM = Double.NaN

        fun closeRun() {
            if (currentRun.size > bestRun.size) bestRun = currentRun
            currentRun = ArrayList()
            prevRouteM = Double.NaN
        }

        // Seed projection for a run-start point: constrained to the interval so an ambiguous shared
        // point (valid on both passes of an out-and-back route) is forced onto THIS interval's pass
        // rather than the global nearest, which may belong to the other pass.
        fun seedProjection(ll: LatLng): Double =
            route.nearestProjectionNear(
                ll,
                aroundDistanceM = startM,
                backWindowM = 0.0,
                fwdWindowM = endM - startM,
            ).distanceAlongM

        for (tp in trackPoints) {
            val ll = LatLng(tp.lat, tp.lng)
            // Seed the first point of a run inside the interval; extend forward-biased thereafter.
            val routeM = if (currentRun.isEmpty()) {
                seedProjection(ll)
            } else {
                route.nearestProjectionNear(
                    ll,
                    aroundDistanceM = prevRouteM,
                    backWindowM = sliceBackWindowM,
                    fwdWindowM = sliceFwdWindowM,
                ).distanceAlongM
            }

            val insideInterval = routeM in startM..endM
            // Monotonic forward in route-distance (allow equal: dedup handles it later).
            val monotonic = currentRun.isEmpty() || routeM >= prevRouteM - sliceBackWindowM

            if (insideInterval && monotonic) {
                currentRun.add(tp to routeM)
                prevRouteM = routeM
            } else {
                closeRun()
                // The breaking point may itself start a fresh run if it lands inside the interval.
                val seedM = seedProjection(ll)
                if (seedM in startM..endM) {
                    currentRun.add(tp to seedM)
                    prevRouteM = seedM
                }
            }
        }
        closeRun()

        if (bestRun.size < 2) return null

        val inside = bestRun.map { it.first }

        // Reverse detection: if track distance decreases as route distance increases, the track
        // was ridden opposite to the route -> reverse so the slice ascends in track distance.
        val oriented = if (inside.last().distanceM < inside.first().distanceM) inside.reversed() else inside

        // GhostCurve requires strictly increasing distance: collapse equal consecutive distances.
        val deduped = ArrayList<TrackPoint>(oriented.size)
        for (p in oriented) {
            if (deduped.isEmpty() || p.distanceM > deduped.last().distanceM) deduped += p
        }
        return if (deduped.size >= 2) deduped else null
    }

    /** Greedily groups candidates whose route-distance ranges overlap (transitively via union). */
    private fun groupOverlapping(candidates: List<Candidate>): List<List<Candidate>> {
        val sorted = candidates.sortedBy { it.routeStartM }
        val groups = ArrayList<MutableList<Candidate>>()
        var spanEnd = Double.NEGATIVE_INFINITY
        for (c in sorted) {
            if (groups.isEmpty() || c.routeStartM > spanEnd) {
                groups += mutableListOf(c)
                spanEnd = c.routeEndM
            } else {
                groups.last() += c
                spanEnd = maxOf(spanEnd, c.routeEndM)
            }
        }
        return groups
    }

    /**
     * The lat/lng on [this] route at cumulative distance [distM], by linear interpolation between
     * the two enclosing vertices. Clamps to the endpoints when out of range.
     */
    private fun PolylinePath.pointAtDistance(distM: Double): LatLng {
        if (distM <= 0.0) return points.first()
        if (distM >= totalM) return points.last()
        val hi = cumulativeM.indexOfFirst { it >= distM }
        val a = points[hi - 1]; val b = points[hi]
        val da = cumulativeM[hi - 1]; val db = cumulativeM[hi]
        val f = if (db == da) 0.0 else (distM - da) / (db - da)
        return LatLng(a.lat + f * (b.lat - a.lat), a.lng + f * (b.lng - a.lng))
    }

    /** `BEST` -> "PR m:ss"; `LAST` -> "Last m:ss". Deterministic for tests. */
    private fun labelFor(pick: GhostPick, totalTimeS: Double): String {
        val prefix = when (pick) {
            GhostPick.BEST -> "PR "
            GhostPick.LAST -> "Last "
        }
        return prefix + mmss(totalTimeS)
    }

    /** Formats seconds as `m:ss` (Locale.US). */
    private fun mmss(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }
}
