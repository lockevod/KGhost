package com.enderthor.kvpartner.engine

import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.PolylinePath
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
}
