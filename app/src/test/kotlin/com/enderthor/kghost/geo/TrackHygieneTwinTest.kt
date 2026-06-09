package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHygieneTwinTest {

    // A straight west→east path near (41.0, 2.0), ~5 km long so the 25/50/75 % fingerprint points
    // land in DISTINCT precision-6 (~1.2 km) cells — needed to tell direction apart. (Real routes are
    // always long enough; a 1 km synthetic path is too short for the coarse fingerprint.)
    private fun ride(id: String, epoch: Long, lastTimeS: Double, dLat: Double = 0.0, dLng: Double = 0.0): RecordedTrack {
        val pts = (0..40).map { i ->
            TrackPointDto(
                lat = 41.0 + dLat,
                lng = 2.0 + dLng + i * 0.0015,            // ~125 m steps eastbound, ~5 km total
                distanceM = i * 125.0,
                timeS = lastTimeS * (i / 40.0),
            )
        }
        return RecordedTrack(id = id, startedAtEpoch = epoch, points = pts)
    }

    @Test fun `trackMetaOf fills cells, fingerprint, totals`() {
        val m = trackMetaOf(ride("a", 1000L, 600.0))
        assertTrue(m.fineCells.isNotEmpty())
        assertEquals(3, m.dirFingerprint.size)
        assertEquals(5000.0, m.totalDistanceM, 1e-6)   // 40 * 125
        assertEquals(600.0, m.totalTimeS!!, 1e-6)
        assertEquals(1000L, m.startedAtEpoch)
    }

    @Test fun `identical paths are twins`() {
        assertTrue(areTwins(trackMetaOf(ride("a", 1L, 600.0)), trackMetaOf(ride("b", 2L, 590.0))))
    }

    @Test fun `a far-apart parallel path is not a twin (fine footprint differs)`() {
        // +0.01 deg latitude ≈ 1.1 km north — entirely different fine cells.
        assertFalse(areTwins(trackMetaOf(ride("a", 1L, 600.0)), trackMetaOf(ride("b", 2L, 600.0, dLat = 0.01))))
    }

    @Test fun `a length outlier is not a twin`() {
        val short = trackMetaOf(ride("a", 1L, 600.0))
        // same start/step, but 60 points instead of 40 → +50% length
        val longPts = (0..60).map { i -> TrackPointDto(41.0, 2.0 + i * 0.0015, i * 125.0, 600.0 * (i / 60.0)) }
        val longer = trackMetaOf(RecordedTrack("b", 2L, longPts))
        assertFalse(areTwins(short, longer))
    }

    @Test fun `a reverse ride is not a twin (direction fingerprint differs)`() {
        val fwd = trackMetaOf(ride("a", 1L, 600.0))
        val revPts = (0..40).map { i -> TrackPointDto(41.0, 2.0 + (40 - i) * 0.0015, i * 125.0, 600.0 * (i / 40.0)) }
        val rev = trackMetaOf(RecordedTrack("b", 2L, revPts))
        assertFalse(areTwins(fwd, rev))
    }
}
