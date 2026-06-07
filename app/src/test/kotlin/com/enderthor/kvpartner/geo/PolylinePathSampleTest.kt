package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylinePathSampleTest {
    private val east = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))

    @Test fun `midpoint by distance interpolates the coordinate`() {
        val s = east.sampleAt(east.totalM / 2.0)
        assertEquals(0.0, s.location.lat, 1e-9)
        assertEquals(0.5, s.location.lng, 1e-6)
    }

    @Test fun `bearing due east is about 90 degrees`() {
        val s = east.sampleAt(east.totalM / 2.0)
        assertEquals(90.0, s.bearingDeg, 0.5)
    }

    @Test fun `distance past the end clamps to the last point`() {
        val s = east.sampleAt(east.totalM * 5)
        assertEquals(1.0, s.location.lng, 1e-9)
    }

    @Test fun `negative distance clamps to the first point`() {
        val s = east.sampleAt(-100.0)
        assertEquals(0.0, s.location.lng, 1e-9)
    }

    @Test fun `bearing due north is about 0 degrees`() {
        val north = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(1.0, 0.0)))
        assertEquals(0.0, north.sampleAt(north.totalM / 2.0).bearingDeg, 0.5)
    }
}
