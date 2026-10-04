package com.enderthor.kghost.engine

import com.enderthor.kghost.extension.jsonForStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostCheckpointTest {
    @Test fun `scalar state round-trips`() {
        val cp = GhostCheckpoint(rideEpoch = 123L, leadS = 42.5, lastRiderDist = 1000.0, pick = GhostPick.BEST, vpTimePerM = 0.3, savedAtEpoch = 999L, routeKey = "myroute_26000")
        val s = jsonForStorage.encodeToString(GhostCheckpoint.serializer(), cp)
        val back = jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), s)
        assertEquals(cp, back)
    }
}

class GhostCheckpointResumeTest {
    private fun cp(dist: Double, elapsed: Double = -1.0) =
        GhostCheckpoint(rideEpoch = 1L, leadS = 132.0, lastRiderDist = dist, pick = GhostPick.BEST,
            vpTimePerM = 0.3, savedAtEpoch = 0L, routeKey = "r", rideElapsedS = elapsed)
    private val freshEpoch = 2L

    @Test fun `a ride-app restart resumes - field log e7fef4`() {
        // Odometer came back 150 m short, ride clock carried on.
        assertTrue(cp(4997.0, 3030.0).continuesRide(freshEpoch, riderDistNow = 4847.0, elapsedNowS = 3277.0))
        assertTrue(cp(9771.0, 4449.0).continuesRide(freshEpoch, riderDistNow = 9634.0, elapsedNowS = 4587.0))
    }

    @Test fun `a new ride does not inherit a ride that ended while KGhost was dead`() {
        // Same route reloaded at the start of a new ride: clock and odometer restart from zero.
        assertFalse(cp(4997.0, 3030.0).continuesRide(freshEpoch, riderDistNow = 40.0, elapsedNowS = 10.0))
        // Codex's case: an abandoned ride cut at 200 m — inside the margin, never a fresh-process resume.
        assertFalse(cp(200.0, 50.0).continuesRide(freshEpoch, riderDistNow = 40.0, elapsedNowS = 60.0))
        // Route loaded mid-new-ride near the old cut, but the new ride's clock is behind the old one.
        assertFalse(cp(5000.0, 1000.0).continuesRide(freshEpoch, riderDistNow = 5100.0, elapsedNowS = 700.0))
        // Codex's boundary case: a cut just past the margin vs a new ride's first metres, clock within slack.
        assertFalse(cp(310.0, 35.0).continuesRide(freshEpoch, riderDistNow = 15.0, elapsedNowS = 10.0))
    }

    @Test fun `the ride clock may come back at most the slack behind`() {
        val c = cp(5000.0, 1000.0)
        assertTrue(c.continuesRide(freshEpoch, riderDistNow = 4900.0, elapsedNowS = 1000.0 - CHECKPOINT_ELAPSED_SLACK_S))
        assertFalse(c.continuesRide(freshEpoch, riderDistNow = 4900.0, elapsedNowS = 1000.0 - CHECKPOINT_ELAPSED_SLACK_S - 1.0))
    }

    @Test fun `a checkpoint without a ride clock only resumes in its own process`() {
        assertFalse(cp(4997.0).continuesRide(freshEpoch, riderDistNow = 4847.0, elapsedNowS = 3277.0))
        assertTrue(cp(4997.0).continuesRide(rideEpoch = 1L, riderDistNow = 0.0, elapsedNowS = 0.0))
    }

    @Test fun `an old checkpoint without the field still decodes`() {
        val legacy = """{"rideEpoch":1,"leadS":5.0,"lastRiderDist":900.0,"pick":"BEST","vpTimePerM":0.3,"savedAtEpoch":0,"routeKey":"r"}"""
        assertEquals(-1.0, jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), legacy).rideElapsedS, 0.0)
    }
}

class GhostCheckpointComparatorTest {
    private fun cp(comparator: RaceComparator?, seen: Boolean = false) =
        GhostCheckpoint(rideEpoch = 1L, leadS = 30.0, lastRiderDist = 5000.0, pick = GhostPick.LAST,
            vpTimePerM = 0.3, savedAtEpoch = 0L, routeKey = "r", rideElapsedS = 900.0,
            comparator = comparator, historyVerdictSeen = seen)

    @Test fun `a legacy blob decodes but is not restorable into either race`() {
        // Written by 1.2.x: no comparator fields. Its lead may be target-earned (1.2.0 tier 4).
        val legacy = """{"rideEpoch":1,"leadS":30.0,"lastRiderDist":5000.0,"pick":"LAST","vpTimePerM":0.3,""" +
            """"savedAtEpoch":0,"routeKey":"r","rideElapsedS":900.0}"""
        val cp = jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), legacy)
        assertEquals(null, cp.comparator)
        assertFalse(cp.historyVerdictSeen)
        // A legacy lead can't prove which comparator earned it: no resume even with every other gate passing.
        assertEquals(null, cp.resumeComparator(otherGatesPass = true))
    }

    @Test fun `comparator and verdict latch round-trip`() {
        val cp = cp(RaceComparator.TARGET, seen = true)
        val back = jsonForStorage.decodeFromString(GhostCheckpoint.serializer(),
            jsonForStorage.encodeToString(GhostCheckpoint.serializer(), cp))
        assertEquals(cp, back)
        assertEquals(RaceComparator.TARGET, back.resumeComparator(otherGatesPass = true))
    }

    // The tick's builder must carry the race's comparator and latch, or every resume is rejected.
    @Test fun `a checkpoint built by the race resumes the same race`() {
        val built = raceCheckpoint(rideEpoch = 1L, leadS = 0.0, lastRiderDist = 5000.0, pick = GhostPick.LAST,
            vpTimePerM = 0.3, savedAtEpoch = 0L, routeKey = "r", rideElapsedS = 900.0,
            comparator = RaceComparator.HISTORY, historyVerdictSeen = true)
        val back = jsonForStorage.decodeFromString(GhostCheckpoint.serializer(),
            jsonForStorage.encodeToString(GhostCheckpoint.serializer(), built))
        assertEquals(RaceComparator.HISTORY, back.resumeComparator(otherGatesPass = true))
        assertTrue(back.historyVerdictSeen)
    }

    // The resumed race keeps the comparator that EARNED the lead, whatever the current route is classified
    // as: a TARGET race rerouted onto a HISTORY route, then restarted, resumes as TARGET with its lead.
    @Test fun `a same-ride checkpoint resumes with its own comparator`() {
        assertEquals(RaceComparator.TARGET, cp(RaceComparator.TARGET).resumeComparator(otherGatesPass = true))
        assertEquals(RaceComparator.HISTORY, cp(RaceComparator.HISTORY).resumeComparator(otherGatesPass = true))
    }

    @Test fun `a checkpoint failing any other gate does not resume`() {
        assertEquals(null, cp(RaceComparator.TARGET).resumeComparator(otherGatesPass = false))
        assertEquals(null, cp(RaceComparator.HISTORY).resumeComparator(otherGatesPass = false))
    }
}
