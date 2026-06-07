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

    @Test fun `build keeps the true ride endpoint even when it falls below decimation spacing`() {
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))

        // With 20 m spacing the decimator keeps 0, 20, 40 and DROPS the final 45 m sample
        // (only 5 m past the last kept point). The recorded track must still end at 45 m,
        // otherwise the ride is truncated by up to ~20 m and the dedup sourceKey is unstable.
        rec.onSample(40.0, -3.0, 0.0, 0.0)    // kept (first)
        rec.onSample(40.0, -3.0, 20.0, 1.0)   // kept (>= 20 m)
        rec.onSample(40.0, -3.0, 40.0, 2.0)   // kept (>= 20 m)
        rec.onSample(40.0, -3.0, 45.0, 3.0)   // dropped by decimator, but is the true endpoint

        val track = rec.build(id = "T1", startedAtEpoch = 7_000L)!!
        assertEquals(45.0, track.points.last().distanceM, 0.0)
        assertEquals(listOf(0.0, 20.0, 40.0, 45.0), track.points.map { it.distanceM })
    }

    @Test fun `build does not duplicate the endpoint when the last fed sample was kept`() {
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)    // kept
        rec.onSample(40.0, -3.0, 25.0, 1.0)   // kept (also the last fed sample)

        val track = rec.build(id = "T1", startedAtEpoch = 1L)!!
        assertEquals(listOf(0.0, 25.0), track.points.map { it.distanceM })
    }

    @Test fun `build returns null with fewer than two kept points`() {
        // A single distinct sample cannot form a comparable segment. (Note: with the endpoint fix,
        // feeding two distinct distances always yields >= 2 points, since the true endpoint is kept
        // even when the decimator would drop it.)
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)   // 1 kept (and also the only fed sample)
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
