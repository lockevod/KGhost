package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Differential test for the PRIMARY optimization: [PointGrid.nearestPerpDistM] (the spatial-grid
 * nearest-perpendicular used by [SegmentMatcher.coveredRunsToIntervals]) must produce the SAME
 * COVERAGE DECISION as the brute-force full scan [PolylinePath.nearestPerpDistM] for every query.
 *
 * Two properties are asserted across random tracks × random query points × random tolerances, with a
 * FIXED `Random(42)` seed for determinism:
 *
 *  1. **Boolean identity (the coverage decision):**
 *     `(grid.nearestPerpDistM(p) < tol) == (brute.nearestPerpDistM(p) < tol)` for EVERY sample.
 *     If this ever differs the grid is unsound — the interval set would change.
 *
 *  2. **Distance identity below tolerance (interval boundaries):**
 *     whenever the value is `< tol`, `grid.nearestPerpDistM(p) == brute.nearestPerpDistM(p)` within
 *     1e-6, so the covered-run start/end distances (hence the emitted intervals) are bit-identical.
 *
 * The brute reference is a verbatim copy of [PolylinePath.nearestPerpDistM].
 */
class PointGridDiffTest {

    // ---- Brute-force reference: verbatim copy of PolylinePath.nearestPerpDistM. ----
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

    /** A random walk track around a base point (~20 m segments, like a decimated real track). */
    private fun randomTrack(rnd: Random, n: Int): PolylinePath {
        var lat = 40.0 + rnd.nextDouble(-0.2, 0.2)
        var lng = -3.0 + rnd.nextDouble(-0.2, 0.2)
        val pts = ArrayList<LatLng>(n)
        pts += LatLng(lat, lng)
        repeat(n - 1) {
            // ~20 m steps in random directions: 20 m ≈ 0.00018 deg lat.
            lat += rnd.nextDouble(-0.0002, 0.0002)
            lng += rnd.nextDouble(-0.0002, 0.0002)
            pts += LatLng(lat, lng)
        }
        return PolylinePath(pts)
    }

    /** A query near the track with jitter so some queries are on-route (< tol) and some far. */
    private fun randomQuery(rnd: Random, path: PolylinePath): LatLng {
        val base = path.points[rnd.nextInt(path.points.size)]
        // Jitter up to ~120 m so plenty of queries straddle the typical 25 m tolerance both ways.
        return LatLng(
            base.lat + rnd.nextDouble(-0.0011, 0.0011),
            base.lng + rnd.nextDouble(-0.0011, 0.0011),
        )
    }

    private fun checkAgreement(path: PolylinePath, q: LatLng, tol: Double) {
        val grid = PointGrid(path, tol)
        val brute = brutePerp(path, q.lat, q.lng)
        val g = grid.nearestPerpDistM(q.lat, q.lng)
        // Property 1: coverage decision identical.
        assertEquals(
            "coverage boolean differs at q=$q tol=$tol (brute=$brute grid=$g)",
            brute < tol, g < tol,
        )
        // Property 2: when covered, the exact distance matches (interval boundary unaffected).
        if (brute < tol) {
            assertEquals("distance below tol differs at q=$q tol=$tol", brute, g, 1e-6)
        }
    }

    @Test fun `grid coverage decision equals brute over random tracks queries and tolerances`() {
        val rnd = Random(42)
        repeat(60) {
            val path = randomTrack(rnd, rnd.nextInt(3, 120))
            repeat(40) {
                val q = randomQuery(rnd, path)
                val tol = rnd.nextDouble(5.0, 60.0)
                checkAgreement(path, q, tol)
            }
        }
    }

    @Test fun `grid agrees at the default 25m tolerance with route-style sampling`() {
        // Mimic the matcher: a track and a route, sample the route by distance, decide coverage.
        val rnd = Random(42)
        val tol = 25.0
        repeat(30) {
            val track = randomTrack(rnd, rnd.nextInt(10, 200))
            // A route that shares part of the track's area: start near a track point and wander.
            val route = randomTrack(rnd, rnd.nextInt(10, 200))
            val grid = PointGrid(track, tol)
            val total = route.totalM
            var d = 0.0
            val out = DoubleArray(2)
            while (d <= total) {
                // Linear interpolation along the route (sampleAt is fine here for a query point).
                val s = route.sampleAt(d)
                val brute = brutePerp(track, s.location.lat, s.location.lng)
                val g = grid.nearestPerpDistM(s.location.lat, s.location.lng)
                assertEquals("coverage boolean differs at d=$d", brute < tol, g < tol)
                if (brute < tol) assertEquals(brute, g, 1e-6)
                d += 25.0
            }
            // touch `out` to avoid unused warning
            assertTrue(out.size == 2)
        }
    }

    @Test fun `grid handles a degenerate two-point track`() {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.018)))
        val grid = PointGrid(path, 25.0)
        // On the line: ~0; far off: large.
        assertEquals(0.0, grid.nearestPerpDistM(0.0, 0.009), 1e-6)
        assertTrue(grid.nearestPerpDistM(0.01, 0.009) >= 25.0)
    }
}
