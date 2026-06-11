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

    // ── overlay ──────────────────────────────────────────────────────────────

    @Test fun `overlay with no primary returns secondary sorted`() {
        val s1 = seg(500.0, 1000.0, ghost(0.0 to 0.0, 500.0 to 50.0))
        val s2 = seg(0.0, 400.0, ghost(0.0 to 0.0, 400.0 to 40.0))
        val out = RouteGhost.overlay(emptyList(), listOf(s1, s2))
        assertEquals(listOf(s2, s1), out)
    }

    @Test fun `overlay trims a partially-overlapped secondary instead of dropping it`() {
        // Primary (AVG) covers [0,500]; secondary (BEST) spans [450,5000] at a constant 10 m/s.
        // The non-overlapped [500,5000] must survive as a trimmed piece — 4.5 km of history.
        val avg = seg(0.0, 500.0, ghost(0.0 to 0.0, 500.0 to 100.0))
        val best = seg(450.0, 5000.0, ghost(0.0 to 0.0, 4550.0 to 455.0))
        val out = RouteGhost.overlay(listOf(avg), listOf(best))
        assertEquals(2, out.size)
        assertEquals(avg, out[0])
        val piece = out[1]
        assertEquals(500.0, piece.routeStartM, 1e-6)
        assertEquals(5000.0, piece.routeEndM, 1e-6)
        // The piece keeps the recorded pace: 4500 m at 10 m/s = 450 s, re-based to t=0.
        assertEquals(450.0, piece.ghost.totalTimeS, 1e-6)
        assertEquals(0.0, piece.ghost.samples.first().timeS, 1e-9)
    }

    @Test fun `overlay keeps the secondary tail when primary is contained inside it`() {
        // Secondary [0,2000] fully contains primary [800,1200] → two pieces: [0,800] and [1200,2000].
        val avg = seg(800.0, 1200.0, ghost(0.0 to 0.0, 400.0 to 80.0))
        val best = seg(0.0, 2000.0, ghost(0.0 to 0.0, 2000.0 to 200.0))
        val out = RouteGhost.overlay(listOf(avg), listOf(best))
        assertEquals(3, out.size)
        assertEquals(0.0, out[0].routeStartM, 1e-6)
        assertEquals(800.0, out[0].routeEndM, 1e-6)
        assertEquals(80.0, out[0].ghost.totalTimeS, 1e-6) // 800 m @ 10 m/s
        assertEquals(avg, out[1])
        assertEquals(1200.0, out[2].routeStartM, 1e-6)
        assertEquals(2000.0, out[2].routeEndM, 1e-6)
        assertEquals(80.0, out[2].ghost.totalTimeS, 1e-6) // 800 m @ 10 m/s
    }

    @Test fun `overlay drops sliver pieces`() {
        // Secondary [450,650]: the non-overlapped piece [500,650] is 150 m < the 200 m minimum.
        val avg = seg(0.0, 500.0, ghost(0.0 to 0.0, 500.0 to 100.0))
        val best = seg(450.0, 650.0, ghost(0.0 to 0.0, 200.0 to 20.0))
        val out = RouteGhost.overlay(listOf(avg), listOf(best))
        assertEquals(listOf(avg), out)
    }

    @Test fun `overlay leaves a non-overlapping secondary untouched`() {
        val avg = seg(0.0, 500.0, ghost(0.0 to 0.0, 500.0 to 100.0))
        val best = seg(600.0, 1600.0, ghost(0.0 to 0.0, 1000.0 to 100.0))
        val out = RouteGhost.overlay(listOf(avg), listOf(best))
        assertEquals(listOf(avg, best), out)
    }
}
