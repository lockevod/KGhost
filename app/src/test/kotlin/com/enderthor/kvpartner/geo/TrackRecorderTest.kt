package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackRecorderTest {

    @Test fun `build returns the decimated subset of fed samples`() {
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))

        rec.onSample(40.0, -3.0, 0.0, 0.0)    // kept (first)
        rec.onSample(40.0, -3.0, 5.0, 1.0)    // dropped (< 20 m)
        rec.onSample(40.0, -3.0, 19.0, 2.0)   // dropped (< 20 m)
        rec.onSample(40.0, -3.0, 25.0, 3.0)   // kept (≥ 20 m)
        rec.onSample(40.0, -3.0, 30.0, 4.0)   // dropped (< 20 m from last kept)
        rec.onSample(40.0, -3.0, 50.0, 5.0)   // kept (≥ 20 m)

        val track = rec.build(id = "T1", startedAtEpoch = 7_000L)!!
        assertEquals("T1", track.id)
        assertEquals(7_000L, track.startedAtEpoch)
        assertEquals(listOf(0.0, 25.0, 50.0), track.points.map { it.distanceM })
        assertEquals(3, rec.size())
    }

    @Test fun `build returns null with fewer than two kept points`() {
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)   // 1 kept
        rec.onSample(40.0, -3.0, 5.0, 1.0)   // dropped
        assertNull(rec.build(id = "T1", startedAtEpoch = 1L))
    }

    @Test fun `reset clears buffer and decimator state`() {
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)
        rec.onSample(40.0, -3.0, 25.0, 1.0)
        assertEquals(2, rec.size())

        rec.reset()
        assertEquals(0, rec.size())
        assertNull(rec.build(id = "T1", startedAtEpoch = 1L))

        // After reset the decimator must treat the next sample as the new "first" (always kept).
        rec.onSample(40.0, -3.0, 1000.0, 2.0)
        assertEquals(1, rec.size())
    }
}
