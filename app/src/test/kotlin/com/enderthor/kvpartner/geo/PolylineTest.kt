package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    // Canonical Google example: "_p~iF~ps|U_ulLnnqC_mqNvxq`@" → 3 points.
    @Test fun `decodes the canonical google polyline`() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5); assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test fun `haversine matches a known short distance`() {
        // ~111.2 m per 0.001 deg latitude near the equator-ish; use a 1-degree lat step ≈ 111.19 km.
        val d = Polyline.haversineM(LatLng(0.0, 0.0), LatLng(1.0, 0.0))
        assertEquals(111_195.0, d, 200.0)
    }

    @Test fun `path cumulative distances are monotonic and total matches`() {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001), LatLng(0.0, 0.002)))
        assertEquals(0.0, path.cumulativeM[0], 1e-9)
        assertTrue(path.cumulativeM[1] > 0.0)
        assertEquals(path.cumulativeM[2], path.totalM, 1e-9)
        assertTrue(path.cumulativeM[2] > path.cumulativeM[1])
    }

    @Test fun `nearestProjection returns distance-along for a point on the path`() {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01))) // east segment
        val proj = path.nearestProjection(LatLng(0.0, 0.005)) // halfway
        assertEquals(path.totalM / 2.0, proj.distanceAlongM, path.totalM * 0.02)
        assertTrue(proj.perpDistM < 1.0)
    }

    @Test fun `nearestProjection reports a large perp distance for an off-route point`() {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01)))
        val proj = path.nearestProjection(LatLng(0.01, 0.005)) // ~1.1 km north of the line
        assertTrue(proj.perpDistM > 500.0)
    }
}
