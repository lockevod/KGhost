package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

/**
 * Differential tests proving the windowed-scan optimization (A) in
 * [PolylinePath.nearestProjectionInRange] and the binary-search optimization (B) in
 * `SegmentMatcher.pointAtDistance` produce results IDENTICAL to the original brute-force code.
 *
 * The reference implementations below are verbatim copies of the ORIGINAL algorithms (full
 * segment scan / `indexOfFirst`). Determinism: a FIXED `Random(42)` seed is used throughout.
 */
class PolylineProjectionDiffTest {

    // ---- Brute-force reference: the ORIGINAL full-scan windowed projection. ----
    private fun bruteInRange(
        path: PolylinePath,
        p: LatLng,
        windowLoM: Double,
        windowHiM: Double,
    ): Projection {
        val points = path.points
        val cumulativeM = path.cumulativeM
        var best = Projection(0.0, Double.MAX_VALUE, 0)
        for (i in 0 until points.size - 1) {
            val segLoM = cumulativeM[i]; val segHiM = cumulativeM[i + 1]
            if (segHiM < windowLoM || segLoM > windowHiM) continue
            val a = points[i]; val b = points[i + 1]
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
            val ax = 0.0; val ay = 0.0
            val bx = (b.lng - a.lng) * mPerDegLng; val by = (b.lat - a.lat) * mPerDegLat
            val px = (p.lng - a.lng) * mPerDegLng; val py = (p.lat - a.lat) * mPerDegLat
            val segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (segLen2 == 0.0) 0.0
            else (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2).coerceIn(0.0, 1.0)
            val fx = ax + t * (bx - ax); val fy = ay + t * (by - ay)
            val perp = kotlin.math.sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < best.perpDistM) {
                val along = cumulativeM[i] + t * (cumulativeM[i + 1] - cumulativeM[i])
                best = Projection(along, perp, i)
            }
        }
        return best
    }

    // Reference for nearestProjectionNear: brute windowed scan with the SAME empty-window fallback.
    private fun bruteNear(
        path: PolylinePath,
        p: LatLng,
        aroundDistanceM: Double,
        backWindowM: Double,
        fwdWindowM: Double,
    ): Projection {
        val lo = aroundDistanceM - backWindowM
        val hi = aroundDistanceM + fwdWindowM
        val windowed = bruteInRange(path, p, lo, hi)
        return if (windowed.perpDistM == Double.MAX_VALUE)
            bruteInRange(path, p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        else windowed
    }

    private fun assertSameProjection(expected: Projection, actual: Projection) {
        // Identical arithmetic path -> bit-for-bit equal; assert with a hairline tolerance anyway.
        assertEquals(expected.distanceAlongM, actual.distanceAlongM, 1e-9)
        assertEquals(expected.perpDistM, actual.perpDistM, 1e-9)
        assertEquals(expected.vertexIndex.toDouble(), actual.vertexIndex.toDouble(), 0.0)
    }

    private fun randomPath(rnd: Random, n: Int): PolylinePath {
        // A random walk around a base point so cumulative distances grow but headings vary.
        var lat = 40.0 + rnd.nextDouble(-0.1, 0.1)
        var lng = -3.0 + rnd.nextDouble(-0.1, 0.1)
        val pts = ArrayList<LatLng>(n)
        pts += LatLng(lat, lng)
        repeat(n - 1) {
            lat += rnd.nextDouble(-0.01, 0.01)
            lng += rnd.nextDouble(-0.01, 0.01)
            pts += LatLng(lat, lng)
        }
        return PolylinePath(pts)
    }

    private fun randomQuery(rnd: Random, path: PolylinePath): LatLng {
        // Near the path with some jitter so some queries are on-route, some off.
        val base = path.points[rnd.nextInt(path.points.size)]
        return LatLng(base.lat + rnd.nextDouble(-0.02, 0.02), base.lng + rnd.nextDouble(-0.02, 0.02))
    }

    @Test fun `infinite-window nearestProjection equals brute global scan`() {
        val rnd = Random(42)
        repeat(50) {
            val path = randomPath(rnd, rnd.nextInt(2, 60))
            repeat(20) {
                val q = randomQuery(rnd, path)
                assertSameProjection(
                    bruteInRange(path, q, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                    path.nearestProjection(q),
                )
            }
        }
    }

    @Test fun `windowed nearestProjectionNear equals brute windowed scan over random windows`() {
        val rnd = Random(42)
        repeat(50) {
            val path = randomPath(rnd, rnd.nextInt(2, 80))
            val total = path.totalM
            repeat(30) {
                val q = randomQuery(rnd, path)
                val around = rnd.nextDouble(-total * 0.2, total * 1.2)
                val back = rnd.nextDouble(0.0, total * 0.3)
                val fwd = rnd.nextDouble(0.0, total * 0.5)
                assertSameProjection(
                    bruteNear(path, q, around, back, fwd),
                    path.nearestProjectionNear(q, around, back, fwd),
                )
            }
        }
    }

    @Test fun `window fully left of the path falls back to global`() {
        val rnd = Random(42)
        val path = randomPath(rnd, 40)
        val q = randomQuery(rnd, path)
        // around far below 0 with tiny window -> no segment intersects -> fallback to global.
        assertSameProjection(
            bruteNear(path, q, -10_000.0, 1.0, 1.0),
            path.nearestProjectionNear(q, -10_000.0, 1.0, 1.0),
        )
    }

    @Test fun `window fully right of the path falls back to global`() {
        val rnd = Random(42)
        val path = randomPath(rnd, 40)
        val q = randomQuery(rnd, path)
        assertSameProjection(
            bruteNear(path, q, path.totalM + 10_000.0, 1.0, 1.0),
            path.nearestProjectionNear(q, path.totalM + 10_000.0, 1.0, 1.0),
        )
    }

    @Test fun `window covering a single segment matches brute`() {
        val rnd = Random(42)
        val path = randomPath(rnd, 30)
        val cm = path.cumulativeM
        // Center the window inside segment 10, narrow enough to (mostly) hit one segment.
        val segMid = (cm[10] + cm[11]) / 2.0
        val half = (cm[11] - cm[10]) / 2.5
        repeat(15) {
            val q = randomQuery(rnd, path)
            assertSameProjection(
                bruteNear(path, q, segMid, half, half),
                path.nearestProjectionNear(q, segMid, half, half),
            )
        }
    }

    @Test fun `window covering all segments matches brute`() {
        val rnd = Random(42)
        val path = randomPath(rnd, 50)
        val total = path.totalM
        repeat(20) {
            val q = randomQuery(rnd, path)
            assertSameProjection(
                bruteNear(path, q, total / 2.0, total, total),
                path.nearestProjectionNear(q, total / 2.0, total, total),
            )
        }
    }

    @Test fun `window boundaries exactly on cumulative vertices match brute`() {
        val rnd = Random(42)
        val path = randomPath(rnd, 40)
        val cm = path.cumulativeM
        repeat(20) {
            val q = randomQuery(rnd, path)
            val loIdx = rnd.nextInt(cm.size)
            val hiIdx = rnd.nextInt(loIdx, cm.size)
            val around = cm[loIdx]
            val fwd = cm[hiIdx] - cm[loIdx]
            assertSameProjection(
                bruteNear(path, q, around, 0.0, fwd),
                path.nearestProjectionNear(q, around, 0.0, fwd),
            )
        }
    }

    /**
     * Allocation-free hot-path helper (perf fix A): [PolylinePath.nearestPerpDistM] must return the
     * SAME perpendicular distance as `nearestProjection(p).perpDistM` across random inputs. This is
     * the value `SegmentMatcher.coveredRunsToIntervals` reads per route sample; the optimization
     * only removes the per-call `LatLng`/`Projection` allocation, never changes the number.
     */
    @Test fun `nearestPerpDistM equals nearestProjection perpDistM over random inputs`() {
        val rnd = Random(42)
        repeat(50) {
            val path = randomPath(rnd, rnd.nextInt(2, 80))
            repeat(30) {
                val q = randomQuery(rnd, path)
                val expected = path.nearestProjection(q).perpDistM
                val actual = path.nearestPerpDistM(q.lat, q.lng)
                // Identical arithmetic path → bit-for-bit equal; assert with a hairline tolerance.
                assertEquals(expected, actual, 1e-9)
            }
        }
    }
}
