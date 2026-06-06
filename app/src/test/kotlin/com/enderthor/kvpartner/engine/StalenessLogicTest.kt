package com.enderthor.kvpartner.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessLogicTest {

    @Test fun `fresh distance is trustworthy regardless of speed`() {
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = true, speedMs = 10.0))
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = true, speedMs = 0.0))
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = true, speedMs = null))
    }

    @Test fun `frozen distance while stopped is trustworthy`() {
        // speed < 0.5 m/s → essentially stopped (e.g. at a red light) → frozen distance legitimate.
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 0.0))
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 0.49))
    }

    @Test fun `frozen distance while moving is not trustworthy`() {
        // speed >= 0.5 m/s with frozen distance → GPS lost while moving (tunnel) → not trustworthy.
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 0.5))
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 8.0))
    }

    @Test fun `frozen distance with unavailable speed is not trustworthy`() {
        // Cannot prove the rider is stopped → conservatively not trustworthy.
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = null))
    }
}
