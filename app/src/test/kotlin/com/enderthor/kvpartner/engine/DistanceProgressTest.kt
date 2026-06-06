package com.enderthor.kvpartner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceProgressTest {
    private var now = 1_000L
    private fun clock() = now
    private fun newProgress() = DistanceProgress(staleThresholdMs = 3000, clock = ::clock)

    @Test fun `progressM reflects the last received value`() {
        val p = newProgress()
        p.onDistance(123.0)
        assertEquals(123.0, p.progressM, 1e-6)
    }

    @Test fun `fresh while the value changes within the threshold`() {
        val p = newProgress()
        p.onDistance(10.0)
        now += 1000
        p.onDistance(20.0)
        assertTrue(p.isFresh)
    }

    @Test fun `stale when the value does not change beyond the threshold (last known value)`() {
        val p = newProgress()
        p.onDistance(50.0)
        now += 1000; p.onDistance(50.0)   // same value: GPS frozen
        now += 1000; p.onDistance(50.0)
        now += 2000; p.onDistance(50.0)   // 4 s without a real change
        assertFalse(p.isFresh)
    }

    @Test fun `returns to fresh when the value changes again`() {
        val p = newProgress()
        p.onDistance(50.0)
        now += 5000; p.onDistance(50.0)
        assertFalse(p.isFresh)
        p.onDistance(60.0)
        assertTrue(p.isFresh)
    }
}
