package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D0-bootstrap pass-disambiguation by heading ([PolylinePath.nearestProjectionByHeadingInWindowOrNull]).
 * An out-and-back route (east to B, then back west to A) makes every point on the shared road project
 * equally onto TWO arcs; only the rider's heading tells the outbound pass from the return pass.
 */
class PolylineHeadingProjectionTest {
    // A→B→A along the equator: outbound east (bearing ~90°), return west (bearing ~270°).
    private val outAndBack = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(0.0, 0.0)))
    private val half = outAndBack.totalM / 2.0 // ≈ the turnaround at B
    // A point on the shared road near the midpoint of each leg, nudged slightly off so perp > 0 on both.
    private val onRoad = LatLng(0.00005, 0.5)

    @Test fun `eastbound heading picks the OUTBOUND arc`() {
        val p = outAndBack.nearestProjectionByHeadingInWindowOrNull(
            onRoad, headingDeg = 90.0, aroundDistanceM = outAndBack.totalM / 2.0,
            backWindowM = outAndBack.totalM, fwdWindowM = outAndBack.totalM,
            maxPerpM = 40.0, maxHeadingDiffDeg = 60.0,
        )
        assertNotNull(p)
        // Outbound: distance-along is in the FIRST leg (< the turnaround).
        assertTrue("expected outbound arc < $half, got ${p!!.distanceAlongM}", p.distanceAlongM < half)
        assertEquals(0.5 * half, p.distanceAlongM, half * 0.05)
    }

    @Test fun `westbound heading picks the RETURN arc`() {
        val p = outAndBack.nearestProjectionByHeadingInWindowOrNull(
            onRoad, headingDeg = 270.0, aroundDistanceM = outAndBack.totalM / 2.0,
            backWindowM = outAndBack.totalM, fwdWindowM = outAndBack.totalM,
            maxPerpM = 40.0, maxHeadingDiffDeg = 60.0,
        )
        assertNotNull(p)
        // Return: distance-along is in the SECOND leg (> the turnaround).
        assertTrue("expected return arc > $half, got ${p!!.distanceAlongM}", p.distanceAlongM > half)
        assertEquals(1.5 * half, p.distanceAlongM, half * 0.05)
    }

    @Test fun `a heading matching neither pass returns null`() {
        // Northbound on an east-west road matches no segment within 60°.
        val p = outAndBack.nearestProjectionByHeadingInWindowOrNull(
            onRoad, headingDeg = 0.0, aroundDistanceM = outAndBack.totalM / 2.0,
            backWindowM = outAndBack.totalM, fwdWindowM = outAndBack.totalM,
            maxPerpM = 40.0, maxHeadingDiffDeg = 60.0,
        )
        assertNull(p)
    }

    @Test fun `a point off the line beyond maxPerp returns null`() {
        val far = LatLng(0.01, 0.5) // ~1.1 km off the road
        val p = outAndBack.nearestProjectionByHeadingInWindowOrNull(
            far, headingDeg = 90.0, aroundDistanceM = outAndBack.totalM / 2.0,
            backWindowM = outAndBack.totalM, fwdWindowM = outAndBack.totalM,
            maxPerpM = 40.0, maxHeadingDiffDeg = 60.0,
        )
        assertNull(p)
    }

    @Test fun `bearingDiffDeg is circular`() {
        assertEquals(10.0, Polyline.bearingDiffDeg(5.0, 355.0), 1e-9)
        assertEquals(180.0, Polyline.bearingDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, Polyline.bearingDiffDeg(90.0, 90.0), 1e-9)
    }
}
