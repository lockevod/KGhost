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

            // Emit the INTERIOR ghost samples scaled onto the route span, then the segment end as an
            // exact, authoritative sample. Skip the first (the segment-relative origin coincides with
            // the cursor sample already added) and the last (replaced by the exact end sample) — the
            // old clamp-into-span approach could land a float-dust interior sample ON endM, which made
            // addSample silently drop the true end sample and left the boundary carrying an interior
            // time while cumTimeS had already advanced past it (a small time cliff at the join).
            val gs = seg.ghost.samples
            for (i in 1 until gs.size - 1) {
                val rd = cursorM + gs[i].distanceM * scale
                if (rd < endM) addSample(samples, rd, cumTimeS + gs[i].timeS)
            }
            addSample(samples, endM, cumTimeS + seg.ghost.totalTimeS)
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

    /**
     * Shortest [overlay] piece worth racing: a sliver between two primary segments races badly (the
     * gap jumps as the ghost crosses it in seconds) and is better left to the fill pace.
     */
    private const val MIN_OVERLAY_PIECE_M = 200.0

    /**
     * Overlays [primary] segments (they always win) on [secondary]: returns primary plus the pieces
     * of each secondary segment that do NOT overlap any primary segment, TRIMMED via their ghost
     * curves — so a secondary ride's history is never discarded whole just because a stretch of it
     * is covered by a primary segment (e.g. the AVERAGE aggregate covers [0,500 m] of a BEST ride
     * spanning [450,5000 m]: the rider keeps racing BEST on [500,5000 m]). Pieces shorter than
     * [MIN_OVERLAY_PIECE_M] are dropped. Output sorted by route start, as [build] expects.
     */
    fun overlay(primary: List<LiveSegment>, secondary: List<LiveSegment>): List<LiveSegment> {
        if (primary.isEmpty()) return secondary.sortedBy { it.routeStartM }
        val out = ArrayList(primary)
        val prim = primary.sortedBy { it.routeStartM }
        for (sec in secondary) {
            var cursor = sec.routeStartM
            for (p in prim) {
                if (p.routeEndM <= cursor) continue
                if (p.routeStartM >= sec.routeEndM) break
                if (p.routeStartM > cursor) trimSegment(sec, cursor, p.routeStartM)?.let(out::add)
                cursor = maxOf(cursor, p.routeEndM)
            }
            if (cursor < sec.routeEndM) trimSegment(sec, cursor, sec.routeEndM)?.let(out::add)
        }
        return out.sortedBy { it.routeStartM }
    }

    /**
     * Cuts [seg] down to route interval `[fromM, toM]`, re-basing its ghost curve to the cut (the
     * piece starts at distance 0 / time 0, interior samples keep the recorded pace). Returns null
     * when the piece is shorter than [MIN_OVERLAY_PIECE_M] or degenerate.
     */
    private fun trimSegment(seg: LiveSegment, fromM: Double, toM: Double): LiveSegment? {
        val a = fromM.coerceAtLeast(seg.routeStartM)
        val b = toM.coerceAtMost(seg.routeEndM)
        if (b - a < MIN_OVERLAY_PIECE_M) return null
        if (a <= seg.routeStartM && b >= seg.routeEndM) return seg
        val routeSpan = seg.routeEndM - seg.routeStartM
        if (routeSpan <= 0.0) return null
        // Map route metres → the curve's own (track) distance axis.
        val scale = seg.ghost.totalDistanceM / routeSpan
        val d0 = (a - seg.routeStartM) * scale
        val d1 = (b - seg.routeStartM) * scale
        val t0 = seg.ghost.timeAt(d0)
        val samples = ArrayList<GhostSample>()
        samples.add(GhostSample(0.0, 0.0))
        for (s in seg.ghost.samples) {
            if (s.distanceM <= d0 || s.distanceM >= d1) continue
            val t = (s.timeS - t0).coerceAtLeast(samples.last().timeS)
            addSample(samples, s.distanceM - d0, t)
        }
        addSample(samples, d1 - d0, (seg.ghost.timeAt(d1) - t0).coerceAtLeast(samples.last().timeS))
        if (samples.size < 2 || samples.last().timeS <= 0.0) return null
        val curve = GhostCurve(samples)
        // Re-stamp the label's time with the PIECE's time — keeping the original would show the FULL
        // ride's m:ss ("PR 7:35") on a piece that races only part of it. Labels are "<prefix> m:ss".
        val label = seg.ghostLabel.substringBeforeLast(' ') + " " + mmss(curve.totalTimeS)
        return LiveSegment(a, b, curve, label)
    }

    /** Appends a sample only when it strictly advances distance ([GhostCurve] requires that). */
    private fun addSample(samples: ArrayList<GhostSample>, distanceM: Double, timeS: Double) {
        if (!distanceM.isFinite() || !timeS.isFinite()) return
        if (distanceM > samples.last().distanceM) {
            samples.add(GhostSample(distanceM, timeS))
        }
    }
}
