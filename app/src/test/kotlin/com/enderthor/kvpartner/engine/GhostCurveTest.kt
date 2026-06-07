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

    @Test fun `timeAt clamps below the range`() {
        assertEquals(0.0, curve.timeAt(-100.0), 1e-6)   // first sample's timeS
    }

    @Test fun `distanceAt clamps above the range`() {
        assertEquals(2000.0, curve.distanceAt(99999.0), 1e-6)   // totalDistanceM
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

    @Test fun `timeAt of non-finite input returns the clamped endpoint and does not throw`() {
        // Without the finiteness guard these would fall through to indexOfFirst (returns -1) and
        // crash with samples[-2]. The first sample's timeS is the clamped value.
        assertEquals(0.0, curve.timeAt(Double.NaN), 1e-6)
        assertEquals(0.0, curve.timeAt(Double.POSITIVE_INFINITY), 1e-6)
        assertEquals(0.0, curve.timeAt(Double.NEGATIVE_INFINITY), 1e-6)
    }

    @Test fun `distanceAt of non-finite input returns the clamped endpoint and does not throw`() {
        assertEquals(0.0, curve.distanceAt(Double.NaN), 1e-6)
        assertEquals(0.0, curve.distanceAt(Double.POSITIVE_INFINITY), 1e-6)
        assertEquals(0.0, curve.distanceAt(Double.NEGATIVE_INFINITY), 1e-6)
    }

    @Test fun `round-trip distanceAt of timeAt recovers the distance`() {
        // Locks interpolation correctness through the binary-search bracket selection.
        for (d in listOf(1.0, 250.0, 500.0, 999.0, 1000.0, 1001.0, 1500.0, 1999.0)) {
            assertEquals(d, curve.distanceAt(curve.timeAt(d)), 1e-6)
        }
    }

    @Test fun `multi-sample lookups match hand-computed values at vertices and interior points`() {
        // 6 samples with varying segment speeds; guards the binary-search bracket vs the old scan.
        val c = GhostCurve(listOf(
            GhostSample(0.0, 0.0),
            GhostSample(100.0, 20.0),   // 5 m/s
            GhostSample(300.0, 60.0),   // 5 m/s
            GhostSample(400.0, 110.0),  // 2 m/s
            GhostSample(700.0, 140.0),  // 10 m/s
            GhostSample(1000.0, 200.0), // 5 m/s
        ))
        // Exact vertices
        assertEquals(20.0, c.timeAt(100.0), 1e-6)
        assertEquals(60.0, c.timeAt(300.0), 1e-6)
        assertEquals(110.0, c.timeAt(400.0), 1e-6)
        assertEquals(140.0, c.timeAt(700.0), 1e-6)
        assertEquals(300.0, c.distanceAt(60.0), 1e-6)
        assertEquals(700.0, c.distanceAt(140.0), 1e-6)
        // Interior points
        assertEquals(10.0, c.timeAt(50.0), 1e-6)     // 0..100 segment
        assertEquals(40.0, c.timeAt(200.0), 1e-6)    // 100..300 segment
        assertEquals(85.0, c.timeAt(350.0), 1e-6)    // 300..400 segment
        assertEquals(125.0, c.timeAt(550.0), 1e-6)   // 400..700 segment
        assertEquals(170.0, c.timeAt(850.0), 1e-6)   // 700..1000 segment
        assertEquals(50.0, c.distanceAt(10.0), 1e-6)
        assertEquals(200.0, c.distanceAt(40.0), 1e-6)
        assertEquals(350.0, c.distanceAt(85.0), 1e-6)
        assertEquals(550.0, c.distanceAt(125.0), 1e-6)
        assertEquals(850.0, c.distanceAt(170.0), 1e-6)
    }

    @Test fun `flat-time stop segment preserves the old bracket behavior`() {
        // Distance strictly increases; time is flat between samples 1 and 2 (a stop).
        val c = GhostCurve(listOf(
            GhostSample(0.0, 0.0),
            GhostSample(50.0, 10.0),
            GhostSample(120.0, 10.0),
            GhostSample(200.0, 25.0),
        ))
        // distanceAt(10.0): first index with timeS >= 10 is sample 1 (50,10); bracket is [0,1],
        // not the flat segment, so it interpolates to the START of the flat region. This pins the
        // OLD indexOfFirst behavior exactly.
        assertEquals(50.0, c.distanceAt(10.0), 1e-6)
        // timeAt across the flat region returns the constant time of that segment.
        assertEquals(10.0, c.timeAt(100.0), 1e-6)   // bracket [50,10]..[120,10] → flat time 10
        // Beyond the flat region, normal interpolation resumes.
        assertEquals(17.5, c.timeAt(160.0), 1e-6)   // half of [120,10]..[200,25]
        assertEquals(160.0, c.distanceAt(17.5), 1e-6)
    }
}
