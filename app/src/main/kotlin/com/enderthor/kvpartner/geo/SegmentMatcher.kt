package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.engine.GhostPick
import com.enderthor.kvpartner.engine.LiveSegment
import com.enderthor.kvpartner.engine.RecordedGhostSource
import timber.log.Timber
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
        /**
         * Recorded-history COVERAGE tolerance (metres): the maximum perpendicular distance from a
         * route sample to a recorded track for that sample to count as "covered" (step 2 above).
         *
         * This is the cross-day GPS budget for the "race your own" use case: the SAME road ridden on
         * a DIFFERENT day routinely offsets 15–40 m (urban canyon, multipath, a different fix), so a
         * tight value silently drops a real match below [minSegmentM] and yields no segment. Default
         * 35.0 absorbs that day-to-day offset while staying well below the road-spacing that would
         * start merging parallel roads.
         *
         * This is DISTINCT from live route-snapping tolerance (RouteProjectedProgress), which decides
         * whether the rider's CURRENT fix is on the loaded route; that one stays tighter because it
         * judges a single live position, not the overlap of two recorded histories.
         */
        val toleranceM: Double = 35.0,
        val minSegmentM: Double = 300.0,
        val mergeGapM: Double = 80.0,
        /**
         * Safety budget / high backstop: process at most this many candidate tracks. When more
         * candidates are supplied, the [maxTracks] most RECENT (largest [RecordedTrack.startedAtEpoch])
         * are kept and the rest are skipped (logged, never silently dropped). Deterministic given the
         * same inputs (stable sort by epoch descending), so tests stay reproducible.
         *
         * This is NOT the primary cap: the primary, relevance-ranked cap is [TrackStore.loadTopCandidates],
         * which pre-ranks by route overlap before parsing. This in-matcher cap is only a HIGH safety
         * backstop and is intentionally set well above realistic histories (e.g. 79 tracks) so it never
         * triggers in practice and so a short perfect-match ride is never evicted in favour of long
         * "neighbour" rides. Since the grid made matching O(n) per track, a tight cap is no longer
         * needed. (Long-term: rank candidates by true along-route overlap / index tracks by PATH cells
         * instead of bbox cells — deferred.)
         */
        val maxTracks: Int = 120,
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

    /**
     * Seed strategy for [extractTrackSlice]'s candidate-chain loop.
     *
     *  - ALL_SEEDS: the original behaviour — build one candidate chain per track point.
     *  - CAN_START: build a chain only from points that can actually START a non-empty chain, i.e.
     *    whose seed projection is on-route (`perpDist < tol`) AND lands inside the interval. This is
     *    PROVEN result-identical to ALL_SEEDS by [SegmentSliceSeedDiffTest]: a seed that is not
     *    "can-start" keeps nothing as its first point, so `buildChain` returns an empty chain that the
     *    `size < 2` filter already discards — dropping it from the loop cannot change the winner.
     *  - CORRIDOR_ENTRY: a STRONGER bound (only can-start points whose predecessor was not can-start)
     *    that is NOT result-identical (an out-and-back return pass and some random fixtures select a
     *    different winning chain). It is retained ONLY so [SegmentSliceSeedDiffTest] can demonstrate
     *    the difference and document why it is deferred. NEVER use it in production.
     *
     * Production default is [CAN_START]. The field is mutable only so the diff test can switch modes.
     */
    internal enum class SeedStrategy { ALL_SEEDS, CAN_START, CORRIDOR_ENTRY }

    /** Production default: see [SeedStrategy]. */
    @Volatile internal var seedStrategy: SeedStrategy = SeedStrategy.CAN_START

    fun match(
        route: PolylinePath,
        tracks: List<RecordedTrack>,
        pick: GhostPick,
        params: Params = Params(),
    ): List<LiveSegment> {
        val candidates = ArrayList<Candidate>()

        // Safety budget (B), HIGH BACKSTOP ONLY: cap the candidate set to the maxTracks most-recent
        // tracks so a pathologically large history can never make match() grind. With the default
        // maxTracks=120 this will NOT trigger at realistic histories (≤120 tracks); the PRIMARY,
        // relevance-ranked cap is TrackStore.loadTopCandidates (pre-ranks by route overlap before
        // parsing). The cap here is deterministic — a stable sort by startedAtEpoch descending — so
        // tests stay reproducible. (The cap test in SegmentMatcherTest passes an explicit small
        // maxTracks to exercise this path.)
        val selected = if (tracks.size > params.maxTracks) {
            Timber.w(
                "SegmentMatcher: capped to %d of %d candidate tracks (most-recent kept)",
                params.maxTracks, tracks.size,
            )
            tracks.sortedByDescending { it.startedAtEpoch }.take(params.maxTracks)
        } else {
            tracks
        }

        for (track in selected) {
            val modelPoints = track.points.map { it.toModel() }
            if (modelPoints.size < 2) continue
            val trackPath = PolylinePath(modelPoints.map { LatLng(it.lat, it.lng) })

            // Step 2 + 3: sample the route by distance, test coverage by the track, build intervals.
            val intervals = coveredRunsToIntervals(route, trackPath, params)

            // Step 4 + 5 (ghost): one candidate per interval.
            for (interval in intervals) {
                val slice = extractTrackSlice(route, modelPoints, interval, params.toleranceM)
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
        val tol = params.toleranceM

        // Allocation-free coverage scan. The original code built an ArrayList<Pair<Double, Boolean>>
        // of every sample and allocated a LatLng + a Projection per sample (route.totalM/25 ≈ 1700
        // samples × N tracks → the GC storm). Here we:
        //   - compute each sample's coordinate into the scratch DoubleArray below (no LatLng), and
        //   - call trackPath.nearestPerpDistM (no Projection), and
        //   - fold runs on the fly (no samples list).
        // Result: ~O(1) allocation per sample instead of one object per sample. The run/interval
        // boundary logic is identical to the original, so the intervals it returns are unchanged.
        val coord = scratchCoord.get()!!

        // Spatial grid over the TRACK's segments, built ONCE per track. It makes the per-sample
        // nearest-perpendicular query near-O(1) (scan a 3×3 cell neighbourhood) instead of
        // O(trackPoints) (full linear scan in trackPath.nearestPerpDistM). The grid is proven by
        // PointGridDiffTest to agree with the brute-force full scan on the coverage decision
        // `(< tol)` for every sample and on the distance value itself whenever `< tol`, so the
        // covered-run/interval folding below is unchanged.
        //
        // CLIP TO THE ROUTE AREA: a recorded track that crosses the route then continues for tens of
        // km would otherwise build a grid over its WHOLE bbox → O(trackBBox / cell²) memory → possible
        // OutOfMemoryError. We only ever query the grid at ROUTE samples, so any track segment whose
        // bbox lies outside the route bbox fattened by `tol` can never be within `tol` of any route
        // sample → dropping it is COVERAGE-IDENTICAL. Clip the grid to that fattened route bbox so its
        // memory is bounded to the route area, not the track span.
        val routeBBox = BBox.around(route.points)
        val grid = if (routeBBox != null) {
            // Fatten by `tol` (plus a tiny epsilon) in degrees. Latitude: tol / metresPerDegLat.
            // Longitude: tol / (metresPerDegLng at the route's mid latitude) — divide by cos(lat),
            // guarding the cos→0 pole case. The margin is a SUPERSET (we over-include), so coverage
            // stays identical; it only ever keeps a few extra harmless boundary segments.
            val padLat = tol / 111_320.0
            val midLatRad = Math.toRadians((routeBBox.minLat + routeBBox.maxLat) / 2.0)
            val cosLat = kotlin.math.cos(midLatRad).coerceAtLeast(1e-6)
            val padLng = tol / (111_320.0 * cosLat)
            val clip = BBox(
                minLat = routeBBox.minLat - padLat,
                maxLat = routeBBox.maxLat + padLat,
                minLng = routeBBox.minLng - padLng,
                maxLng = routeBBox.maxLng + padLng,
            )
            PointGrid.forPathClippedTo(trackPath, tol, clip)
        } else {
            PointGrid(trackPath, tol)
        }

        val runs = ArrayList<Pair<Double, Double>>()
        var runStart = -1.0
        var prevDist = 0.0
        var d = 0.0
        // Walk samples at `step`, then one final sample exactly at `total` (so a covered run reaching
        // the end closes cleanly — same as the original explicit end sample).
        while (true) {
            val dist = if (d < total) d else total
            route.pointAtDistanceInto(dist, coord)
            val cov = grid.nearestPerpDistM(coord[0], coord[1]) < tol
            if (cov) {
                if (runStart < 0.0) runStart = dist
            } else if (runStart >= 0.0) {
                runs += runStart to prevDist
                runStart = -1.0
            }
            prevDist = dist
            if (dist >= total) break
            d += step
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
     * Tiny epsilon (m) for the strict route-forward test in [extractTrackSlice]'s chain walk. A kept
     * point must advance in route-distance by more than this; the epsilon only absorbs float
     * round-off so two genuinely distinct, advancing points are not rejected as "equal". It must stay
     * far below the route sampling/decimation spacing so it never lets a stalled (frozen) or
     * backward lap re-entry through.
     */
    private const val routeAdvanceEpsilonM = 1e-3

    /**
     * Backstop multiplier for the odometer-vs-route-span sanity check in [extractTrackSlice]. After
     * building the chosen chain we reject it (treat the interval as no-segment) when the ghost's
     * odometer span exceeds `(routeSpan + 2 * toleranceM) * routeSpanInflationLimit`. This guarantees
     * a smeared/inflated ghost (e.g. a multi-lap chain whose odometer covers N laps over a one-lap
     * route span) is never emitted, regardless of how it slipped past the per-point guards. The
     * `2 * toleranceM` allowance covers the projection's perpendicular tolerance at both ends, and
     * 1.25 leaves headroom for legitimate odometer-vs-route slack (a track is sampled along the road,
     * which is slightly longer than the straight route projection) without admitting a full extra lap.
     */
    private const val routeSpanInflationLimit = 1.25

    /**
     * Extracts ONE clean monotonic pass of the track over `[interval]` of the route by STITCHING the
     * monotonic subsequence of in-interval track points across the whole interval.
     *
     * The original (pre-②) implementation projected every track point by GLOBAL nearest and bucketed
     * by `[startM, endM]`. On an out-and-back route the outbound track points were geometrically
     * valid on BOTH the outbound and return vertex ranges, so they smeared across both intervals: the
     * per-interval slice became non-monotonic in route-distance, the dedup dropped points, and the
     * ghost shrank (frozen tail re-emerged).
     *
     * The out-and-back fix then walked the track in recorded order and kept only the LONGEST
     * contiguous run of in-interval, monotonic points. That cured the smear but OVER-restricted: on a
     * normal SINGLE-PASS overlap split into two runs by ONE disruptive point (GPS spike, roundabout,
     * brief off-route blip) — where `mergeGapM` bridged the coverage gap so the interval stays one
     * wide span — it kept only the longer half, so `ghost.totalDistanceM` ≈ half the span and the
     * frozen tail returned for the remainder (F1).
     *
     * The current implementation STITCHES the monotonic subsequence of in-interval points. Starting
     * from a seed point it walks forward; a point is KEPT (appended to the chain) when:
     *  - its forward-biased projection's `perpDistM < toleranceM` (F2 — the off-route guard; a spike
     *    that jumps off the road is rejected here),
     *  - its route-distance lands inside `[startM, endM]`,
     *  - its track `distanceM` is STRICTLY greater than the last kept point's track distance
     *    (monotonic in odometer — a real ride never rewinds), AND
     *  - its route-distance does not run BACKWARD past the previous kept point (monotonic in route,
     *    the out-and-back segregator).
     * A point that fails any test is SKIPPED, but the walk CONTINUES (it is NOT a run boundary) and a
     * skipped point does NOT advance the seed, so a single mid-segment blip no longer truncates the
     * ghost — the chain stitches straight across it.
     *
     * Because an out-and-back route doubles back over the SAME road, a single seed chosen greedily in
     * recorded order can latch onto the WRONG pass for an interval (e.g. an outbound point is
     * geometrically valid on the return interval too, yet yields a degenerate chain). So we build a
     * candidate chain from EACH possible seed point and keep the one covering the most track odometer.
     * The track-point count is small, so the O(n²) walk is cheap.
     *
     * Out-and-back segregation is preserved: on the shared road the return pass is geometrically valid
     * (perpDist≈0) on THIS interval's outbound vertices, so the windowed projection
     * ([PolylinePath.nearestProjectionNear]) and the perpDist/interval tests alone cannot reject it.
     * But its route-distance runs BACKWARD relative to the previous kept point, so the route-monotonic
     * test skips it; a skip does not advance the seed, so an outbound chain never absorbs the return
     * pass (and vice-versa). The two passes therefore form separate candidate chains, and the chain
     * matching THIS interval's direction wins on odometer span.
     *
     * Reverse detection is preserved: if the kept subsequence descends in track distance overall it
     * is reversed so the slice ascends before the ghost is built. Returns null when fewer than two
     * usable (strictly-increasing-distance) points remain.
     */
    private fun extractTrackSlice(
        route: PolylinePath,
        trackPoints: List<TrackPoint>,
        interval: Pair<Double, Double>,
        toleranceM: Double,
    ): List<TrackPoint>? {
        val (startM, endM) = interval

        // Seed projection for a chain's first point: constrained to the interval so an ambiguous
        // shared point (valid on both passes of an out-and-back route) is forced onto THIS interval's
        // pass rather than the global nearest, which may belong to the other pass.
        fun seedProjection(ll: LatLng): Projection =
            route.nearestProjectionNear(
                ll,
                aroundDistanceM = startM,
                backWindowM = 0.0,
                fwdWindowM = endM - startM,
            )

        // Builds the stitched chain that starts at [seedIndex] (skipping blips, never terminating on
        // one). Returns the kept (point, routeM) pairs; empty if the seed itself is not in-interval.
        fun buildChain(seedIndex: Int): List<Pair<TrackPoint, Double>> {
            val kept = ArrayList<Pair<TrackPoint, Double>>()
            var prevRouteM = Double.NaN
            for (i in seedIndex until trackPoints.size) {
                val tp = trackPoints[i]
                val ll = LatLng(tp.lat, tp.lng)
                val proj = if (kept.isEmpty()) {
                    seedProjection(ll)
                } else {
                    route.nearestProjectionNear(
                        ll,
                        aroundDistanceM = prevRouteM,
                        backWindowM = sliceBackWindowM,
                        fwdWindowM = sliceFwdWindowM,
                    )
                }
                val routeM = proj.distanceAlongM
                val onRoute = proj.perpDistM < toleranceM
                val insideInterval = routeM in startM..endM
                val monotonicOdometer = kept.isEmpty() || tp.distanceM > kept.last().first.distanceM
                // Strict route-forward progress: a kept point must advance in route-distance past the
                // previous kept point (modulo a tiny float epsilon). This is the multi-lap
                // same-direction segregator (BUG 1): when the rider re-enters the SAME stretch in the
                // SAME direction, the windowed projection either snaps the lap-2 point back to the
                // stretch start (routeM jumps backward) or freezes it at the window head (routeM
                // stalls) — both fail strict-advance, so the second lap never appends with a frozen
                // route position. A single-pass mid-segment blip still advances in routeM afterwards,
                // so it is kept; the out-and-back return pass runs backward in routeM, so it is
                // rejected here too (preserving the earlier out-and-back fix).
                val monotonicRoute = kept.isEmpty() || routeM > prevRouteM + routeAdvanceEpsilonM
                // Time-forward progress (BUG 2): a kept point must not move time backward. A recorded
                // track with a backward time glitch (odometer up, timeS down) would otherwise flow
                // into GhostCurve and throw "decreasing time", crashing match() at route load. Guard
                // it here so the emitted slice is monotonic in BOTH distance and time.
                val monotonicTime = kept.isEmpty() || tp.timeS >= kept.last().first.timeS
                if (onRoute && insideInterval && monotonicOdometer && monotonicRoute && monotonicTime) {
                    kept.add(tp to routeM)
                    prevRouteM = routeM
                }
                // else: skip but continue — a single blip does not break the chain.
            }
            return kept
        }

        // Build a candidate chain from every possible seed and keep the one that COVERS the most of
        // the interval — measured by route-distance span (last routeM − first routeM), with kept
        // point-count as a tiebreaker. A greedy single seed can latch onto the wrong pass of an
        // out-and-back route and produce a degenerate 2-point chain whose ODOMETER span is huge but
        // whose ROUTE coverage is tiny; ranking by route-span (not odometer-span) rejects it.
        // A seed can only produce a non-empty chain if its FIRST point is kept, which in buildChain
        // requires seedProjection(seed).perpDist < tol AND its routeM in [startM, endM] (the
        // kept.isEmpty() branch of the per-point test, with the monotonic guards trivially true for
        // the first point). Any seed failing this yields an empty chain that the size<2 filter below
        // already discards. So the set of seeds that CAN contribute is exactly the points satisfying
        // that predicate — call it "can-start".
        //
        // CAN_START (the proven-identical production bound) keeps every can-start point as a seed;
        // CORRIDOR_ENTRY (deferred, test-only) keeps only the can-start points whose predecessor was
        // not can-start.
        fun canStart(i: Int): Boolean {
            val tp = trackPoints[i]
            val proj = seedProjection(LatLng(tp.lat, tp.lng))
            return proj.perpDistM < toleranceM && proj.distanceAlongM in startM..endM
        }

        val seedIndices: Iterable<Int> = when (seedStrategy) {
            SeedStrategy.ALL_SEEDS -> trackPoints.indices
            SeedStrategy.CAN_START -> trackPoints.indices.filter { canStart(it) }
            SeedStrategy.CORRIDOR_ENTRY -> {
                val seeds = ArrayList<Int>()
                var prevCanStart = false
                for (i in trackPoints.indices) {
                    val cs = canStart(i)
                    if (cs && !prevCanStart) seeds += i
                    prevCanStart = cs
                }
                seeds
            }
        }

        var best: List<Pair<TrackPoint, Double>> = emptyList()
        var bestRouteSpan = -1.0
        for (seed in seedIndices) {
            val chain = buildChain(seed)
            if (chain.size < 2) continue
            val routeSpan = chain.last().second - chain.first().second
            if (routeSpan > bestRouteSpan ||
                (routeSpan == bestRouteSpan && chain.size > best.size)
            ) {
                bestRouteSpan = routeSpan
                best = chain
            }
        }

        if (best.size < 2) return null

        val inside = best.map { it.first }

        // Reverse detection: if track distance decreases as route distance increases, the track
        // was ridden opposite to the route -> reverse so the slice ascends in track distance.
        val oriented = if (inside.last().distanceM < inside.first().distanceM) inside.reversed() else inside

        // GhostCurve requires strictly increasing distance: collapse equal consecutive distances.
        val deduped = ArrayList<TrackPoint>(oriented.size)
        for (p in oriented) {
            if (deduped.isEmpty() || p.distanceM > deduped.last().distanceM) deduped += p
        }
        if (deduped.size < 2) return null

        // Backstop validation (BUG 1): reject any chain whose ODOMETER span inflated well past the
        // route span — a smeared/multi-lap chain that slipped past the per-point guards. The engine
        // assumes ghost.totalDistanceM ≈ routeEndM − routeStartM; emitting an inflated ghost breaks
        // gap-distance and ghost-progress, so we drop the interval (no segment) instead.
        val odometerSpanM = deduped.last().distanceM - deduped.first().distanceM
        val routeSpanM = endM - startM
        if (odometerSpanM > (routeSpanM + 2.0 * toleranceM) * routeSpanInflationLimit) return null

        return deduped
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
     * Reusable scratch for [pointAtDistanceInto]'s `[lat, lng]` output, so the per-sample coverage
     * scan never allocates a [LatLng]. ThreadLocal because `match` may be called concurrently (each
     * thread gets its own 2-element buffer); the matcher itself is otherwise stateless.
     */
    private val scratchCoord = ThreadLocal.withInitial { DoubleArray(2) }

    /**
     * The lat/lng on [this] route at cumulative distance [distM], by linear interpolation between
     * the two enclosing vertices (clamped to the endpoints when out of range), written into the
     * caller-supplied `out` (`out[0] = lat`, `out[1] = lng`). Allocation-free so the per-sample
     * coverage scan in [coveredRunsToIntervals] never allocates a [LatLng] per route sample. The
     * arithmetic (and the binary-search vertex lookup) is the original `pointAtDistance` logic that
     * [PointAtDistanceDiffTest] pins against the brute-force reference.
     */
    private fun PolylinePath.pointAtDistanceInto(distM: Double, out: DoubleArray) {
        if (distM <= 0.0) {
            val p = points.first(); out[0] = p.lat; out[1] = p.lng; return
        }
        if (distM >= totalM) {
            val p = points.last(); out[0] = p.lat; out[1] = p.lng; return
        }
        // Lower-bound binary search over the non-decreasing cumulativeM (see pointAtDistance).
        val hi = run {
            var lo = 0
            var high = cumulativeM.size // exclusive
            while (lo < high) {
                val mid = (lo + high) ushr 1
                if (cumulativeM[mid] >= distM) high = mid else lo = mid + 1
            }
            lo
        }
        val a = points[hi - 1]; val b = points[hi]
        val da = cumulativeM[hi - 1]; val db = cumulativeM[hi]
        val f = if (db == da) 0.0 else (distM - da) / (db - da)
        out[0] = a.lat + f * (b.lat - a.lat)
        out[1] = a.lng + f * (b.lng - a.lng)
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
