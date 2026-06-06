package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.engine.GhostPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMatcherTest {
    // Route: 2 km straight east along the equator (0,0)→(0,~0.018).
    private val route = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.018)))
    private fun pt(lng: Double, distanceM: Double, t: Double) =
        com.enderthor.kvpartner.geo.TrackPoint(0.0, lng, distanceM, t)

    private val params = SegmentMatcher.Params(toleranceM = 25.0, minSegmentM = 300.0, mergeGapM = 80.0)

    @Test fun `finds the overlapping middle stretch as one segment`() {
        // Track rides exactly along the route's middle ~1 km (lng 0.004..0.013), at 5 m/s.
        val track = com.enderthor.kvpartner.geo.RecordedTrack(
            id = "t1", startedAtEpoch = 1_000L,
            points = (0..18).map { i ->
                val lng = 0.004 + i * 0.0005
                pt(lng, distanceM = i * 55.0, t = i * 11.0).toDto()
            },
        )
        val segs = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        assertEquals(1, segs.size)
        val s = segs.first()
        assertTrue(s.routeStartM > 300.0 && s.routeEndM < route.totalM)
        assertTrue(s.routeEndM - s.routeStartM >= params.minSegmentM)
        // Ghost curve is segment-relative and monotonic.
        assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
    }

    @Test fun `discards overlaps shorter than minSegmentM`() {
        val track = com.enderthor.kvpartner.geo.RecordedTrack(
            "short", 1_000L,
            (0..3).map { i -> pt(0.006 + i * 0.0003, i * 33.0, i * 6.0).toDto() }, // ~100 m overlap
        )
        assertTrue(SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params).isEmpty())
    }

    @Test fun `BEST picks the faster of two tracks over the same stretch`() {
        fun track(id: String, secPerStep: Double) = com.enderthor.kvpartner.geo.RecordedTrack(
            id, 1_000L, (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * secPerStep).toDto() })
        val slow = track("slow", 12.0); val fast = track("fast", 8.0)
        val segs = SegmentMatcher.match(route, listOf(slow, fast), GhostPick.BEST, params)
        assertEquals(1, segs.size)
        // The chosen ghost's total time over the segment equals the fast track's.
        assertTrue(segs.first().ghost.totalTimeS < 18 * 10.0)
    }

    /**
     * Regression for the union-span bug: two tracks covering partially-overlapping but DIFFERENT
     * route ranges (A ≈ [0,600 m], B ≈ [400,1000 m]) were previously grouped into a single segment
     * whose [routeStartM,routeEndM] was the greedy union [0,1000 m].  The chosen ghost (say B)
     * only covered ~600 m, so GhostCurve.timeAt clamped and the gap reading froze for the last
     * ~400 m of the union span.  After the fix the emitted segment's span must match the winner's
     * own ghost coverage (within 15 %).
     */
    @Test fun `ghost coverage matches declared segment span after overlapping-range resolution`() {
        // Route is 2 km east (lng 0..0.018). Each degree of longitude ≈ 111 320 m at equator,
        // so 0.018° ≈ 2004 m.  One sample step of 0.0005° ≈ 55.7 m along the route.

        // Track A: covers roughly route [0, ~600 m] — lng 0.000..0.0054 (10 steps × 55 m = 550 m track).
        // Made deliberately SLOWER so BEST will prefer track B.
        val trackA = com.enderthor.kvpartner.geo.RecordedTrack(
            id = "A", startedAtEpoch = 1_000L,
            points = (0..10).map { i ->
                pt(lng = i * 0.0005, distanceM = i * 55.0, t = i * 15.0).toDto()
            },
        )

        // Track B: covers roughly route [~400 m, ~1000 m] — lng 0.0036..0.0090
        // (shifted by ~7 steps = ~385 m; 11 steps × 55 m = 605 m track). Made FASTER.
        val trackB = com.enderthor.kvpartner.geo.RecordedTrack(
            id = "B", startedAtEpoch = 2_000L,
            points = (0..10).map { i ->
                pt(lng = 0.0036 + i * 0.0005, distanceM = i * 55.0, t = i * 10.0).toDto()
            },
        )

        val segs = SegmentMatcher.match(route, listOf(trackA, trackB), GhostPick.BEST, params)

        // There must be at least one segment (the two tracks overlap, so they form a group).
        assertTrue("Expected at least one segment, got ${segs.size}", segs.size >= 1)

        // Find the segment that the winner (B, faster) produced.  On the fixed code there is
        // exactly one segment whose span matches B's coverage.  On the old union-span code there
        // would be a single segment with routeEndM ≈ 1000 m but ghost.totalDistanceM ≈ 550 m —
        // a ~80 % mismatch that this assertion catches.
        val winning = segs.first()
        val spanM = winning.routeEndM - winning.routeStartM
        val ghostM = winning.ghost.totalDistanceM

        // Ghost coverage must be within 15 % of the declared segment span.
        val ratio = ghostM / spanM
        assertTrue(
            "ghost.totalDistanceM ($ghostM) should be within 15% of span ($spanM), ratio=$ratio",
            ratio in 0.85..1.15,
        )
    }
}
