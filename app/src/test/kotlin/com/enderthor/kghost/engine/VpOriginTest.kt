package com.enderthor.kghost.engine

import com.enderthor.kghost.extension.jsonForStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideContinuesTest {
    @Test fun `same epoch always continues`() {
        assertTrue(rideContinues(1L, 0.0, -1.0, epochNow = 1L, distNow = 0.0, elapsedNow = 0.0))
    }

    @Test fun `fresh process continues only with both clocks carrying on past the margin`() {
        assertTrue(rideContinues(1L, 4997.0, 3030.0, epochNow = 2L, distNow = 4847.0, elapsedNow = 3277.0))
        assertFalse(rideContinues(1L, 4997.0, 3030.0, epochNow = 2L, distNow = 40.0, elapsedNow = 10.0)) // new ride
        assertFalse(rideContinues(1L, 5000.0, 1000.0, epochNow = 2L, distNow = 5100.0, elapsedNow = 700.0)) // clock behind
        assertFalse(rideContinues(1L, 200.0, 50.0, epochNow = 2L, distNow = 250.0, elapsedNow = 60.0)) // inside margin
        assertFalse(rideContinues(1L, 4997.0, -1.0, epochNow = 2L, distNow = 4997.0, elapsedNow = 3277.0)) // no clock evidence
    }
}

class VpOriginTest {
    private val now = 10_000_000L
    private val maxAge = 6 * 60 * 60 * 1000L

    // Origin stamped at elapsed 100 s / 50 m; evidence last refreshed at 4997 m / 3030 s.
    private fun saved(epoch: Long = 1L, savedAt: Long = now - 60_000L, lastDist: Double = 4997.0, rideElapsed: Double = 3030.0) =
        VpOrigin(rideEpoch = epoch, originElapsedS = 100.0, startDistM = 50.0,
            lastDist = lastDist, rideElapsedS = rideElapsed, savedAtEpoch = savedAt)

    private fun start(saved: VpOrigin?, epoch: Long, elapsed: Double, dist: Double) =
        startVpOrigin(saved, epoch, elapsed, dist, now, maxAge)

    @Test fun `round-trips`() {
        val o = saved()
        assertEquals(o, jsonForStorage.decodeFromString(VpOrigin.serializer(), jsonForStorage.encodeToString(VpOrigin.serializer(), o)))
    }

    @Test fun `no saved origin starts fresh here`() {
        val o = start(null, 7L, elapsed = 12.0, dist = 3.0)
        assertEquals(12.0, o.originElapsedS, 0.0)
        assertEquals(3.0, o.startDistM, 0.0)
        assertEquals(7L, o.rideEpoch)
    }

    @Test fun `same epoch adopts`() {
        val o = start(saved(epoch = 7L), 7L, elapsed = 3100.0, dist = 5100.0)
        assertEquals(100.0, o.originElapsedS, 0.0)
        assertEquals(50.0, o.startDistM, 0.0)
    }

    @Test fun `fresh-process continuation adopts - field log e7fef4`() {
        val o = start(saved(), 2L, elapsed = 3277.0, dist = 4831.0)
        assertEquals(100.0, o.originElapsedS, 0.0)
        assertEquals(50.0, o.startDistM, 0.0)
        // Re-keyed to the new process's epoch, evidence refreshed to now.
        assertEquals(2L, o.rideEpoch)
        assertEquals(4831.0, o.lastDist, 0.0)
        assertEquals(3277.0, o.rideElapsedS, 0.0)
        assertEquals(now, o.savedAtEpoch)
    }

    @Test fun `stale age rejects`() {
        val o = start(saved(savedAt = now - maxAge - 1), 2L, elapsed = 3277.0, dist = 4831.0)
        assertEquals(3277.0, o.originElapsedS, 0.0)
        assertEquals(4831.0, o.startDistM, 0.0)
    }

    @Test fun `a saved-at in the future rejects`() {
        assertEquals(3277.0, start(saved(savedAt = now + 1), 2L, 3277.0, 4831.0).originElapsedS, 0.0)
    }

