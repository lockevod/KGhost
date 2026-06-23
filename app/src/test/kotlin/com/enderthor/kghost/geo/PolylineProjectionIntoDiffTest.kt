package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the allocation-free [PolylinePath.nearestProjectionNearInto] is BYTE-IDENTICAL to the
 * object-returning [PolylinePath.nearestProjectionNear] it replaces in SegmentMatcher's hot loop:
 *  - when the window is non-empty (Into returns true) the written [along, perp] equal
 *    nearestProjectionNear's result exactly (delta 0.0), and
 *  - when the window is empty (Into returns false) nearestProjectionNear takes the SAME global
 *    fallback the caller will (equals the plain global [nearestProjection]).
 */
class PolylineProjectionIntoDiffTest {

    // Bent multi-segment path with unequal segment lengths so windows land at irregular distances.
    private val path = PolylinePath(
        listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 1.0),
            LatLng(0.5, 1.0),
            LatLng(0.5, 3.0),
            LatLng(2.0, 3.0),
        ),
    )

    @Test fun `Into matches nearestProjectionNear across points and windows`() {
        val out = DoubleArray(2)
        val total = path.totalM
        val queries = listOf(
            LatLng(0.0, 0.5), LatLng(0.25, 1.0), LatLng(0.5, 2.0), LatLng(1.0, 3.0),
            LatLng(0.0, 0.0), LatLng(2.0, 3.0), // exact endpoints
            LatLng(5.0, 5.0), LatLng(-1.0, -1.0), // far off-path → empty windows
        )
        var checked = 0
        var around = 0.0
        while (around <= total) {
            for (back in listOf(0.0, 30.0, 100.0)) {
                for (fwd in listOf(50.0, 250.0, total)) {
                    for (q in queries) {
                        val found = path.nearestProjectionNearInto(q.lat, q.lng, around, back, fwd, out)
                        val ref = path.nearestProjectionNear(q, around, back, fwd)
                        if (found) {
                            // Window non-empty → ref is the windowed projection → must match exactly.
                            assertEquals("along @around=$around back=$back fwd=$fwd q=$q", ref.distanceAlongM, out[0], 0.0)
                            assertEquals("perp @around=$around back=$back fwd=$fwd q=$q", ref.perpDistM, out[1], 0.0)
                        } else {
                            // Window empty → nearestProjectionNear falls back to the global projection,
                            // which is exactly what the caller does when Into returns false.
                            val global = path.nearestProjection(q)
                            assertEquals(global.distanceAlongM, ref.distanceAlongM, 0.0)
                            assertEquals(global.perpDistM, ref.perpDistM, 0.0)
                        }
                        checked++
                    }
                }
            }
            around += total / 13.0
        }
        assertTrue("swept enough cases", checked > 500)
    }
}
