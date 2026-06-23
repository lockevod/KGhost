package com.enderthor.kghost.geo

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

    // A bent multi-segment path so the segment lookup actually has to choose between several
    // segments (a 2-point path can't exercise an off-by-one in the lookup). Segments have
    // deliberately UNEQUAL lengths so cumulative boundaries land at irregular distances.
    private val multi = PolylinePath(
        listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 1.0),   // east
            LatLng(0.5, 1.0),   // north
            LatLng(0.5, 3.0),   // east (longer)
            LatLng(2.0, 3.0),   // north (longest)
        ),
    )

    // Reference: the original linear-scan semantics sampleAt must reproduce exactly. Kept here so
    // the binary-search implementation is held to byte-identical output across the whole domain.
    private fun refSampleAt(path: PolylinePath, distanceAlongM: Double): RouteSample {
        val pts = path.points
        val cum = path.cumulativeM
        val d = distanceAlongM.coerceIn(0.0, path.totalM)
        var i = 0
        while (i < pts.size - 2 && cum[i + 1] < d) i++
        val a = pts[i]; val b = pts[i + 1]
        val segLen = cum[i + 1] - cum[i]
        val f = if (segLen > 0.0) ((d - cum[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        return RouteSample(LatLng(a.lat + f * (b.lat - a.lat), a.lng + f * (b.lng - a.lng)), Polyline.bearingDeg(a, b))
    }

    @Test fun `sampleAt matches the linear-scan reference across the whole route`() {
        val total = multi.totalM
        // Sweep fine-grained distances PLUS every exact cumulative boundary (the off-by-one zones).
        val probes = buildList {
            var d = -50.0
            while (d <= total + 50.0) { add(d); d += total / 997.0 }
            multi.cumulativeM.forEach { add(it); add(it - 1e-6); add(it + 1e-6) }
        }
        for (d in probes) {
            val got = multi.sampleAt(d)
            val want = refSampleAt(multi, d)
            assertEquals("lat @ $d", want.location.lat, got.location.lat, 1e-9)
            assertEquals("lng @ $d", want.location.lng, got.location.lng, 1e-9)
            assertEquals("bearing @ $d", want.bearingDeg, got.bearingDeg, 1e-9)
        }
    }

    @Test fun `sampleAt matches the linear reference across a path with a zero-length segment`() {
        // A repeated vertex (segment of length 0) makes two consecutive cumulativeM equal — the
        // lower-bound binary search must pick the SAME (leftmost) segment the linear scan did.
        val dup = PolylinePath(
            listOf(
                LatLng(0.0, 0.0),
                LatLng(0.0, 1.0),
                LatLng(0.0, 1.0),   // duplicate → zero-length segment, equal cumulativeM
                LatLng(0.0, 2.0),
            ),
        )
        val total = dup.totalM
        val probes = buildList {
            var d = -10.0
            while (d <= total + 10.0) { add(d); d += total / 333.0 }
            dup.cumulativeM.forEach { add(it); add(it - 1e-6); add(it + 1e-6) }
        }
        for (d in probes) {
            val got = dup.sampleAt(d)
            val want = refSampleAt(dup, d)
            assertEquals("lat @ $d", want.location.lat, got.location.lat, 1e-9)
            assertEquals("lng @ $d", want.location.lng, got.location.lng, 1e-9)
            assertEquals("bearing @ $d", want.bearingDeg, got.bearingDeg, 1e-9)
        }
    }

    @Test fun `sampleAt at an exact interior boundary sits on that vertex`() {
        val boundary = multi.cumulativeM[2] // start of the 3rd segment
        val s = multi.sampleAt(boundary)
        assertEquals(multi.points[2].lat, s.location.lat, 1e-9)
        assertEquals(multi.points[2].lng, s.location.lng, 1e-9)
    }
}
