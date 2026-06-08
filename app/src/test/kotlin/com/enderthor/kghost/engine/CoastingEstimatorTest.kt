package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoastingEstimatorTest {
    private fun newEstimator(coastWindowMs: Long = 30_000L) =
        CoastingEstimator(coastWindowMs = coastWindowMs)

    @Test fun `changing distance is LIVE and effective equals raw`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 1.0)
        assertEquals(110.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while stopped is a legit stop (LIVE, no coasting)`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 5.0, elapsedS = 0.0)   // moving, changing
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 1.0)   // frozen + stopped
        assertEquals(100.0, c.effectiveDistanceM, 1e-6) // raw (frozen), no extrapolation
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while moving within window coasts at last moving speed`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)  // moving at 10 m/s → remember speed
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)  // frozen + still moving, 3 s gap
        // 100 + 10 * 3 = 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.COASTING, c.quality)
        assertEquals(3.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while moving beyond window is LONG_LOSS but keeps coasting`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 40.0) // beyond the 30 s window
        assertEquals(CoastQuality.LONG_LOSS, c.quality)
        // Still dead-reckons — never blanks: 100 + 10 * 40 = 500
        assertEquals(500.0, c.effectiveDistanceM, 1e-6)
        assertEquals(40.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance with null speed still coasts at last moving speed`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0) // remember 10 m/s
        c.update(rawDistanceM = 100.0, speedMs = null, elapsedS = 2.0) // frozen, speed gone → still coast
        assertEquals(120.0, c.effectiveDistanceM, 1e-6) // 100 + 10 * 2
        assertEquals(CoastQuality.COASTING, c.quality)
    }

    @Test fun `resume after a gap returns to raw and LIVE with no lingering coast`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)  // coasting → 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        c.update(rawDistanceM = 140.0, speedMs = 10.0, elapsedS = 4.0)  // GPS back, new value
        assertEquals(140.0, c.effectiveDistanceM, 1e-6) // snaps to raw, no lingering coast
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `non-finite raw distance is ignored and previous state kept`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = Double.NaN, speedMs = 10.0, elapsedS = 1.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        c.update(rawDistanceM = Double.POSITIVE_INFINITY, speedMs = 10.0, elapsedS = 2.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
    }

    @Test fun `coasting uses the last MOVING speed not a later low speed`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)  // remember 10 m/s
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 2.0)  // coast 100 + 10*2 = 120
        assertEquals(120.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.COASTING, c.quality)
    }

    @Test fun `frozen at the start before any movement stays LIVE (no false GPS-loss)`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        // Stationary start line: first sample, then DISTANCE frozen at 0 with SPEED not yet emitting.
        c.update(rawDistanceM = 0.0, speedMs = null, elapsedS = 0.0)  // first call → LIVE
        c.update(rawDistanceM = 0.0, speedMs = null, elapsedS = 40.0) // frozen 40 s, never moved
        assertEquals(CoastQuality.LIVE, c.quality) // NOT LONG_LOSS → no false "GPS lost" alert
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `a pause (elapsed frozen) injects no phantom coast distance on resume`() {
        // Simulates a long café stop: DISTANCE stays frozen and ELAPSED_TIME is frozen by the ride
        // app during pause, so the resume tick sees a frozen distance at the SAME elapsedS as the last
        // change → zero coast gap, no phantom distance. (A wall-clock coast would inject lastSpeed ×
        // the whole pause duration.)
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0) // moving, anchor at t=50 s
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0) // resume: same elapsed, frozen dist
        assertEquals(100.0, c.effectiveDistanceM, 1e-6) // no phantom forward distance
        assertEquals(0.0, c.coastingSeconds, 1e-6)
        assertNotEquals(CoastQuality.LONG_LOSS, c.quality) // not a prolonged loss
    }
}
