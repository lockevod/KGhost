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
        // Speed stream not yet emitted (or non-finite) → null → cannot prove the rider is stopped
        // → conservatively not trustworthy.
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = null))
    }

    // The caller passes the RAW SPEED magnitude (no value-change freshness wrapping). A genuinely
    // constant speed — e.g. a steady 0.0 after stopping at a light — must still classify as stopped;
    // value-change freshness would wrongly age it out and blank a valid gap. These cases assert the
    // magnitude-based distinction at the logic boundary.

    @Test fun `frozen distance with genuine-stop speed magnitude is trustworthy`() {
        // Genuine stop → raw SPEED is 0.0 (< 0.5) → trustworthy → gap stays visible, even when the
        // 0.0 has been constant for a long time (value-change freshness would have failed here).
        assertTrue(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 0.0))
    }

    @Test fun `frozen distance with frozen-high speed magnitude is not trustworthy`() {
        // GPS lost while moving → SPEED freezes at its last (high) value ≥ 0.5 → reads as moving →
        // must NOT be treated as a stop → blank to `---`.
        assertFalse(StalenessLogic.isTrustworthy(distanceFresh = false, speedMs = 8.0))
    }
}
