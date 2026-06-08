package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.PolylinePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals

class RouteProjectedProgressTest {
    private var now = 0L
    private val route = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.018))) // ~2 km east
    private fun p() = RouteProjectedProgress(route, toleranceM = 25.0, staleThresholdMs = 3000, clock = { now })

    @Test fun `projects an on-route point to distance-along and is on-route`() {
        val rp = p(); rp.onLocation(LatLng(0.0, 0.009)) // halfway
        assertEquals(route.totalM / 2.0, rp.progressM, route.totalM * 0.03)
        assertTrue(rp.onRoute)
    }

    @Test fun `flags off-route when perp distance exceeds tolerance`() {
        val rp = p(); rp.onLocation(LatLng(0.01, 0.009)) // ~1.1 km north
        assertFalse(rp.onRoute)
    }

    @Test fun `isFresh false once progress stops changing past the threshold`() {
        val rp = p()
        rp.onLocation(LatLng(0.0, 0.004)); now += 1000
        rp.onLocation(LatLng(0.0, 0.004)); now += 3000  // same projected distance, 4 s elapsed
        rp.onLocation(LatLng(0.0, 0.004))
        assertFalse(rp.isFresh)
    }

    /**
     * Out-and-back regression. Route goes A→B (east) then B→A (back west) along the SAME road, so
     * every longitude appears twice: once on the outbound vertex range and once on the return range.
     *
     * On the OLD global-nearest projection, when the rider is physically at a shared longitude on
     * the RETURN pass, [PolylinePath.nearestProjection] returns whichever pass has the smaller
     * perpendicular distance — GPS-equal here — so progressM flips back to the SMALLER outbound
     * distance instead of continuing past B. The windowed, forward-biased projection keeps progress
     * on the current pass: it must increase monotonically through the outbound pass and keep
     * increasing past B on the return, never snapping back to an outbound value.
     */
    @Test fun `out-and-back progress is monotonic and does not snap back on the return pass`() {
        // A=(0,0) -> B=(0,0.018) east (~2 km) -> back to A. Total ~4 km.
        // Densely vertexed (every ~0.0006° ≈ 67 m) like a real decimated route, so the per-segment
        // window of nearestProjectionNear can genuinely segregate the outbound and return passes.
        val outLngs = generateSequence(0.0) { it + 0.0006 }.takeWhile { it < 0.018 }.toList() + 0.018
        val backLngs = outLngs.reversed().drop(1)
        val outAndBack = PolylinePath((outLngs + backLngs).map { LatLng(0.0, it) })
        val bM = Polyline.haversineM(LatLng(0.0, 0.0), LatLng(0.0, 0.018)) // distance to the turnaround
        val rp = RouteProjectedProgress(outAndBack, toleranceM = 25.0, staleThresholdMs = 3000, clock = { now })

        // Longitudes the rider passes, in fixes spaced ~155 m (0.0014°) apart so each step fits the
        // forward window (200 m): outbound 0 -> 0.018, then return 0.018 -> 0.
        val outboundLng = generateSequence(0.0) { it + 0.0014 }.takeWhile { it <= 0.018 }.toList() + 0.018
        val returnLng = generateSequence(0.018 - 0.0014) { it - 0.0014 }.takeWhile { it >= 0.0 }.toList()

        var prev = -1.0
        for (lng in outboundLng) {
            rp.onLocation(LatLng(0.0, lng)); now += 1000
            assertTrue("outbound progress must increase (lng=$lng got ${rp.progressM}, prev=$prev)", rp.progressM >= prev)
            prev = rp.progressM
        }
        // At B the progress is ~bM.
        assertEquals(bM, prev, bM * 0.05)

        for (lng in returnLng) {
            rp.onLocation(LatLng(0.0, lng)); now += 1000
            // The return pass must KEEP increasing past B — it must NOT snap back to the outbound
            // distance for the same longitude (which would be < bM here).
            assertTrue(
                "return progress must keep increasing past B (lng=$lng got ${rp.progressM}, prev=$prev)",
                rp.progressM >= prev,
            )
            assertTrue(
                "return progress must be past the turnaround, not the outbound value (got ${rp.progressM})",
                rp.progressM >= bM - 25.0,
            )
            prev = rp.progressM
        }
    }
}
