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

    // --- nearestProjectionByHeadingUnambiguousOrNull: refuse-to-guess global re-acquire ---

    @Test fun `unambiguous single pass returns the point`() {
        // Eastbound on the out-and-back: only the outbound leg matches the heading → one candidate cluster.
        val p = outAndBack.nearestProjectionByHeadingUnambiguousOrNull(
            onRoad, headingDeg = 90.0, maxPerpM = 40.0, maxHeadingDiffDeg = 45.0, gapMarginM = 75.0, spanCapM = 200.0,
        )
        assertNotNull(p)
        assertTrue("expected outbound arc < $half, got ${p!!.distanceAlongM}", p.distanceAlongM < half)
    }

    @Test fun `two far-apart same-direction passes are AMBIGUOUS and return null`() {
        // The SAME east road ridden twice same-direction, separated along the route by a big north detour:
        // (0,0)→(0,1) east [pass 1], up-across-down, then (0,0)→(0,1) east AGAIN [pass 2]. A point on that
        // east road projects onto BOTH passes with low perp + east heading → heading can't disambiguate a
        // same-direction overlap → must refuse.
        val overlap = PolylinePath(
            listOf(
                LatLng(0.0, 0.0), LatLng(0.0, 1.0), // pass 1 east
                LatLng(1.0, 1.0), LatLng(1.0, 0.0), LatLng(0.0, 0.0), // north, west, south detour
                LatLng(0.0, 1.0), // pass 2 east (same road)
            ),
        )
        val p = overlap.nearestProjectionByHeadingUnambiguousOrNull(
            onRoad, headingDeg = 90.0, maxPerpM = 40.0, maxHeadingDiffDeg = 45.0, gapMarginM = 75.0, spanCapM = 200.0,
        )
        assertNull("two far-apart east passes must be ambiguous → null", p)
    }

    @Test fun `two same-direction passes only ~444 m apart are refused (gap rule beats the old 500 m span)`() {
        // Small same-direction overlap: two east legs at lat 0 separated by a ~333 m N-W-S detour → the two
        // candidates sit ~444 m apart along the route. The OLD raw-span rule (margin 500) had span 444 < 500 →
        // wrongly ADOPTED (up to ~444 m icon error). The gap rule sees a 444 m gap ≫ 75 → refuses. Icon-only;
        // the number carries.
        val nearOverlap = PolylinePath(
            listOf(
                LatLng(0.0, 0.0), LatLng(0.0, 0.001), // pass 1 east ~111 m
                LatLng(0.001, 0.001), LatLng(0.001, 0.0), LatLng(0.0, 0.0), // ~333 m detour
                LatLng(0.0, 0.001), // pass 2 east ~111 m (same road)
            ),
        )
        val onNear = LatLng(0.00005, 0.0005) // ~5.5 m off the east road, at its midpoint
        val p = nearOverlap.nearestProjectionByHeadingUnambiguousOrNull(
            onNear, headingDeg = 90.0, maxPerpM = 40.0, maxHeadingDiffDeg = 45.0, gapMarginM = 75.0, spanCapM = 200.0,
        )
        assertNull("two same-direction passes ~444 m apart must be refused by the gap rule", p)
    }

    @Test fun `off-route beyond maxPerp returns null (nothing to adopt)`() {
        val far = LatLng(0.01, 0.5)
        assertNull(
            outAndBack.nearestProjectionByHeadingUnambiguousOrNull(
                far, headingDeg = 90.0, maxPerpM = 40.0, maxHeadingDiffDeg = 45.0, gapMarginM = 75.0, spanCapM = 200.0,
            ),
        )
    }

    @Test fun `bearingDiffDeg is circular`() {
        assertEquals(10.0, Polyline.bearingDiffDeg(5.0, 355.0), 1e-9)
        assertEquals(180.0, Polyline.bearingDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, Polyline.bearingDiffDeg(90.0, 90.0), 1e-9)
    }
}
