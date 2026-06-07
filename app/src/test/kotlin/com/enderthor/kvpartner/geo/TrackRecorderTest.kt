package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.import_.HistoryImporter
import com.enderthor.kvpartner.import_.sourceKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun `build does not append a backward end-of-ride glitch endpoint`() {
        // A final end-of-ride GPS glitch reports a SMALLER cumulative distance than the last kept
        // point. The endpoint must NOT be appended (it would be non-monotonic/malformed); the built
        // track simply ends at the last kept point and does not crash.
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)    // kept (first)
        rec.onSample(40.0, -3.0, 20.0, 1.0)   // kept (>= 20 m)
        rec.onSample(40.0, -3.0, 40.0, 2.0)   // kept (>= 20 m)
        rec.onSample(40.0, -3.0, 35.0, 3.0)   // backward glitch: dropped by decimator, NOT appended

        val track = rec.build(id = "T1", startedAtEpoch = 7_000L)!!
        assertEquals(40.0, track.points.last().distanceM, 0.0)
        assertEquals(listOf(0.0, 20.0, 40.0), track.points.map { it.distanceM })
    }

    @Test fun `build returns null with fewer than two kept points`() {
        // A single distinct sample cannot form a comparable segment. (Note: with the endpoint fix,
        // feeding two distinct distances always yields >= 2 points, since the true endpoint is kept
        // even when the decimator would drop it.)
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        rec.onSample(40.0, -3.0, 0.0, 0.0)   // 1 kept (and also the only fed sample)
        assertNull(rec.build(id = "T1", startedAtEpoch = 1L))
    }

    @Test fun `cross-source dedup key parity - recorder and importer agree for the same ride`() {
        // The dedup design requires the SAME ride to yield the SAME sourceKey whether it is live-
        // recorded by ② (TrackRecorder.build) or scanned/imported by ③ (HistoryImporter.
        // defaultDecimate). Both must key off the DECIMATED tail. This test locks that ②/③ symmetry.
        //
        // One synthetic ride: 52 dense samples every ~5 m up to ~252 m, so a TrackDecimator(20.0)
        // keeps 0, 20, 40, ... 240 (decimated tail = 240 m, bucket 24) while the true endpoint is
        // ~252 m (bucket 25). If ② keys off the TRUE endpoint it lands in a different 10 m bucket
        // than ③ → dedup fails → duplicate track. The keys MUST match.
        val startedAt = 1_700_000_000_000L
        val raw = (0..50).map { i -> Triple(i * 5.0, i.toDouble(), i) } // (distanceM, timeS, idx)
            .map { (d, t, _) -> Sample(lat = 40.0, lng = -3.0, distanceM = d, timeS = t) }
            .plus(Sample(lat = 40.0, lng = -3.0, distanceM = 252.0, timeS = 51.0)) // true endpoint

        // ② path: feed every raw sample through a TrackRecorder, then build().
        val rec = TrackRecorder(TrackDecimator(minSpacingM = 20.0))
        raw.forEach { rec.onSample(it.lat, it.lng, it.distanceM, it.timeS) }
        val recorded = rec.build(id = "ride", startedAtEpoch = startedAt)!!

        // ③ path: build a RecordedTrack from the SAME raw samples (as DTOs), then defaultDecimate().
        val scanned = RecordedTrack(
            id = "scan",
            startedAtEpoch = startedAt,
            points = raw.map { TrackPointDto(it.lat, it.lng, it.distanceM, it.timeS) },
            sourceKey = sourceKeyOf(startedAt, 252.0), // raw-keyed, as FitDecoder/GpxParser produce
            source = Source.FITFILES_SCAN,
        )
        val importedDecimated = HistoryImporter.defaultDecimate(scanned)

        // Guard: keying off the TRUE endpoint (252 m, bucket 25) MUST differ from the decimated tail
        // (240 m, bucket 24); otherwise this test would pass even on the broken code and prove nothing.
        assertNotEquals(
            sourceKeyOf(startedAt, 252.0),
            sourceKeyOf(startedAt, 240.0),
        )

        // The invariant: ②'s and ③'s sourceKeys are EQUAL (both key off the decimated tail, 240 m).
        assertEquals(importedDecimated.sourceKey, recorded.sourceKey)

        // And the endpoint is still preserved in ②'s points (track accuracy is not sacrificed).
        assertEquals(252.0, recorded.points.last().distanceM, 0.0)
    }

    /** Local sample holder for the parity test. */
    private data class Sample(val lat: Double, val lng: Double, val distanceM: Double, val timeS: Double)

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
