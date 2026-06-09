package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHygieneTwinTest {

    // A straight west→east path near (41.0, 2.0), ~5 km long (40 × 125 m steps).
    private fun ride(id: String, epoch: Long, lastTimeS: Double, dLat: Double = 0.0, dLng: Double = 0.0): RecordedTrack {
        val pts = (0..40).map { i ->
            TrackPointDto(
                lat = 41.0 + dLat,
                lng = 2.0 + dLng + i * 0.0015,
                distanceM = i * 125.0,
                timeS = lastTimeS * (i / 40.0),
            )
        }
        return RecordedTrack(id = id, startedAtEpoch = epoch, points = pts)
    }

    @Test fun `trackMetaOf fills cells, fingerprint, totals`() {
        val m = trackMetaOf(ride("a", 1000L, 600.0))
        assertTrue(m.fineCells.isNotEmpty())
        assertTrue("dilated is a superset of fine", m.fineCells.all { it in m.dilatedCells })
        assertEquals(3, m.dirFingerprint.size)
        assertEquals(5000.0, m.totalDistanceM, 1e-6)   // 40 * 125
        assertEquals(600.0, m.totalTimeS!!, 1e-6)
        assertEquals(1000L, m.startedAtEpoch)
    }

    @Test fun `identical paths are twins`() {
        assertTrue(areTwins(trackMetaOf(ride("a", 1L, 600.0)), trackMetaOf(ride("b", 2L, 590.0))))
    }

    @Test fun `same road with consumer-GPS jitter (plus minus 4 m) still groups as twins`() {
        // THE regression for the show-stopper: raw set-Jaccard at precision 8 fails at ±4 m noise
        // (~0.82 < 0.90); the dilated mutual-containment must still recognise the same road.
        fun jitteredLap(id: String, epoch: Long, seed: Long): RecordedTrack {
            val rnd = java.util.Random(seed)
            val pts = (0..200).map { i ->
                val latJitter = (rnd.nextDouble() - 0.5) * 2.0 * (4.0 / 111_320.0) // ±4 m N/S of the line
                TrackPointDto(41.0 + latJitter, 2.0 + i * 0.0006, i * 50.0, 600.0 * (i / 200.0)) // ~10 km E
            }
            return RecordedTrack(id, epoch, pts)
        }
        assertTrue(areTwins(trackMetaOf(jitteredLap("a", 1L, 11L)), trackMetaOf(jitteredLap("b", 2L, 9973L))))
    }

    @Test fun `a far-apart parallel path is not a twin (fine footprint differs)`() {
        // +0.01 deg latitude ≈ 1.1 km north — disjoint cells AND fingerprint points > FP_TOL_M apart.
        assertFalse(areTwins(trackMetaOf(ride("a", 1L, 600.0)), trackMetaOf(ride("b", 2L, 600.0, dLat = 0.01))))
    }

    @Test fun `a length outlier is not a twin`() {
        val short = trackMetaOf(ride("a", 1L, 600.0))
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
