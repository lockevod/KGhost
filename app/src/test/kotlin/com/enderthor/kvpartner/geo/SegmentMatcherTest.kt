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
}
