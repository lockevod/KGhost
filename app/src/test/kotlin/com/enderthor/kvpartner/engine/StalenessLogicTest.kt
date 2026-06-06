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

    // The caller now passes a FRESHNESS-GATED speed (freshValueOrNull), not the raw SPEED reading.
    // A stale/frozen speed arrives here as null, so the gate cannot mistake a frozen speed for a
    // genuine stop. These cases assert that distinction at the logic boundary.

    @Test fun `frozen distance with fresh genuine-stop speed is trustworthy`() {
        // Genuine stop with healthy GPS → SPEED is fresh 0.0 → trustworthy → gap stays visible.
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 0.0))
    }

    @Test fun `frozen distance with stale frozen speed (null) is not trustworthy`() {
        // GPS lost while moving → SPEED freezes (last-known-value) → freshValueOrNull() returns
        // null → must NOT be treated as a stop → blank to `---`.
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = null))
    }
}
