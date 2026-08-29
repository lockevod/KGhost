package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoastingEstimatorTest {
    private fun newEstimator(coastWindowMs: Long = 30_000L) =
        CoastingEstimator(coastWindowMs = coastWindowMs)

    @Test fun `changing distance is LIVE and effective equals raw`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 1.0)
        assertEquals(110.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while stopped is a legit stop (LIVE, no coasting)`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 5.0, elapsedS = 0.0)   // moving, changing
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 1.0)   // frozen + stopped
        assertEquals(100.0, c.effectiveDistanceM, 1e-6) // raw (frozen), no extrapolation
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while moving within window coasts at last moving speed`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)  // moving at 10 m/s → remember speed
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)  // frozen + still moving, 3 s gap
        // 100 + 10 * 3 = 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.COASTING, c.quality)
        assertEquals(3.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance while moving beyond window is LONG_LOSS but keeps coasting`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 40.0) // beyond the 30 s window
        assertEquals(CoastQuality.LONG_LOSS, c.quality)
        // Still dead-reckons — never blanks: 100 + 10 * 40 = 500
        assertEquals(500.0, c.effectiveDistanceM, 1e-6)
        assertEquals(40.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `frozen distance with null speed still coasts at last moving speed`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0) // remember 10 m/s
        c.update(rawDistanceM = 100.0, speedMs = null, elapsedS = 2.0) // frozen, speed gone → still coast
        assertEquals(120.0, c.effectiveDistanceM, 1e-6) // 100 + 10 * 2
        assertEquals(CoastQuality.COASTING, c.quality)
    }

    @Test fun `resume after a gap returns to raw and LIVE with no lingering coast`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)  // coasting → 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
        c.update(rawDistanceM = 140.0, speedMs = 10.0, elapsedS = 4.0)  // GPS back, new value
        assertEquals(140.0, c.effectiveDistanceM, 1e-6) // snaps to raw, no lingering coast
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `non-finite raw distance is ignored and previous state kept`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = Double.NaN, speedMs = 10.0, elapsedS = 1.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
        c.update(rawDistanceM = Double.POSITIVE_INFINITY, speedMs = 10.0, elapsedS = 2.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.LIVE, c.quality)
    }

    @Test fun `coasting uses the last MOVING speed not a later low speed`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)  // remember 10 m/s
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 2.0)  // coast 100 + 10*2 = 120
        assertEquals(120.0, c.effectiveDistanceM, 1e-6)
        assertEquals(CoastQuality.COASTING, c.quality)
    }

    @Test fun `frozen at the start before any movement stays LIVE (no false GPS-loss)`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        // Stationary start line: first sample, then DISTANCE frozen at 0 with SPEED not yet emitting.
        c.update(rawDistanceM = 0.0, speedMs = null, elapsedS = 0.0)  // first call → LIVE
        c.update(rawDistanceM = 0.0, speedMs = null, elapsedS = 40.0) // frozen 40 s, never moved
        assertEquals(CoastQuality.LIVE, c.quality) // NOT LONG_LOSS → no false "GPS lost" alert
        assertEquals(0.0, c.coastingSeconds, 1e-6)
    }

    @Test fun `a stop WITHOUT auto-pause coasts one tick on resume, not the whole stop`() {
        // ROOT-CAUSE LOCK for the stop re-anchor. Auto-pause is a user setting and many riders leave it
        // off, so ELAPSED_TIME keeps counting through a red light while DISTANCE is frozen. Without the
        // re-anchor on every STOPPED tick the coast anchor ages across the whole stop, and the first
        // tick that reports movement again — before the DISTANCE stream has re-emitted — dead-reckons
        // `lastMovingSpeed × the WHOLE stop` in ONE tick.
        //
        // This is the ONLY lock on the two consumers that layer 1 alone protects: the no-route
        // Ghost-Pace gap (charged at historical pace and never refunded) and the GPS-lost alert clock
        // (coastingSeconds, which must be ~1 s here and not ~121 s → no false "GPS lost").
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 1000.0, speedMs = 6.0, elapsedS = 0.0) // rolling at 6 m/s, anchor at 1000 m
        for (t in 1..120) c.update(rawDistanceM = 1000.0, speedMs = 0.0, elapsedS = t.toDouble()) // 2 min stopped
        // Rolling again, but DISTANCE has not caught up yet → the coast branch fires.
        c.update(rawDistanceM = 1000.0, speedMs = 0.8, elapsedS = 121.0)

        // ONE tick of movement at the speed actually REPORTED (0.8 m/s), not 121 s of it, and not the
        // remembered 6 m/s: the dead reckoning integrates the speed stream tick by tick.
        assertEquals(1000.8, c.effectiveDistanceM, 1e-6)
        assertEquals(1.0, c.coastingSeconds, 1e-6)          // the alert clock, not 121 s
        assertEquals(CoastQuality.COASTING, c.quality)      // NOT LONG_LOSS → no false "GPS lost"
        // Ageing the anchor across the stop instead gives 1000 + 6 × 121 = 1726 m: ~726 phantom
        // metres in one tick.
    }

    @Test fun `a lying sensor cannot invent distance forever, but a real tunnel still coasts`() {
        // A wheel spinning on a rack (or a mis-configured circumference) reports plausible movement while
        // the bike is parked and the raw distance is frozen — indistinguishable from a genuine tunnel
        // except by duration. Dead reckoning is therefore bounded, not symmetric with the null-speed path:
        // a 30 s cap would freeze the odometer inside a real tunnel.
        val c = newEstimator()
        c.update(rawDistanceM = 0.0, speedMs = 8.0, elapsedS = 0.0)
        c.update(rawDistanceM = 8.0, speedMs = 8.0, elapsedS = 1.0)   // moving, raw advancing

        // Raw freezes; the sensor keeps insisting on 8 m/s for four hours.
        var t = 1.0
        repeat(14_400) { t += 1.0; c.update(rawDistanceM = 8.0, speedMs = 8.0, elapsedS = t) }

        // 1800 s of budget at 8 m/s = 14.4 km, then the odometer freezes. Unbounded, the same four hours
        // fabricated 115 km in the adversarial repro. The bound is deliberately generous: its job is the
        // runaway, not second-guessing a plausible loss (see MAX_COAST_S).
        assertEquals(8.0 + 14_400.0, c.effectiveDistanceM, 1e-6)
        // The loss clock keeps running, so the alert and the estimate mark stay honest.
        assertEquals(14_400.0, c.coastingSeconds, 1e-6)
        assertEquals(CoastQuality.LONG_LOSS, c.quality)
    }

    @Test fun `a genuine two minute tunnel is coasted end to end`() {
        // The case the class exists for: the rider IS moving and the sensor IS right. 120 s is inside the
        // budget, so every metre is dead-reckoned — the bound must not bite here.
        val c = newEstimator()
        c.update(rawDistanceM = 0.0, speedMs = 9.0, elapsedS = 0.0)
        c.update(rawDistanceM = 9.0, speedMs = 9.0, elapsedS = 1.0)
        var t = 1.0
        repeat(120) { t += 1.0; c.update(rawDistanceM = 9.0, speedMs = 9.0, elapsedS = t) }
        assertEquals(9.0 + 9.0 * 120, c.effectiveDistanceM, 1e-6) // 1080 m of tunnel, all of it
    }

    @Test fun `an implausible speed sample is never remembered as the dead-reckoning rate`() {
        // The remembered moving speed is the rate every NULL-speed metre is invented at, so one corrupt
        // sample would be spent on the NEXT dropout, long after it arrived. A 100 m/s reading (360 km/h)
        // is not a bicycle: it must be rejected, leaving the last plausible speed in place.
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 0.0, speedMs = 8.0, elapsedS = 0.0)     // moving at 8 m/s
        c.update(rawDistanceM = 8.0, speedMs = 8.0, elapsedS = 1.0)     // still 8 m/s, distance advancing
        c.update(rawDistanceM = 16.0, speedMs = 100.0, elapsedS = 2.0)  // one corrupt sample, distance still LIVE
        assertEquals(16.0, c.effectiveDistanceM, 1e-6)                  // LIVE ticks always equal raw

        // Now the fix dies AND the speed stream goes quiet: dead reckoning falls back to the remembered
        // speed for at most one coast window (30 s).
        var t = 2.0
        repeat(30) { t += 1.0; c.update(rawDistanceM = 16.0, speedMs = null, elapsedS = t) }

        // 30 s of budget at the last PLAUSIBLE speed: 16 + 8 × 30 = 256 m.
        // Had the 100 m/s sample been remembered: 16 + 100 × 30 = 3016 m — 3 km of phantom distance,
        // which the ghost would then be paid historical pace for.
        assertEquals(256.0, c.effectiveDistanceM, 1e-6)
    }

    @Test fun `a pause (elapsed frozen) injects no phantom coast distance on resume`() {
        // Simulates a long café stop: DISTANCE stays frozen and ELAPSED_TIME is frozen by the ride
        // app during pause, so the resume tick sees a frozen distance at the SAME elapsedS as the last
        // change → zero coast gap, no phantom distance. (A wall-clock coast would inject lastSpeed ×
        // the whole pause duration.)
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0) // moving, anchor at t=50 s
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0) // resume: same elapsed, frozen dist
        assertEquals(100.0, c.effectiveDistanceM, 1e-6) // no phantom forward distance
        assertEquals(0.0, c.coastingSeconds, 1e-6)
        assertNotEquals(CoastQuality.LONG_LOSS, c.quality) // not a prolonged loss
    }
}
