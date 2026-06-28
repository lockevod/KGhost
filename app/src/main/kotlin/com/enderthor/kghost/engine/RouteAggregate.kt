package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable

/** Node grid step (m) for the per-route average. Same grid as SegmentMatcher's coverage scan. */
const val AGG_STEP_M = 25.0

/** EMA weight of the newest lap. 0.25 ≈ the recency-weighted mean of the last ~4 laps. */
const val AGG_ALPHA = 0.25

/**
 * Laps of plain running mean before the EMA takes over. A bare EMA from lap 1 leaves the FIRST lap
 * dominating for many rides (weight (1−α)ⁿ); the seed makes the early average a real average.
 */
const val AGG_SEED_LAPS = 4

/**
 * Floor speed (m/s) for the dwell clip: lap time accumulated below this speed between two nodes is
 * treated as a stop (café, photo, long light) and compressed out of the lap before it updates the
 * average. 0.5 m/s (1.8 km/h) is slower than pushing a bike uphill, so genuine riding — even a
 * steep hike-a-bike — is effectively never clipped.
 */
const val AGG_MIN_SPEED_MS = 0.5

/** Max plausible cyclist speed (m/s) between two nodes; a faster jump is a GPS spike and is rejected. */
const val AGG_MAX_SPEED_MS = 30.0

/** Persisted-aggregate schema version. Bump when the node/aggregate layout changes so old blobs are
 *  discarded (and re-seeded) instead of mis-read. */
const val AGG_SCHEMA_VERSION = 3

/** BEST reducer plausibility cap: a node's "best" may be at most this many times faster than its own
 *  recency-weighted average there. Clips a GPS-glitch segment (which as a raw min would spike the
 *  curve) while leaving a genuine strong day through — 2.0 (≤2× the average) covers a real hard effort
 *  vs an easy-ride-heavy average; a tighter 1.5 would clamp a legitimately fast lap. Self-anchored. */
const val BEST_MAX_SPEEDUP = 2.0

/** Laps on a node below which AVERAGE has no smoothed mean yet and FALLS BACK to the LAST reducer
 *  (the single recorded lap) rather than dropping the node to Ghost-Pace. At ≥ this many laps AVERAGE
 *  uses the EMA mean. This is NO LONGER a raceability gate — every pick races covered nodes at count≥1;
 *  it only selects EMA-vs-last for AVERAGE. (Gating AVERAGE at ≥2 left a route with only ONE full
 *  recorded lap almost entirely in Ghost-Pace mid-route, since that lone lap never lifts a node past 1.) */
const val AGG_MIN_LAPS = 2

/** Minimum raceable run length (m). A contiguous raceable run shorter than this is dropped (isolated
 *  noise); ported from SegmentMatcher.minSegmentM. The run set is per-pick (AVERAGE gates at
 *  AGG_MIN_LAPS, BEST/LAST at 1). */
const val AGG_MIN_SEG_M = 300.0

/** One grid node: the three reducers of the per-segment delta (node k-1 → k), plus the lap count. */
@Serializable
data class AggregateNode(
    /** EMA mean delta (s) — the AVERAGE reducer. Node 0 = 0. */
    val dtS: Double = 0.0,
    /** Laps that covered THIS node-pair (raceable gate + warmup). */
    val count: Int = 0,
    /** Smallest delta seen (s) — the BEST reducer (fastest traversal of this segment). */
    val minDtS: Double = 0.0,
    /** Delta from the most-recent FOLDED (good) traversal of this segment (s) — the LAST reducer. A
     *  rejected glitch lap does not overwrite it, so it is "last good", not literally "last ride". */
    val lastDtS: Double = 0.0,
)

/**
 * Per-route exponential-moving-average ghost: the recency-weighted mean of recent laps of one loaded
 * route, sampled on a fixed [stepM] grid over route distance `[0, routeLenM]`.
 *
 * Persisted independently of the recorded track files (see [com.enderthor.kghost.geo.AggregateStore]),
 * so it survives the track-library prune — that is the whole reason it is an O(1) running mean rather
 * than an average recomputed from the surviving tracks (which the prune caps at three per route).
 *
 * Pure data + pure transforms; no Android, no filesystem — unit-tested directly.
 */
