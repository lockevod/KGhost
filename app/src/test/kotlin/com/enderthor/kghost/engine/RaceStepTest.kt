package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceStepTest {
    private class Rig(val cmp: RaceComparator = RaceComparator.HISTORY) {
        val st = RaceStepState().apply { moveStartS = 0.0 }
        val coast = CoastingEstimator()
        val integ = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.3, decimateM = 20.0)

        /** Feeds coast then steps the race; returns the result. */
        fun tick(
            raw: Double, t: Double, speed: Double? = 5.0, fixOk: Boolean = true, gradeFresh: Boolean = true,
            hist: Double? = null, grade: () -> Double? = { null }, target: Double = 5.0,
        ): RaceStepResult {
            coast.update(raw, speed, t)
            return advanceRace(
                st, integ, cmp, coast, coast.effectiveDistanceM, t, speed, 0.0, 0.0, 0.0,
                fixOk, gradeFresh, target, { hist }, grade,
            )
        }
    }

    @Test fun `freezes the clock while stopped and settled`() {
        val r = Rig()
        r.tick(0.0, 0.0); r.tick(50.0, 10.0, hist = 0.2)
        val gap = r.integ.gapTimeS
        val ms = r.st.moveStartS!!
        r.tick(50.0, 20.0, speed = 0.0, hist = 0.2)
        assertEquals(ms + 10.0, r.st.moveStartS!!, 1e-9)
        assertEquals(gap, r.integ.gapTimeS, 1e-9)
    }

    @Test fun `a pending sample does not freeze the clock`() {
        val r = Rig()
        r.tick(0.0, 0.0); r.tick(50.0, 10.0, hist = 0.2)
        r.tick(50.0, 12.0, speed = 5.0, hist = 0.2)
        assertTrue(r.coast.pendingSample)
        assertEquals(0.0, r.st.moveStartS!!, 1e-9)
    }

    @Test fun `pendingHold skips onTick after the first tick`() {
        val r = Rig()
        r.tick(0.0, 0.0); r.tick(50.0, 10.0, hist = 0.2)
        val ghost = r.integ.ghostTime
        val last = r.st.lastRiderDist
        val res = r.tick(50.0, 12.0, speed = 5.0, hist = 0.2)
        assertTrue(r.coast.pendingHold)
        assertFalse(res.ticked)
        assertNull(res.pace)
        assertEquals(ghost, r.integ.ghostTime, 0.0)
        assertEquals(last, r.st.lastRiderDist, 0.0)
    }

    @Test fun `stale gradient with a fresh fix never consults gradePace`() {
        val r = Rig()
        r.tick(0.0, 0.0)
        val res = r.tick(50.0, 10.0, gradeFresh = false, grade = { error("gradePace consulted") })
        assertEquals("GP", res.tag)
        assertNull(res.pace)
    }

    @Test fun `stale fix gives a neutral step`() {
        val r = Rig()
        r.tick(0.0, 0.0)
        val matched = r.integ.matchedM
        val res = r.tick(50.0, 10.0, fixOk = false, hist = 0.1)
        assertEquals("GP", res.tag)
        assertNull(res.pace)
        assertEquals(matched, r.integ.matchedM, 0.0)
    }

    @Test fun `HISTORY latches the verdict only when matched metres rise`() {
        val r = Rig()
        r.tick(0.0, 0.0, hist = 0.2)
        assertFalse(r.st.historyVerdictSeen)
        val res = r.tick(50.0, 10.0, hist = 0.2)
        assertEquals("SEG", res.tag)
        assertTrue(r.st.historyVerdictSeen)
    }

    @Test fun `TARGET tags TGT and charges the target`() {
        val r = Rig(RaceComparator.TARGET)
        r.tick(0.0, 0.0)
        val res = r.tick(50.0, 10.0, target = 5.0)
        assertEquals("TGT", res.tag)
        assertEquals(0.0, r.integ.gapTimeS, 0.01)
    }

    @Test fun `ticked is true normally and pace is null under a stale fix`() {
        val r = Rig()
        assertTrue(r.tick(0.0, 0.0).ticked)
        val res = r.tick(50.0, 10.0, fixOk = false, hist = 0.1)
        assertTrue(res.ticked)
        assertNull(res.pace)
    }
}
