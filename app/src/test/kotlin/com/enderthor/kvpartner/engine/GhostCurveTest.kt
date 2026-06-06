package com.enderthor.kvpartner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhostCurveTest {
    private val curve = GhostCurve(listOf(
        GhostSample(0.0, 0.0),
        GhostSample(1000.0, 200.0),   // 5 m/s
        GhostSample(2000.0, 500.0),   // 3.33 m/s (slower)
    ))

    @Test fun `timeAt interpolates linearly within a segment`() {
        assertEquals(100.0, curve.timeAt(500.0), 1e-6)    // middle of the first segment
        assertEquals(350.0, curve.timeAt(1500.0), 1e-6)   // middle of the second segment
    }

    @Test fun `distanceAt is the inverse`() {
        assertEquals(500.0, curve.distanceAt(100.0), 1e-6)
        assertEquals(1500.0, curve.distanceAt(350.0), 1e-6)
    }

    @Test fun `timeAt clamps above the range`() {
        assertEquals(500.0, curve.timeAt(9999.0), 1e-6)
    }

    @Test fun `distanceAt clamps below the range`() {
        assertEquals(0.0, curve.distanceAt(-5.0), 1e-6)
    }

    @Test fun `totals are correct`() {
        assertEquals(2000.0, curve.totalDistanceM, 1e-6)
        assertEquals(500.0, curve.totalTimeS, 1e-6)
    }

    @Test fun `rejects non-monotonic samples`() {
        assertThrows(IllegalArgumentException::class.java) {
            GhostCurve(listOf(GhostSample(0.0, 0.0), GhostSample(100.0, 50.0), GhostSample(50.0, 80.0)))
        }
    }

    @Test fun `rejects empty or single-point list`() {
        assertThrows(IllegalArgumentException::class.java) { GhostCurve(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GhostCurve(listOf(GhostSample(0.0, 0.0))) }
    }
}
