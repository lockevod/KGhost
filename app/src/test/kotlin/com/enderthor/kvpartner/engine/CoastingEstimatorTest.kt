package com.enderthor.kvpartner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoastingEstimatorTest {
    private var now = 1_000L
    private fun clock() = now
    private fun newEstimator(graceMs: Long = 8_000L) =
        CoastingEstimator(graceMs = graceMs, clock = ::clock)

    @Test fun `changing distance is trustworthy and effective equals raw`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
        now += 1000
        c.update(rawDistanceM = 110.0, speedMs = 10.0)
        assertEquals(110.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
    }

    @Test fun `frozen distance while stopped is a legit stop (no coasting)`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 5.0)   // moving, changing
        now += 1000
        c.update(rawDistanceM = 100.0, speedMs = 0.0)   // frozen + stopped
        assertEquals(100.0, c.effectiveDistanceM, 1e-6) // raw (frozen), no extrapolation
        assertTrue(c.trustworthy)
    }

    @Test fun `frozen distance while moving within grace coasts at last moving speed`() {
        val c = newEstimator(graceMs = 8_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0)  // moving at 10 m/s, changing → remember speed
        now += 3000                                     // 3 s GPS gap
        c.update(rawDistanceM = 100.0, speedMs = 10.0)  // frozen + still moving
        // 100 + 10 * 3 = 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
    }

    @Test fun `frozen distance while moving beyond grace is not trustworthy`() {
        val c = newEstimator(graceMs = 8_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        now += 9000                                     // beyond the 8 s grace window
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        assertFalse(c.trustworthy)
    }

    @Test fun `frozen distance with null speed is not trustworthy`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        now += 1000
        c.update(rawDistanceM = 100.0, speedMs = null)  // frozen, can't prove stopped, can't coast
        assertFalse(c.trustworthy)
    }

    @Test fun `resume after a gap returns to raw and trustworthy with no lingering coast`() {
        val c = newEstimator(graceMs = 8_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        now += 3000
        c.update(rawDistanceM = 100.0, speedMs = 10.0)  // coasting → 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        now += 1000
        c.update(rawDistanceM = 140.0, speedMs = 10.0)  // GPS back, new value
        assertEquals(140.0, c.effectiveDistanceM, 1e-6) // snaps to raw, no lingering coast
        assertTrue(c.trustworthy)
    }

    @Test fun `non-finite raw distance is ignored and previous state kept`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0)
        c.update(rawDistanceM = Double.NaN, speedMs = 10.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
        c.update(rawDistanceM = Double.POSITIVE_INFINITY, speedMs = 10.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
    }

    @Test fun `coasting uses the last MOVING speed not a later low speed`() {
        val c = newEstimator(graceMs = 8_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0)  // remember 10 m/s
        now += 2000
        // frozen but a low (but >0) freeze of speed should still not be < min if it's 10.
        c.update(rawDistanceM = 100.0, speedMs = 10.0)  // coast 100 + 10*2 = 120
        assertEquals(120.0, c.effectiveDistanceM, 1e-6)
        assertTrue(c.trustworthy)
    }
}
