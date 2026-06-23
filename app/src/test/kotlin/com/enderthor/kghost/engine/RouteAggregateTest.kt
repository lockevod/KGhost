package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAggregateTest {

    private fun lap(vararg pts: Pair<Double, Double>): List<DoubleArray> =
        pts.map { doubleArrayOf(it.first, it.second) }

    // Route 0..100, step 25 → 5 nodes (0,25,50,75,100).
    private val key = "loop_100"

    @Test fun `empty aggregate seeds per-segment deltas from the lap, count 1`() {
        // 100 m in 20 s = 5 m/s → each 25 m segment takes 5 s → node deltas 0,5,5,5,5.
        val agg = updateAggregate(null, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 20.0))
        assertEquals(5, agg.nodes.size)
        val expected = doubleArrayOf(0.0, 5.0, 5.0, 5.0, 5.0)
        for (k in 1..4) {
            assertEquals(expected[k], agg.nodes[k].dtS, 1e-9)
            assertEquals(1, agg.nodes[k].count)
        }
        // Node 0 (the lap's first covered node, sitting at the start origin) folds a degenerate
        // zero-length delta against the phantom baseline; it carries dt=0 and never affects timing.
        assertEquals(0.0, agg.nodes[0].dtS, 1e-9)
    }

    @Test fun `identical second lap leaves deltas unchanged, count 2`() {
        val l = lap(0.0 to 0.0, 100.0 to 20.0)
        val first = updateAggregate(null, key, "Loop", 100.0, l)
        val second = updateAggregate(first, key, "Loop", 100.0, l)
        assertEquals(5.0, second.nodes[2].dtS, 1e-9)
        assertEquals(2, second.nodes[2].count)
    }

    @Test fun `slower second lap averages plainly while seeding`() {
        val fast = updateAggregate(null, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 20.0)) // node1 dt = 5s
        // 100 m in 40 s = 2.5 m/s → each segment 10 s → node1 dt = 10 s.
        val blended = updateAggregate(fast, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 40.0))
        // Seeding (count < AGG_SEED_LAPS): plain running mean = (5 + 10) / 2 = 7.5, NOT the EMA blend —
        // a bare EMA from lap 1 would leave the first lap dominating for many rides.
        assertEquals(7.5, blended.nodes[1].dtS, 1e-9)
        assertEquals(2, blended.nodes[1].count)
    }

    @Test fun `EMA takes over after the seed laps`() {
        // AGG_SEED_LAPS identical laps (node1 dt = 5 s) fill the seed window with mean 5.
        var agg: PerRouteAggregate? = null
        repeat(AGG_SEED_LAPS) {
            agg = updateAggregate(agg, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 20.0))
        }
        assertEquals(5.0, agg!!.nodes[1].dtS, 1e-9)
        assertEquals(AGG_SEED_LAPS, agg!!.nodes[1].count)
        // Lap AGG_SEED_LAPS+1 (node1 dt = 10 s) blends by the EMA: 0.25*10 + 0.75*5 = 6.25.
        val after = updateAggregate(agg, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 40.0))
        assertEquals(6.25, after.nodes[1].dtS, 1e-9)
    }

    @Test fun `dwell before the first covered node is clipped too`() {
        // Stop right after the start, before the 25 m node: samples (0,0),(10,5) then 600 s stop →
        // (25,610),(50,615),(100,625). Without the first-sample baseline, node1 would carry the whole
        // 605 s and shift every later node by the stop.
        val l = lap(0.0 to 0.0, 10.0 to 5.0, 25.0 to 610.0, 50.0 to 615.0, 100.0 to 625.0)
        val agg = updateAggregate(null, key, "Loop", 100.0, l)
        // Node1 (25 m): dt=610 over 25 m from the (0,0) baseline → floor allows 50 s → delta = 50.
        assertEquals(50.0, agg.nodes[1].dtS, 1e-9)
        // Later segments keep their riding pace: 5 s each.
        assertEquals(5.0, agg.nodes[2].dtS, 1e-9)
        assertEquals(5.0, agg.nodes[4].dtS, 1e-9)
    }

    @Test fun `spike rejection then dwell clip stay consistent`() {
        // Node2 is a spike (rejected); node3 then dwells. The guards must measure node3 against the
        // last ACCEPTED node (25 m), not the rejected one.
        val l = lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 5.1, 75.0 to 700.0, 100.0 to 705.0)
        val agg = updateAggregate(null, key, "Loop", 100.0, l)
        assertEquals(0, agg.nodes[2].count) // spike rejected
        // Node3 follows a gap (node2 rejected), so no consecutive 1-step delta is folded → count 0.
        assertEquals(0, agg.nodes[3].count)
        // Node4: consecutive to node3, keeps its own pace → delta 5 s.
        assertEquals(5.0, agg.nodes[4].dtS, 1e-9)
        assertEquals(1, agg.nodes[4].count)
    }

    @Test fun `long dwell is compressed out, segment deltas stay sane`() {
        // 5 m/s riding with a 600 s stop between 25 m and 50 m: raw times 0, 5, 610, 615, 620.
        val l = lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 610.0, 75.0 to 615.0, 100.0 to 620.0)
        val agg = updateAggregate(null, key, "Loop", 100.0, l)
        // Node1: 5 s. Node2: dt=605 s over 25 m → floor speed 0.5 m/s allows 50 s → delta = 50.
        assertEquals(5.0, agg.nodes[1].dtS, 1e-9)
        assertEquals(50.0, agg.nodes[2].dtS, 1e-9)
        // Later segments keep their own riding pace: 5 s each.
        assertEquals(5.0, agg.nodes[3].dtS, 1e-9)
        assertEquals(5.0, agg.nodes[4].dtS, 1e-9)
        for (k in 1..4) assertEquals(1, agg.nodes[k].count)
    }

    @Test fun `partial lap only bumps count on reached nodes`() {
        val full = updateAggregate(null, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 20.0))
        // Second lap covers only 0..50.
        val partial = updateAggregate(full, key, "Loop", 100.0, lap(0.0 to 0.0, 50.0 to 10.0))
        assertEquals(2, partial.nodes[2].count) // 50 m reached
        assertEquals(1, partial.nodes[3].count) // 75 m not reached → untouched
        assertEquals(1, partial.nodes[4].count) // 100 m not reached → untouched
    }

    @Test fun `spike node is rejected, neighbours intact`() {
        // node2 (50 m) jumps only 0.1 s from node1 → 250 m/s → rejected; node3 recovers (but follows a
        // gap, so node3 is not a consecutive folded pair).
        val l = lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 5.1, 75.0 to 15.0, 100.0 to 20.0)
        val agg = updateAggregate(null, key, "Loop", 100.0, l)
        assertEquals(0, agg.nodes[2].count) // spike rejected
        assertEquals(1, agg.nodes[1].count)
        assertEquals(0, agg.nodes[3].count) // follows the rejected node → re-baselined, no 1-step delta
        assertEquals(1, agg.nodes[4].count) // consecutive to node3 → folded
        assertEquals(5.0, agg.nodes[4].dtS, 1e-9)
    }

    @Test fun `length mismatch rebuilds a fresh grid`() {
        val short = updateAggregate(null, key, "Loop", 100.0, lap(0.0 to 0.0, 100.0 to 20.0)) // 5 nodes
        val long = updateAggregate(short, key, "Loop", 200.0, lap(0.0 to 0.0, 200.0 to 40.0)) // 9 nodes
        assertEquals(9, long.nodes.size)
        assertEquals(1, long.nodes[1].count) // fresh grid: only this lap counted
    }

    // Origin invariance: the same ride starting at routeDist 0 vs starting at 500 m yields identical
    // per-segment deltas for the stretch they share.
    @Test fun `per-segment deltas are independent of the lap's start offset`() {
        val key = "loop"
        // Ride A: 0→100 m, 5 s per 25 m. Ride B: same pace but its samples start at 500 m, t from 0.
        val a = updateAggregate(null, key, "Loop", 1000.0, lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 10.0, 75.0 to 15.0, 100.0 to 20.0))
        val b = updateAggregate(null, key, "Loop", 1000.0, lap(500.0 to 0.0, 525.0 to 5.0, 550.0 to 10.0, 575.0 to 15.0, 600.0 to 20.0))
        // node index = dist/25. A covers nodes 1..4 (pairs into them), B covers nodes 21..24. Each delta = 5 s.
        assertEquals(5.0, a.nodes[1].dtS, 1e-9)
        assertEquals(5.0, a.nodes[4].dtS, 1e-9)
        assertEquals(5.0, b.nodes[21].dtS, 1e-9)
        assertEquals(5.0, b.nodes[24].dtS, 1e-9)
        assertEquals(1, a.nodes[4].count)
        assertEquals(1, b.nodes[24].count)
    }

    // A node-pair needs >= AGG_MIN_LAPS laps before its node is raceable.
    @Test fun `a pair is raceable only after two laps`() {
        val key = "loop"
        val l = lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 10.0, 75.0 to 15.0, 100.0 to 20.0)
        val one = updateAggregate(null, key, "Loop", 1000.0, l)
        assertTrue(one.toLiveSegments().isEmpty())
        val two = updateAggregate(one, key, "Loop", 1000.0, l)
        val segs = two.toLiveSegments()
        assertTrue(segs.isNotEmpty())
        // Ghost regression: traversing nodes 1..4 (75 m of grid span) takes 4 * 5 s = 20 s.
        val s = segs.first()
        assertEquals(20.0, s.ghost.timeAt(s.routeEndM - s.routeStartM), 1e-6)
    }

    @Test fun `schemaVersion is stamped on the result`() {
        val agg = updateAggregate(null, "k", "K", 100.0, lap(0.0 to 0.0, 25.0 to 5.0, 50.0 to 10.0))
        assertEquals(AGG_SCHEMA_VERSION, agg.schemaVersion)
    }

    private fun aggOf(routeLenM: Double, vararg nodes: Pair<Double, Int>): PerRouteAggregate =
        PerRouteAggregate(
            routeKey = key, routeName = "Loop", routeLenM = routeLenM, stepM = AGG_STEP_M,
            schemaVersion = AGG_SCHEMA_VERSION,
            nodes = nodes.map { AggregateNode(dtS = it.first, count = it.second) },
        )

    @Test fun `toLiveSegments builds one segment per contiguous ge-2-lap run`() {
        // counts 2,2,2,1,1 over 0,25,50,75,100. The race run scans nodes with count >= 2; node 0 here
        // also carries count 2 so the run is [0,50], deltas 0,5,5 → segment time 10 s.
        val agg = aggOf(100.0, 0.0 to 2, 5.0 to 2, 5.0 to 2, 15.0 to 1, 20.0 to 1)
        val segs = agg.toLiveSegments()
        assertEquals(1, segs.size)
        assertEquals(0.0, segs[0].routeStartM, 1e-9)
        assertEquals(50.0, segs[0].routeEndM, 1e-9)
        assertEquals(10.0, segs[0].ghost.totalTimeS, 1e-9)
        assertTrue(segs[0].ghostLabel.startsWith("AVG"))
    }

    @Test fun `toLiveSegments empty while no run has 2 laps`() {
        val agg = aggOf(100.0, 0.0 to 1, 5.0 to 1, 5.0 to 1, 15.0 to 1, 20.0 to 1)
        assertTrue(agg.toLiveSegments().isEmpty())
    }

    @Test fun `toLiveSegments skips a lone ge-2 node (needs two)`() {
        val agg = aggOf(100.0, 0.0 to 2, 5.0 to 1, 5.0 to 1, 15.0 to 1, 20.0 to 1)
        assertTrue(agg.toLiveSegments().isEmpty())
    }

    @Test fun `toLiveSegments repairs a non-monotonic delta dip`() {
        // node2 delta is negative — its cumulative time would dip below node1's, so it must be clamped
        // so GhostCurve does not reject the curve.
        val agg = aggOf(100.0, 0.0 to 2, 10.0 to 2, -3.0 to 2, 13.0 to 2, 5.0 to 2)
        val segs = agg.toLiveSegments()
        assertEquals(1, segs.size)
        // cum: node1=10, node2=7 → clamped to 10, node3=20, node4=25 → strictly non-decreasing in time.
        val c = segs[0].ghost
        assertEquals(10.0, c.timeAt(25.0), 1e-9)
        assertEquals(10.0, c.timeAt(50.0), 1e-9)
    }
}
