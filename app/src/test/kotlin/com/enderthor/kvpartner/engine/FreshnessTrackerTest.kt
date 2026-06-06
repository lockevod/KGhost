package com.enderthor.kvpartner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTrackerTest {

    /** Mutable clock so tests can advance virtual time without sleeping. */
    private class FakeClock(var nowMs: Long = 0L) {
        fun read(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    @Test fun `never set returns null`() {
        val tracker = FreshnessTracker(staleThresholdMs = 3000, clock = { 0L })
        assertNull(tracker.freshValueOrNull())
    }

    @Test fun `a value change is fresh`() {
        val clock = FakeClock()
        val tracker = FreshnessTracker(staleThresholdMs = 3000, clock = clock::read)
        tracker.onValue(8.0)
        assertEquals(8.0, tracker.freshValueOrNull()!!, 1e-9)
    }

    @Test fun `repeated same value past threshold becomes null`() {
        val clock = FakeClock()
        val tracker = FreshnessTracker(staleThresholdMs = 3000, clock = clock::read)
        tracker.onValue(8.0)
        // Stream keeps re-emitting the SAME (frozen) value; no change resets the timer.
        clock.advance(1500); tracker.onValue(8.0)
        assertEquals(8.0, tracker.freshValueOrNull()!!, 1e-9) // still within threshold
        clock.advance(1600); tracker.onValue(8.0) // total 3100 ms since last CHANGE
        assertNull(tracker.freshValueOrNull()) // frozen past threshold → stale
    }

    @Test fun `recovery after staleness is fresh again`() {
        val clock = FakeClock()
        val tracker = FreshnessTracker(staleThresholdMs = 3000, clock = clock::read)
        tracker.onValue(8.0)
        clock.advance(4000); tracker.onValue(8.0) // frozen → stale
        assertNull(tracker.freshValueOrNull())
        // A genuine new value (signal recovered) resets the change timer → fresh again.
        tracker.onValue(2.0)
        assertEquals(2.0, tracker.freshValueOrNull()!!, 1e-9)
    }

    @Test fun `fresh zero is distinguishable from a frozen non-zero`() {
        // The core gap-engine case: a genuine stop emits a CHANGING then settled-but-fresh 0.0
        // (trustworthy), whereas a value frozen on GPS loss goes null (not trustworthy).
        val clock = FakeClock()
        val tracker = FreshnessTracker(staleThresholdMs = 3000, clock = clock::read)
        tracker.onValue(5.0)
        tracker.onValue(0.0) // changed to a genuine stop
        assertEquals(0.0, tracker.freshValueOrNull()!!, 1e-9)
    }
}
