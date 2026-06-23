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

/** A node needs at least this many contributing laps before AVERAGE will race it. */
const val AGG_MIN_LAPS = 2

/**
 * "Started at the route start" tolerance (m): the rider's route position at first movement (D0) must
 * be within this of 0 for the lap to update the aggregate. A lap that joined mid-route injects a
 * time-origin offset (it lacks the `[0, D0]` time, so it folds `t≈0` at node-D0), so it is skipped
 * rather than polluting the mean.
 *
 * Set to 300 m (was 50): in normal use the rider starts AT the route origin but with routine GPS /
 * start-area variation of ~100-300 m (observed D0 up to 257 m on a real ride), and 50 m rejected
 * almost every real lap → the aggregate never warmed up and AVERAGE was stuck on the BEST-warmup
 * ghost forever. 300 m covers that observed range and is the practical maximum that does not
 * meaningfully distort the CURRENT model: because the raced ghost uses node-time DIFFERENCES
 * ([buildRunSegment] subtracts the run's start-node time), a D0 offset is confined to the start
 * region (≤ D0) — which a rider starting that far in does not race anyway — and is further diluted by
 * the EMA (α=0.25) over a few laps; the bulk of the route's average is unaffected.
 *
 * The proper fix for a genuine mid-route / arbitrary-point start (re-anchoring a partial lap to the
 * existing aggregate's time at its first covered node, so there is NO offset at all) is a model
 * redesign deferred to a later release.
 */
const val AGG_START_TOL_M = 300.0

/** Max plausible cyclist speed (m/s) between two nodes; a faster jump is a GPS spike and is rejected. */
const val AGG_MAX_SPEED_MS = 30.0

/** Persisted-aggregate schema version. Bump when the node/aggregate layout changes so old blobs are
 *  discarded (and re-seeded) instead of mis-read. */
const val AGG_SCHEMA_VERSION = 1

/** One grid node of the per-route average: the EMA mean rider-time to reach it, and the lap count. */
@Serializable
data class AggregateNode(
    /** EMA mean rider-time to traverse the segment from node k-1 to this node k (s). Node 0 = 0. */
    val dtS: Double = 0.0,
    /** How many contributing laps covered THIS node-pair (warmup gate + partial-lap support). */
    val count: Int = 0,
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
) {
    /**
     * Turns the well-covered stretches of this average into raceable [LiveSegment]s — contiguous runs
     * of nodes with `count >= AGG_MIN_LAPS`. The caller feeds these through [RouteGhost.build], which
     * bridges the uncovered stretches (warmup / never-ridden) with the Ghost-Pace fill exactly as it
     * does for BEST/LAST. Returns an empty list while no stretch has ≥2 laps yet (so AVERAGE can fall
     * back to BEST during warmup).
     */
    fun toLiveSegments(): List<LiveSegment> {
        val out = ArrayList<LiveSegment>()
        var i = 0
        while (i < nodes.size) {
            if (nodes[i].count < AGG_MIN_LAPS) { i++; continue }
            var j = i
            while (j + 1 < nodes.size && nodes[j + 1].count >= AGG_MIN_LAPS) j++
            // Run [i, j] of node indices (inclusive). A segment needs at least two nodes.
            if (j > i) buildRunSegment(i, j)?.let { out.add(it) }
            i = j + 1
        }
        return out
    }

    /** Builds a segment-relative [LiveSegment] for the node run `[from, to]`, or null if degenerate. */
    private fun buildRunSegment(from: Int, to: Int): LiveSegment? {
        val startDist = from * stepM
        val samples = ArrayList<GhostSample>(to - from + 1)
        var cum = 0.0
        var prevTime = 0.0
        samples.add(GhostSample(0.0, 0.0))
        for (k in from + 1..to) {
            val dist = (k - from) * stepM
            cum += nodes[k].dtS
            // Independent per-segment EMAs could, rarely, produce a non-positive step; clamp so the curve
            // stays strictly non-decreasing in time (GhostCurve.init requires that).
            val t = cum.coerceAtLeast(prevTime)
            samples.add(GhostSample(dist, t))
            prevTime = t
        }
        if (samples.size < 2) return null
        // Reject a corrupt run that implies an impossible average speed (defensive; nodes are guarded
        // on update too). Let RouteGhost fill that stretch instead.
        val totalDist = samples.last().distanceM
        val totalTime = samples.last().timeS
        if (totalTime <= 0.0 || totalDist / totalTime > AGG_MAX_SPEED_MS) return null
        val curve = GhostCurve(samples)
        return LiveSegment(
            routeStartM = startDist,
            routeEndM = to * stepM,
            ghost = curve,
            ghostLabel = "AVG " + mmss(curve.totalTimeS),
        )
    }
}

/**
 * Folds one lap's `(routeDist, riderTimeFromStartS)` series into [existing] (or a fresh grid) and
 * returns the updated aggregate. Pure.
 *
 * @param lap ascending-in-routeDist samples; [DoubleArray] `[routeDistM, riderTimeS]`. `riderTimeS`
 *   is measured on the fair-start clock (time since the rider first rolled at the route start), so
 *   the average is consistent with the live race's "fair start".
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
        var prevNodeIdx = -1 // grid index of the last FOLDED node (-1 = none yet)
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
            // Fold the SINGLE-step delta only when the previous folded node is exactly k-1; otherwise
            // this is the first covered node or sits past a gap → just re-baseline (no valid 1-step dt).
            val segDt = tAdj - prevNodeTime
            if (prevNodeIdx == k - 1) {
                val node = base[k]
                // Plain running mean while seeding (first AGG_SEED_LAPS laps), then the EMA — see
                // [AGG_SEED_LAPS] for why a bare EMA from lap 1 is wrong.
                val mean = when {
                    node.count == 0 -> segDt
                    node.count < AGG_SEED_LAPS -> (node.dtS * node.count + segDt) / (node.count + 1)
                    else -> alpha * segDt + (1.0 - alpha) * node.dtS
                }
                base[k] = AggregateNode(dtS = mean, count = node.count + 1)
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
