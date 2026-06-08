package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapCalculatorTest {
    // Ghost at a constant 5 m/s.
    private val curve = GhostPaceSource(5.0).curve()

    @Test fun `you are ahead when you cover more distance than the ghost in the same time`() {
        // At 100 s the ghost has covered 500 m; you have covered 600 m → ahead.
        val s = GapCalculator.compute(progressM = 600.0, elapsedS = 100.0, curve = curve, fresh = true)
        assertTrue(s.ahead)
        assertEquals(-20.0, s.gapTimeS, 1e-6)      // your time (100) - ghost time at 600m (120) = -20
        assertEquals(100.0, s.gapDistanceM, 1e-6)  // 600 - 500
        assertEquals(500.0, s.ghostProgressM, 1e-6)
        assertFalse(s.estimated)
        assertTrue(s.active)
    }

    @Test fun `you are behind when you cover less distance than the ghost`() {
        val s = GapCalculator.compute(progressM = 400.0, elapsedS = 100.0, curve = curve, fresh = true)
        assertFalse(s.ahead)
        assertEquals(20.0, s.gapTimeS, 1e-6)       // 100 - 80
        assertEquals(-100.0, s.gapDistanceM, 1e-6) // 400 - 500
    }

    @Test fun `fresh false marks state as estimated`() {
        val s = GapCalculator.compute(progressM = 400.0, elapsedS = 100.0, curve = curve, fresh = false)
        assertTrue(s.estimated)
    }

    @Test fun `inactive helper returns inactive state`() {
        val s = GapState.inactive()
        assertFalse(s.active)
    }

    @Test fun `non-finite progress returns an inactive state`() {
        assertFalse(GapCalculator.compute(Double.NaN, 100.0, curve, true).active)
        assertFalse(GapCalculator.compute(Double.POSITIVE_INFINITY, 100.0, curve, true).active)
    }

    @Test fun `non-finite elapsed returns an inactive state`() {
        assertFalse(GapCalculator.compute(600.0, Double.NaN, curve, true).active)
        assertFalse(GapCalculator.compute(600.0, Double.NEGATIVE_INFINITY, curve, true).active)
    }
}