@Serializable
data class PerRouteAggregate(
    val routeKey: String,
    val routeName: String,
    val routeLenM: Double,
    val stepM: Double = AGG_STEP_M,
    /** Schema version; 0 = legacy/absent (old blobs lack the field). Checked on load; stale → discard. */
    val schemaVersion: Int = 0,
    /** Node `k` (index) represents route distance `k * stepM`. Size = floor(routeLenM/stepM) + 1. */
    val nodes: List<AggregateNode>,
    /** Tracks the corridor seed folded; drives the lazy re-seed when history grows. 0 = legacy/absent. */
    val seededTrackCount: Int = 0,
) {
    /**
     * Raceable [LiveSegment]s for [pick], from this grid: contiguous runs of covered nodes (count≥1),
     * each built from the pick's reducer (EMA / min-clamped / last). Runs shorter than [minSegM] are
     * dropped. The caller bridges the gaps (count==0 / dropped) with the Ghost-Pace fill via
     * [RouteGhost.build]. ALL picks race at count≥1; AVERAGE uses the EMA mean on a node with
     * ≥ [AGG_MIN_LAPS] laps and FALLS BACK to the LAST reducer (the single recorded lap) below that —
     * so a route with only one full recording still races end-to-end instead of sitting in Ghost-Pace.
     */
    fun toLiveSegments(pick: GhostPick, minSegM: Double = AGG_MIN_SEG_M): List<LiveSegment> {
        val out = ArrayList<LiveSegment>()
        // node[k].count = laps covering the INCOMING segment (k-1→k); node 0 has none, so scan k≥1.
        // Every pick is raceable at count≥1 (AVERAGE's EMA-vs-last choice is per-node in nodeDelta).
        var k = 1
        while (k < nodes.size) {
            if (nodes[k].count < 1) { k++; continue }
            val firstK = k
            while (k + 1 < nodes.size && nodes[k + 1].count >= 1) k++
            buildRunSegment(firstK - 1, k, pick, minSegM)?.let { out.add(it) }
            k++
        }
        return out
    }

    /** The pick's per-segment delta at node [k]. BEST is clamped to a plausible multiple of the average
     *  there (no glitch spike, no node-to-node jump); LAST is the raw last-good reducer; AVERAGE is the
     *  EMA mean once a node has ≥ [AGG_MIN_LAPS] laps and falls back to LAST (the lone recorded lap)
     *  below that — at count==1 the two are identical anyway, so the fallback only ever helps coverage. */
    private fun nodeDelta(k: Int, pick: GhostPick): Double {
        val n = nodes[k]
        return when (pick) {
            GhostPick.AVERAGE -> if (n.count >= AGG_MIN_LAPS) n.dtS else n.lastDtS
            GhostPick.LAST -> n.lastDtS
            GhostPick.BEST -> maxOf(n.minDtS, n.dtS / BEST_MAX_SPEEDUP)
        }
    }

    private fun labelFor(pick: GhostPick, totalTimeS: Double): String = when (pick) {
        GhostPick.BEST -> "PR " + mmss(totalTimeS)
        GhostPick.LAST -> "Last " + mmss(totalTimeS)
        GhostPick.AVERAGE -> "AVG " + mmss(totalTimeS)
    }

    /** Builds a segment-relative [LiveSegment] for the node run `[from, to]`, or null if too short/degenerate. */
    private fun buildRunSegment(from: Int, to: Int, pick: GhostPick, minSegM: Double): LiveSegment? {
        if ((to - from) * stepM < minSegM) return null
        val startDist = from * stepM
        val samples = ArrayList<GhostSample>(to - from + 1)
        var cum = 0.0
        var prevTime = 0.0
        samples.add(GhostSample(0.0, 0.0))
        for (k in from + 1..to) {
            val dist = (k - from) * stepM
            cum += nodeDelta(k, pick)
            val t = cum.coerceAtLeast(prevTime) // keep the curve non-decreasing (GhostCurve requires it)
            samples.add(GhostSample(dist, t))
            prevTime = t
        }
        if (samples.size < 2) return null
        val totalDist = samples.last().distanceM
        val totalTime = samples.last().timeS
        if (totalTime <= 0.0 || totalDist / totalTime > AGG_MAX_SPEED_MS) return null // defensive
        val curve = GhostCurve(samples)
        return LiveSegment(startDist, to * stepM, curve, labelFor(pick, curve.totalTimeS))
    }
}

