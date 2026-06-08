package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Differential test proving optimization (B): the binary-search index lookup used in
 * `SegmentMatcher.pointAtDistance` returns the IDENTICAL index to the original
 * `cumulativeM.indexOfFirst { it >= distM }` across many distances — exact vertices, midpoints,
 * out-of-range clamps. `pointAtDistance` is private to SegmentMatcher, so this replicates the two
 * index-lookup algorithms verbatim and asserts they agree; the surrounding interpolation in
 * pointAtDistance is unchanged, so equal indices => identical coordinates.
 *
 * Deterministic: FIXED `Random(42)` seed.
 */
class PointAtDistanceDiffTest {

    // ORIGINAL lookup.
    private fun oldIndex(cumulativeM: DoubleArray, distM: Double): Int =
        cumulativeM.indexOfFirst { it >= distM }

    // NEW lookup (lower-bound binary search), copied from the optimized pointAtDistance.
    private fun newIndex(cumulativeM: DoubleArray, distM: Double): Int {
        var lo = 0
        var high = cumulativeM.size
        while (lo < high) {
            val mid = (lo + high) ushr 1
            if (cumulativeM[mid] >= distM) high = mid else lo = mid + 1
        }
        return if (lo < cumulativeM.size) lo else -1 // indexOfFirst returns -1 when none match
    }

    private fun randomCumulative(rnd: Random, n: Int): DoubleArray {
        val arr = DoubleArray(n)
        for (i in 1 until n) {
            // Allow occasional zero-length steps to exercise tie handling.
            val step = if (rnd.nextInt(10) == 0) 0.0 else rnd.nextDouble(0.0, 50.0)
            arr[i] = arr[i - 1] + step
        }
        return arr
    }

    @Test fun `binary search matches indexOfFirst across random distances`() {
        val rnd = Random(42)
        repeat(200) {
            val cm = randomCumulative(rnd, rnd.nextInt(2, 200))
            val total = cm.last()
            // Exact vertices.
            for (v in cm) {
                assertEquals(oldIndex(cm, v), newIndex(cm, v))
            }
            // Midpoints between consecutive distinct vertices.
            for (i in 1 until cm.size) {
                if (cm[i] > cm[i - 1]) {
                    val mid = (cm[i] + cm[i - 1]) / 2.0
                    assertEquals(oldIndex(cm, mid), newIndex(cm, mid))
                }
            }
            // Random in-range and out-of-range distances (incl. clamps).
            repeat(50) {
                val d = rnd.nextDouble(-100.0, total + 100.0)
                assertEquals(oldIndex(cm, d), newIndex(cm, d))
            }
            // Tiny epsilon around vertices to stress tie boundaries.
            for (v in cm) {
                assertEquals(oldIndex(cm, v - 1e-9), newIndex(cm, v - 1e-9))
                assertEquals(oldIndex(cm, v + 1e-9), newIndex(cm, v + 1e-9))
            }
        }
    }
}
