package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Test

/**
 * Differential + memory-bound test for FIX 3: [PointGrid.forPathClippedTo].
 *
 * A recorded track that crosses the route and then continues for tens of km would, with the
 * unclipped grid, allocate a grid over its WHOLE bbox (O(trackSpan / cell²) cells) → possible
 * OutOfMemoryError. The clipped grid indexes only the segments whose bbox intersects the route bbox
 * fattened by the tolerance, so:
 *
 *  (a) COVERAGE-IDENTICAL: for every ROUTE-area query the clipped grid's coverage decision
 *      (`< tol`) and the exact distance when `< tol` match the brute-force full scan over ALL the
 *      track's segments (verbatim copy of [PolylinePath.nearestPerpDistM]). The far tail can never be
 *      within `tol` of any route sample, so dropping it changes nothing.
 *  (b) MEMORY-BOUNDED: the clipped grid's cell count is proportional to the CLIP (route) area, NOT
 *      the track span — orders of magnitude smaller than an unclipped grid over the full track, and
 *      it builds without OOM.
 */
class PointGridClipDiffTest {

    // ---- Brute-force reference: verbatim copy of PolylinePath.nearestPerpDistM (ALL segments). ----
    private fun brutePerp(path: PolylinePath, pLat: Double, pLng: Double): Double {
        var bestPerp = Double.MAX_VALUE
        val pts = path.points
        val n = pts.size
        for (i in 0 until n - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
            val bx = (b.lng - a.lng) * mPerDegLng; val by = (b.lat - a.lat) * mPerDegLat
            val px = (pLng - a.lng) * mPerDegLng; val py = (pLat - a.lat) * mPerDegLat
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 == 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
            val fx = t * bx; val fy = t * by
            val perp = sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < bestPerp) bestPerp = perp
        }
        return bestPerp
    }

    /** Builds the route-bbox-fattened-by-tol clip box exactly as SegmentMatcher does. */
    private fun clipFor(route: PolylinePath, tol: Double): BBox {
        val rb = BBox.around(route.points)!!
        val padLat = tol / 111_320.0
        val midLatRad = Math.toRadians((rb.minLat + rb.maxLat) / 2.0)
        val cosLat = cos(midLatRad).coerceAtLeast(1e-6)
        val padLng = tol / (111_320.0 * cosLat)
        return BBox(
            minLat = rb.minLat - padLat, maxLat = rb.maxLat + padLat,
            minLng = rb.minLng - padLng, maxLng = rb.maxLng + padLng,
        )
    }

    @Test fun `clipped grid coverage equals brute full-scan over route-area samples`() {
        val tol = 25.0
        // Route: ~1 km straight east along the equator (lng 0 .. ~0.009 ≈ 1002 m).
        val route = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.009)))

        // Track: rides ALONG the route's middle (covers it), then SHOOTS far north for ~80 km — a
        // long tail hundreds of cells away from the route. ~25 m steps near the route.
        val onRoute = (0..30).map { i -> LatLng(0.0, 0.0015 + i * 0.0002) } // lng 0.0015..0.0075, on route
        // Far tail: jump ~80 km north (0.72° lat) and continue. These segments are far from the route.
        var lat = 0.72
        val lng0 = 0.0045
        val tail = (0..2000).map { j -> LatLng(lat + j * 0.0002, lng0) } // 2000 segments far away
        val track = PolylinePath(onRoute + tail)

        val clip = clipFor(route, tol)
        val grid = PointGrid.forPathClippedTo(track, tol, clip)

        // Sample the route by distance and assert the clipped grid agrees with the brute full scan
        // over ALL track segments (including the far tail) for every route-area query.
        val total = route.totalM
        var d = 0.0
        while (d <= total) {
            val s = route.sampleAt(d)
            val brute = brutePerp(track, s.location.lat, s.location.lng)
            val g = grid.nearestPerpDistM(s.location.lat, s.location.lng)
            assertEquals("coverage boolean differs at d=$d (brute=$brute grid=$g)", brute < tol, g < tol)
            if (brute < tol) assertEquals("distance below tol differs at d=$d", brute, g, 1e-6)
            d += 5.0
        }
    }

    @Test fun `clipped grid cell count is bounded to the route area not the track span`() {
        val tol = 25.0
        val route = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.009)))

        // Same far-tailed track as above.
        val onRoute = (0..30).map { i -> LatLng(0.0, 0.0015 + i * 0.0002) }
        val tail = (0..2000).map { j -> LatLng(0.72 + j * 0.0002, 0.0045 + j * 0.0002) }
        val track = PolylinePath(onRoute + tail)

        val clip = clipFor(route, tol)
        val clipped = PointGrid.forPathClippedTo(track, tol, clip)
        val unclipped = PointGrid(track, tol)

        // The clipped grid spans only the ~1 km route area (a few tens of cells per axis), while the
        // unclipped grid spans the ~80 km tail (thousands of cells per axis). Assert a large gap.
        assertTrue(
            "clipped cellCount (${clipped.cellCount}) must be far below unclipped (${unclipped.cellCount})",
            clipped.cellCount * 50L < unclipped.cellCount,
        )
        // And an absolute bound: the route area is ~1 km × ~tol → a couple thousand cells at most.
        assertTrue(
            "clipped cellCount (${clipped.cellCount}) must be bounded to the route area",
            clipped.cellCount < 10_000,
        )
    }
}
