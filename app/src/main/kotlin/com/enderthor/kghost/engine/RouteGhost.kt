package com.enderthor.kghost.engine

/**
 * Builds ONE continuous whole-route ghost — the "ghost of the ride", not a ghost per segment.
 *
 * The rider's recorded history covers only some stretches of the loaded route (the matched
 * [LiveSegment]s). This stitches those stretches together with a constant [fillSpeedM] pace in the
 * gaps (before the first segment, between segments, and after the last) into a SINGLE monotonic
 * [GhostCurve] over route distance `[0, routeLengthM]`. The ghost then advances continuously along
 * the whole route by elapsed time and never freezes mid-route just because a recorded stretch ran
 * out — it only freezes when the ride is paused (its clock is `ELAPSED_TIME`, which the ride app
 * stops on pause).
 *
 * Each segment's own ghost curve is expressed in RECORDED-TRACK distance, which can differ slightly
 * from the route distance of that stretch (GPS noise / a marginally different line). We scale each
 * segment's distance axis to fit its route span exactly, so the composite is strictly monotonic in
 * route distance and every segment ends precisely at its route end at the right cumulative time.
 *
 * Pure and deterministic — unit tested directly.
 */
object RouteGhost {

    /**
     * Maximum plausible cyclist speed (m/s) that a recorded segment may imply. Any segment whose
     * average speed (routeSpan / totalTime) exceeds this is treated as corrupt data (GPS dropout,
     * timestamp glitch) and replaced by a fill-pace bridge — same as if no recording existed for
     * that stretch. 30 m/s ≈ 108 km/h: reachable only on the steepest descents; a segment at higher
     * average implies the GPS recorded a huge jump, not real movement.
     */
    private const val MAX_SEGMENT_SPEED_MS = 30.0

    /**
     * @param routeLengthM  Total route length in metres ([PolylinePath.totalM]).
     * @param segments      Matched recorded stretches (any order; overlaps are dropped defensively).
     * @param fillSpeedM    Pace (m/s, > 0) used to bridge the gaps between/around segments. Typically
     *                      the Ghost Pace target, falling back to the segments' own average.
     * @return A whole-route [GhostCurve], or null if a usable curve can't be built (degenerate route,
     *         or a gap exists but [fillSpeedM] is non-positive so it can't be bridged).
     */
    fun build(routeLengthM: Double, segments: List<LiveSegment>, fillSpeedM: Double): GhostCurve? {
        if (!routeLengthM.isFinite() || routeLengthM <= 0.0) return null
        val canFill = fillSpeedM.isFinite() && fillSpeedM > 0.0

        // Order by route start and clamp into the route; the matcher already dedups overlaps but we
        // guard anyway so a stray overlap can't break monotonicity.
        val segs = segments
            .filter { it.routeEndM > it.routeStartM }
            .sortedBy { it.routeStartM }

        val samples = ArrayList<GhostSample>()
        samples.add(GhostSample(0.0, 0.0))
        var cursorM = 0.0
        var cumTimeS = 0.0

        for (seg in segs) {
            val startM = seg.routeStartM.coerceIn(cursorM, routeLengthM)
            val endM = seg.routeEndM.coerceIn(startM, routeLengthM)
            if (endM <= cursorM) continue // fully behind the cursor (overlap) → skip

            // Gap before the segment, bridged at the fill pace.
            if (startM > cursorM) {
                if (!canFill) return null
                cumTimeS += (startM - cursorM) / fillSpeedM
                addSample(samples, startM, cumTimeS)
                cursorM = startM
            }

            val routeSpan = endM - cursorM
            if (routeSpan <= 0.0) continue
            val trackSpan = seg.ghost.totalDistanceM
            val scale = if (trackSpan > 0.0) routeSpan / trackSpan else 0.0

            // Sanity-check the implied average speed for this segment. A GPS dropout or timestamp
            // glitch can make the recorded total time for a segment implausibly short (e.g. 6 s for
            // 1425 m = 237 m/s), causing the ghost to "fly" through that section and produce a
            // giant, nonsensical gap spike. Treat such segments as fill-pace stretches instead.
            val segTimeS = seg.ghost.totalTimeS
            val impliedSpeedMs = if (segTimeS > 0.0) routeSpan / segTimeS else Double.MAX_VALUE
            if (impliedSpeedMs > MAX_SEGMENT_SPEED_MS) {
                // Use fill pace for this stretch (same as if there were no recorded data here).
                if (!canFill) return null
                cumTimeS += routeSpan / fillSpeedM
                addSample(samples, endM, cumTimeS)
                cursorM = endM
                continue
            }

            // Emit each internal ghost sample, scaled onto the route span. Skip the first (it is the
            // segment-relative origin and coincides with the cursor sample already added).
            val gs = seg.ghost.samples
            for (i in 1 until gs.size) {
                val rd = (cursorM + gs[i].distanceM * scale).coerceAtMost(endM)
                addSample(samples, rd, cumTimeS + gs[i].timeS)
            }
            cumTimeS += seg.ghost.totalTimeS
            cursorM = endM
        }

        // Tail gap to the route end.
        if (cursorM < routeLengthM) {
            if (!canFill) {
                // Can't bridge the tail; the curve ends at the last segment. Still valid if we have
                // at least two samples (a segment was emitted).
            } else {
                cumTimeS += (routeLengthM - cursorM) / fillSpeedM
                addSample(samples, routeLengthM, cumTimeS)
            }
        }

        if (samples.size < 2) return null
        return GhostCurve(samples)
    }

    /**
     * The segments' combined average speed (total recorded track distance / total recorded time),
     * or null when there are no segments or zero total time. Used as the gap-fill pace when no
     * Ghost Pace target is configured, so the ghost still flows at the rider's own typical pace.
     */
    fun averageSegmentSpeedM(segments: List<LiveSegment>): Double? {
        var dist = 0.0
        var time = 0.0
        for (s in segments) {
            dist += s.ghost.totalDistanceM
            time += s.ghost.totalTimeS
        }
        return if (time > 0.0 && dist > 0.0) dist / time else null
    }

    /** Appends a sample only when it strictly advances distance ([GhostCurve] requires that). */
    private fun addSample(samples: ArrayList<GhostSample>, distanceM: Double, timeS: Double) {
        if (!distanceM.isFinite() || !timeS.isFinite()) return
        if (distanceM > samples.last().distanceM) {
            samples.add(GhostSample(distanceM, timeS))
        }
    }
}