/**
 * Folds one lap's `(routeDist, riderTimeFromStartS)` series into [existing] (or a fresh grid) and
 * returns the updated aggregate. Pure.
 *
 * @param lap ascending-in-routeDist samples; [DoubleArray] `[routeDistM, riderTimeS]`. Only the
 *   DIFFERENCE in `riderTimeS` between consecutive covered nodes is folded (as per-segment deltas),
 *   so the model is origin-invariant: the time may be on any consistent clock and the lap may START
 *   ANYWHERE on the route (a live fair-start lap, or a history slice re-based to its own start via
 *   [seedAggregateFromLaps]). Time must be non-decreasing along the lap; absolute origin is irrelevant.
 */
fun updateAggregate(
    existing: PerRouteAggregate?,
    routeKey: String,
    routeName: String,
    routeLenM: Double,
    lap: List<DoubleArray>,
    alpha: Double = AGG_ALPHA,
    stepM: Double = AGG_STEP_M,
): PerRouteAggregate {
    val nodeCount = (Math.floor(routeLenM / stepM) + 1).toInt().coerceAtLeast(1)
    // Reuse the existing grid only when it matches this route's axis (same node count + step); a route
    // whose length crossed the key's 100 m rounding boundary gets a fresh grid rather than a bad blend.
    val base: Array<AggregateNode> =
        if (existing != null && existing.nodes.size == nodeCount && existing.stepM == stepM &&
            existing.schemaVersion == AGG_SCHEMA_VERSION
        ) {
            existing.nodes.toTypedArray()
        } else {
            Array(nodeCount) { AggregateNode() }
        }

    if (lap.size >= 2) {
        val first = lap.first()[0]
        val last = lap.last()[0]
        var seg = 0 // pointer into `lap` for the linear-interpolation walk (lap is ascending in dist)
        // Guard baseline = the lap's FIRST SAMPLE (not NaN): a stop between the route start and the
        // first covered node would otherwise enter that node unclipped and shift the WHOLE lap by the
        // stop duration. prevNodeTime is on the ADJUSTED (dwell-compressed) clock.
        var prevNodeTime = lap.first()[1]
        var prevNodeDist = lap.first()[0]
        var prevNodeIdx = -1 // grid index of the last COVERED node we advanced past (folded OR just
        // re-baselined); -1 = none yet. The fold gate below uses it to require a consecutive pair.
        // Seconds compressed out of this lap so far by the dwell clip. Subtracting it from every later
        // node keeps the lap's own moving pace intact while removing the stop.
        var clipS = 0.0
        for (k in 0 until nodeCount) {
            val d = k * stepM
            if (d < first || d > last) continue // node not covered by this (possibly partial) lap
            // Advance the segment pointer so lap[seg][0] <= d <= lap[seg+1][0].
            while (seg + 1 < lap.size && lap[seg + 1][0] < d) seg++
            val a = lap[seg]
            val b = if (seg + 1 < lap.size) lap[seg + 1] else a
            val span = b[0] - a[0]
            val f = if (span > 0.0) (d - a[0]) / span else 0.0
            val tLap = a[1] + f * (b[1] - a[1])
            // Drop a non-finite interpolated time (a corrupt sample) so a NaN can never be persisted into
            // the EMA — a stored NaN would later make GhostCurve reject the whole curve at race time.
            if (!tLap.isFinite()) continue
            var tAdj = tLap - clipS
            val dt = tAdj - prevNodeTime
            // REAL distance gap (d − prevNodeDist) — several grid steps when nodes were skipped;
            // dividing by a bare stepM would under-estimate the speed and let a multi-node spike through.
            val gap = d - prevNodeDist
            if (gap > 0.0) {
                // GPS-spike / monotonicity guard: a node whose time does not advance past the previous
                // GOOD baseline, or implies an impossible speed from it, is dropped (left unchanged) —
                // so a momentary jump can't poison the mean. prevNodeTime/Dist do NOT advance on a reject.
                if (dt <= 0.0 || gap / dt > AGG_MAX_SPEED_MS) continue
                // DWELL CLIP: time beyond what the floor speed allows over this gap is a stop, not
                // riding — compress it out so one café stop doesn't make the average ghost crawl at that
                // spot forever. Only the excess dwell is removed (from this and every later node, via
                // clipS); the lap's own moving pace is untouched.
                val maxDt = gap / AGG_MIN_SPEED_MS
                if (dt > maxDt) {
                    clipS += dt - maxDt
                    tAdj = prevNodeTime + maxDt
                }
            } else if (dt < 0.0) {
                continue // node at the baseline's own distance but time went backwards: corrupt sample
            }
            // Fold the SINGLE-step delta only when the previous COVERED node EXISTS and is exactly k-1
            // (it may have only re-baselined, not folded — either way it gives us the node-(k-1) time);
            // otherwise this is the first covered node or sits past a gap → just re-baseline (no valid
            // 1-step dt). The `prevNodeIdx >= 0` guard keeps node 0 (no incoming segment) at count 0
            // even for a lap that starts exactly at routeDist 0 (its degenerate dt would be 0 anyway).
            val segDt = tAdj - prevNodeTime
            if (prevNodeIdx >= 0 && prevNodeIdx == k - 1) {
                val node = base[k]
                // Plain running mean while seeding (first AGG_SEED_LAPS laps), then the EMA.
                val mean = when {
                    node.count == 0 -> segDt
                    node.count < AGG_SEED_LAPS -> (node.dtS * node.count + segDt) / (node.count + 1)
                    else -> alpha * segDt + (1.0 - alpha) * node.dtS
                }
                val newMin = if (node.count == 0) segDt else minOf(node.minDtS, segDt)
                base[k] = AggregateNode(dtS = mean, count = node.count + 1, minDtS = newMin, lastDtS = segDt)
            }
            prevNodeTime = tAdj
            prevNodeDist = d
            prevNodeIdx = k
        }
    }

    return PerRouteAggregate(
        routeKey = routeKey,
        routeName = routeName,
        routeLenM = routeLenM,
        stepM = stepM,
        schemaVersion = AGG_SCHEMA_VERSION,
        nodes = base.asList(),
    )
}

/**
 * Builds a fresh aggregate by folding [laps] (already in the desired EMA order — oldest first) via
 * [updateAggregate]. Used to SEED a route's average from recorded history at first match, so AVERAGE
 * races from ride 1. Each lap is a `(routeDistM, timeS)` series; origin-invariant, so a lap may start
 * anywhere on the route.
 */
fun seedAggregateFromLaps(
    routeKey: String,
    routeName: String,
    routeLenM: Double,
    laps: List<List<DoubleArray>>,
): PerRouteAggregate {
    var agg: PerRouteAggregate? = null
    for (lap in laps) agg = updateAggregate(agg, routeKey, routeName, routeLenM, lap)
    // Empty laps → a fresh, correctly-sized, stamped aggregate. Reuse updateAggregate (a <2-point lap
    // is a no-op fold) so the grid-size formula lives in exactly one place.
    return agg ?: updateAggregate(null, routeKey, routeName, routeLenM, emptyList())
}