    @Test fun `new ride near 0 rejects`() {
        assertEquals(10.0, start(saved(), 2L, elapsed = 10.0, dist = 40.0).originElapsedS, 0.0)
    }

    @Test fun `odometer jump beyond the margin rejects`() {
        assertEquals(3277.0, start(saved(), 2L, elapsed = 3277.0, dist = 4997.0 + CHECKPOINT_RESUME_MARGIN_M + 1).originElapsedS, 0.0)
    }

    @Test fun `clock behind rejects`() {
        val behind = 3030.0 - CHECKPOINT_ELAPSED_SLACK_S - 1
        assertEquals(behind, start(saved(), 2L, elapsed = behind, dist = 4900.0).originElapsedS, 0.0)
    }

    @Test fun `route-only start then restart - refreshed evidence keeps continuity`() {
        // Origin stamped in route mode at the ride start (50 m, inside the margin); the rider then rides
        // 5 km on the route, the evidence refreshing every tick interval, then the process dies.
        val atStart = start(null, 1L, elapsed = 100.0, dist = 50.0)
        val refreshed = atStart.withEvidence(lastDist = 4997.0, rideElapsedS = 3030.0, savedAtEpoch = now - 5_000L)
        val resumed = start(refreshed, 2L, elapsed = 3277.0, dist = 4831.0)
        assertEquals(100.0, resumed.originElapsedS, 0.0)
        assertEquals(50.0, resumed.startDistM, 0.0)
        // Without the refresh the start-time evidence (50 m) can never pass the margin → whole ride as lead.
        assertEquals(3277.0, start(atStart, 2L, elapsed = 3277.0, dist = 4831.0).originElapsedS, 0.0)
    }

    /** The extension's stamping loop: on each moving tick, stamp once iff the sample is coherent. */
    private fun firstStamp(saved: VpOrigin?, epoch: Long, ticks: List<Pair<Double, Double>>): VpOrigin? {
        for ((dist, elapsed) in ticks) if (vpOriginCoherent(dist, elapsed)) return start(saved, epoch, elapsed, dist)
        return null
    }

    @Test fun `coherence - a distance the clock cannot explain is not a real state`() {
        assertFalse(vpOriginCoherent(5000.0, 0.0))
        assertFalse(vpOriginCoherent(40_000.0, 0.5))
        assertTrue(vpOriginCoherent(3.0, 1.5))
        assertTrue(vpOriginCoherent(CHECKPOINT_RESUME_MARGIN_M, 0.0))
        assertTrue(vpOriginCoherent(4831.0, 3277.0))
    }

    @Test fun `resume with a stale elapsed defers, then adopts the saved origin`() {
        // Fresh process: the first moving tick carries the whole-ride odometer at a not-yet-restored clock.
        val o = firstStamp(saved(), 2L, listOf(5000.0 to 0.0, 5005.0 to 3280.0))!!
        assertEquals(100.0, o.originElapsedS, 0.0)
        assertEquals(50.0, o.startDistM, 0.0)
    }

    @Test fun `replayed previous-ride odometer defers, then mints fresh at the corrected low odometer`() {
        val prevRide = saved(epoch = 1L, lastDist = 40_000.0, rideElapsed = 5000.0)
        val o = firstStamp(prevRide, 2L, listOf(40_000.0 to 0.5, 3.0 to 1.5))!!
        assertEquals(1.5, o.originElapsedS, 0.0)
        assertEquals(3.0, o.startDistM, 0.0)
        assertEquals(o, firstStamp(null, 2L, listOf(40_000.0 to 0.5, 3.0 to 1.5)))
    }

    @Test fun `VP gap measures distance from the start distance`() {
        val curve = GhostPaceSource(5.0).curve()
        // Adopted origin: 4781 m ridden over 3177 s at a 5 m/s target ≈ 15885 m → far BEHIND, not 4831 m AHEAD.
        val g = vpRaceGap(distM = 4831.0, startDistM = 50.0, raceElapsedS = 3177.0, curve = curve, fresh = true)
        assertEquals(4781.0, g.progressM, 1e-9)
        assertFalse(g.ahead)
    }
}
