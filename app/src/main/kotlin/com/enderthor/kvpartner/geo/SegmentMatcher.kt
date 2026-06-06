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
 *     the one whose track `startedAtEpoch` is largest. One [LiveSegment] per merged interval.
 *  6. `hasElevation = false`, `elevationProfile = null` for now (Task 9/10 fills these from the
 *     route elevation polyline). The result is sorted ascending by `routeStartM`.
 *
 * Pure Kotlin (no Android).
 *
 * Design notes for cases the tests do not cover (recall-first: never silently drop a real overlap):
 *  - When grouping per-track segments into merged route intervals, two segments are considered the
 *    same stretch when their `[routeStartM, routeEndM]` ranges overlap at all. Groups are formed
 *    greedily over the union span, which keeps a genuine shared stretch as a single segment.
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
            // Merged interval spans the union of the group so a shared stretch is one segment.
            val start = group.minOf { it.routeStartM }
            val end = group.maxOf { it.routeEndM }
            LiveSegment(
                routeStartM = start,
                routeEndM = end,
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
     * Extracts the track points projecting onto `[interval]` of the route, ordered by route
     * distance, reversing when the track runs opposite to the route. Returns null when fewer than
     * two usable (strictly-increasing-distance) points remain.
     */
    private fun extractTrackSlice(
        route: PolylinePath,
        trackPoints: List<TrackPoint>,
        interval: Pair<Double, Double>,
    ): List<TrackPoint>? {
        val (startM, endM) = interval
        // Project each track point onto the route; keep those inside the interval.
        val inside = trackPoints
            .map { it to route.nearestProjection(LatLng(it.lat, it.lng)).distanceAlongM }
            .filter { it.second in startM..endM }
            .sortedBy { it.second } // route order
            .map { it.first }

        if (inside.size < 2) return null

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
