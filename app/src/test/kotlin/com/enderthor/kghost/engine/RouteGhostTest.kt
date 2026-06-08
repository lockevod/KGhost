package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGhostTest {

    private fun ghost(vararg pts: Pair<Double, Double>) =
        GhostCurve(pts.map { GhostSample(it.first, it.second) })

    private fun seg(startM: Double, endM: Double, ghost: GhostCurve) =
        LiveSegment(
            routeStartM = startM,
            routeEndM = endM,
            ghost = ghost,
            ghostLabel = "t",
        )

    @Test fun `single segment with VP-filled lead-in and tail`() {
        // Route 0..300. Segment [100,200] recorded at 5 m/s (100 m in 20 s). Fill 10 m/s.
        val c = RouteGhost.build(
            routeLengthM = 300.0,
            segments = listOf(seg(100.0, 200.0, ghost(0.0 to 0.0, 100.0 to 20.0))),
            fillSpeedM = 10.0,
        )
        assertNotNull(c)
        c!!
        // Lead-in 0..100 @10 m/s = 10 s; segment 100..200 = 20 s (ends at t=30); tail 200..300 = 10 s.
        assertEquals(0.0, c.distanceAt(0.0), 1e-6)
        assertEquals(100.0, c.distanceAt(10.0), 1e-6)
        assertEquals(200.0, c.distanceAt(30.0), 1e-6)
        assertEquals(300.0, c.distanceAt(40.0), 1e-6)
        // Mid-segment time lookup: halfway (150 m) is 10 s of fill + 10 s into the segment = 20 s.
        assertEquals(20.0, c.timeAt(150.0), 1e-6)
    }

    @Test fun `segment track distance is scaled to its route span`() {
        // Recorded track span 200 m but the route stretch is only 100 m → scale 0.5, ends at 200 m.
        val c = RouteGhost.build(
            routeLengthM = 200.0,
            segments = listOf(seg(100.0, 200.0, ghost(0.0 to 0.0, 200.0 to 40.0))),
            fillSpeedM = 10.0,
        )!!
        // Lead-in 0..100 @10 = 10 s, then the segment ends exactly at route 200 m at t=50.
        assertEquals(200.0, c.totalDistanceM, 1e-6)
        assertEquals(100.0, c.distanceAt(10.0), 1e-6)
        assertEquals(200.0, c.distanceAt(50.0), 1e-6)
    }

    @Test fun `adjacent segments flow without a gap`() {
        val c = RouteGhost.build(
            routeLengthM = 200.0,
            segments = listOf(
                seg(0.0, 100.0, ghost(0.0 to 0.0, 100.0 to 10.0)),
                seg(100.0, 200.0, ghost(0.0 to 0.0, 100.0 to 30.0)),
            ),
            fillSpeedM = 10.0,
        )!!
        assertEquals(0.0, c.distanceAt(0.0), 1e-6)
        assertEquals(100.0, c.distanceAt(10.0), 1e-6) // first segment: 10 s
        assertEquals(200.0, c.distanceAt(40.0), 1e-6) // + second segment: 30 s
    }

    @Test fun `out-of-order and overlapping segments are sorted and de-overlapped`() {
        val c = RouteGhost.build(
            routeLengthM = 300.0,
            segments = listOf(
                seg(200.0, 300.0, ghost(0.0 to 0.0, 100.0 to 10.0)),
                seg(0.0, 100.0, ghost(0.0 to 0.0, 100.0 to 10.0)),
                seg(50.0, 150.0, ghost(0.0 to 0.0, 100.0 to 10.0)), // overlaps the second → clamped
            ),
            fillSpeedM = 10.0,
        )!!
        assertTrue(c.totalDistanceM == 300.0)
        // Monotonic, finite curve over the whole route.
        assertEquals(0.0, c.distanceAt(0.0), 1e-6)
        assertEquals(300.0, c.distanceAt(c.totalTimeS), 1e-6)
    }

    @Test fun `a gap with no fill speed cannot be bridged`() {
        assertNull(
            RouteGhost.build(
                routeLengthM = 300.0,
                segments = listOf(seg(100.0, 200.0, ghost(0.0 to 0.0, 100.0 to 20.0))),
                fillSpeedM = 0.0,
            ),
        )
    }

    @Test fun `no segments and no fill yields null`() {
        assertNull(RouteGhost.build(300.0, emptyList(), 0.0))
    }

    @Test fun `degenerate route length yields null`() {
        assertNull(RouteGhost.build(0.0, listOf(seg(0.0, 100.0, ghost(0.0 to 0.0, 100.0 to 10.0))), 10.0))
    }

    @Test fun `average segment speed is total distance over total time`() {
        val avg = RouteGhost.averageSegmentSpeedM(
            listOf(
                seg(0.0, 100.0, ghost(0.0 to 0.0, 100.0 to 10.0)), // 10 m/s
                seg(100.0, 200.0, ghost(0.0 to 0.0, 100.0 to 30.0)), // ~3.33 m/s
            ),
        )
        assertNotNull(avg)
        assertEquals(200.0 / 40.0, avg!!, 1e-9)
    }

    @Test fun `average segment speed is null with no segments`() {
        assertNull(RouteGhost.averageSegmentSpeedM(emptyList()))
    }
}
