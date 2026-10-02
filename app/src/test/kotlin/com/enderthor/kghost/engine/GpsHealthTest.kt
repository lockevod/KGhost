package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsHealthTest {
    private fun s(x: Int) = x * 1000L

    @Test fun `nothing is judged before the race starts`() {
        val h = GpsHealth(60.0)
        assertFalse(h.update(s(600), lastFixMs = s(1), paused = false)) // an old fix, but no race yet
        assertNull(h.ageS(s(600), null))
    }

    // Indoor/trainer on a wheel sensor: the race starts, no fix ever arrives — there is no GPS to lose.
    @Test fun `a ride that never had a trusted fix never alerts`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(100))
        assertFalse(h.update(s(400), null, false))
        h.onResume(s(500))
        assertFalse(h.update(s(900), null, false))
    }

    @Test fun `a pre-race fix then silence fires once 60 s into the race`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(100))
        assertFalse(h.update(s(159), lastFixMs = s(20), paused = false))
        assertTrue(h.update(s(160), lastFixMs = s(20), paused = false))
        assertFalse(h.update(s(400), lastFixMs = s(20), paused = false)) // one alert per episode
    }

    // The resume grace must not re-arm: a loss that spans a pause is still ONE loss.
    @Test fun `a loss spanning a pause alerts once`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertTrue(h.update(s(100), lastFixMs = s(30), paused = false))
        h.update(s(200), lastFixMs = s(30), paused = true)
        h.onResume(s(300))
        assertFalse(h.update(s(301), lastFixMs = s(30), paused = false)) // grace: age 1 s, but no re-arm
        assertFalse(h.update(s(400), lastFixMs = s(30), paused = false)) // same loss, still no second alert
    }

    // The field case: fixes flowing, then they stop for good mid-ride.
    @Test fun `fixes stop mid-ride - fires 60 s after the last one, re-arms only once recovered`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertFalse(h.update(s(500), lastFixMs = s(499), paused = false))
        assertTrue(h.update(s(560), lastFixMs = s(500), paused = false))
        assertFalse(h.update(s(580), lastFixMs = s(551), paused = false)) // age 29 < 30 → re-armed, no fire
        assertTrue(h.update(s(700), lastFixMs = s(551), paused = false))  // a second loss alerts again
    }

    @Test fun `a fix inside the hysteresis band does not re-arm`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertTrue(h.update(s(60), lastFixMs = s(0), paused = false))
        assertFalse(h.update(s(100), lastFixMs = s(60), paused = false)) // age 40: still the same episode
        assertFalse(h.update(s(125), lastFixMs = s(60), paused = false)) // 65 s again, but never re-armed
    }

    @Test fun `paused never fires, and a resume grants a fresh grace`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertFalse(h.update(s(900), lastFixMs = s(10), paused = true))
        h.onResume(s(900))
        assertFalse(h.update(s(959), lastFixMs = s(10), paused = false))
        assertTrue(h.update(s(960), lastFixMs = s(10), paused = false))
    }

    @Test fun `a parked rider whose trusted fix keeps arriving never alerts`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        for (t in 1..1800) assertFalse(h.update(s(t), lastFixMs = s(t - (t % 5)), paused = false))
    }

    @Test fun `a fresh fix while paused does not re-arm, the first one after resume does`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertTrue(h.update(s(100), lastFixMs = s(10), paused = false))
        assertFalse(h.update(s(150), lastFixMs = s(149), paused = true))
        assertTrue(h.fired)
        h.onResume(s(160))
        assertFalse(h.update(s(161), lastFixMs = s(160), paused = false))
        assertFalse(h.fired)
    }

    @Test fun `a dispatch the host refused is retried on the next tick`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0))
        assertTrue(h.update(s(70), lastFixMs = s(5), paused = false))
        h.undoFire()
        assertTrue(h.update(s(71), lastFixMs = s(5), paused = false))
    }

    @Test fun `ride end resets, so the previous ride's fix cannot leak in`() {
        val h = GpsHealth(60.0)
        h.onRaceStart(s(0)); h.update(s(100), lastFixMs = s(1), paused = false)
        h.reset()
        assertFalse(h.fired)
        assertNull(h.ageS(s(5000), s(1)))
        h.onRaceStart(s(5000))
        assertEquals(10.0, h.ageS(s(5010), lastFixMs = s(1))!!, 1e-9) // stale fix older than the new anchor
        // Production nulls lastFix at ride end, so a new ride with no fix yet is the indoor case: silent.
        assertFalse(h.update(s(5200), lastFixMs = null, paused = false))
    }
}
