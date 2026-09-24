package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        // Past the pending tolerance (2 s): this is a genuine coast, not a held pending sample.
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)  // coast 100 + 10*3 = 130
        assertEquals(130.0, c.effectiveDistanceM, 1e-6)
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

        // The resume tick is a PENDING SAMPLE (within the 2 s tolerance): nothing is invented, so the
        // odometer holds exactly at the raw value and no metres are dead-reckoned at all.
        assertEquals(1000.0, c.effectiveDistanceM, 1e-6)
        assertEquals(1.0, c.coastingSeconds, 1e-6)          // the alert clock, not 121 s — unchanged
        assertEquals(CoastQuality.LIVE, c.quality)          // a pending sample reads LIVE, not COASTING
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

    @Test fun `GUARD - a genuine two minute tunnel is coasted end to end`() {
        // NOT a regression test: this passes identically on the pre-bound code, because 120 s was never
        // truncated by anything. It is a GUARD against a future tightening of MAX_COAST_S (a 180 s cap
        // was tried and rejected) — the case the class exists for is the rider who IS moving with a
        // sensor that IS right, and every metre of it must stay dead-reckoned.
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

    // ── Episode diagnostics (rawAtFreezeM / coastedSurplusM) — read on the RECOVERY tick ──────────
    // Pure, read-only state behind the one-line-per-GPS-loss-episode diagnostic. They must name the
    // value the raw stream FROZE at and the surplus the recovery branch discards.

    @Test fun `dropout then recovery reports the freeze point and the discarded surplus`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0) // frozen: within tolerance, a hold
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 2.0) // still within tolerance (2 s)
        assertEquals(100.0, c.effectiveDistanceM, 1e-6)               // held, nothing dead-reckoned yet
        c.update(rawDistanceM = 105.0, speedMs = 10.0, elapsedS = 3.0) // fix back: raw resumed, not jumped
        assertEquals(CoastQuality.LIVE, c.quality)
        assertEquals(100.0, c.rawAtFreezeM, 1e-6)                     // frozen AT 100 m
        assertEquals(0.0, c.coastedSurplusM, 1e-6)                    // nothing was ever invented to discard
        assertEquals(105.0, c.effectiveDistanceM, 1e-6)
    }

    @Test fun `a legitimate stop invents nothing so the surplus stays zero`() {
        val c = newEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 5.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 10.0) // parked at a light
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 20.0)
        c.update(rawDistanceM = 102.0, speedMs = 3.0, elapsedS = 21.0) // rolls again
        assertEquals(100.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6)
    }

    @Test fun `a stop INSIDE a dropout keeps the metres already dead-reckoned in the surplus`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 2.0) // within tolerance: held, not coasted
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 12.0) // stop mid-hold: hold stays open
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 22.0)
        c.update(rawDistanceM = 101.0, speedMs = 4.0, elapsedS = 23.0) // fix back
        assertEquals(100.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6) // nothing was ever dead-reckoned to discard
    }

    @Test fun `escalated - a stop INSIDE a dropout past the pending tolerance keeps the metres already dead-reckoned`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0) // past tolerance: 30 m genuinely coasted
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 13.0) // stop mid-dropout: hold, add nothing
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 23.0)
        c.update(rawDistanceM = 101.0, speedMs = 4.0, elapsedS = 24.0) // fix back
        assertEquals(100.0, c.rawAtFreezeM, 1e-6)
        assertEquals(30.0, c.coastedSurplusM, 1e-6) // the stop neither added nor removed metres
    }

    @Test fun `each episode reports its own numbers and a live stretch resets them`() {
        val c = newEstimator(coastWindowMs = 30_000L)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0) // episode 1: within tolerance, held
        c.update(rawDistanceM = 108.0, speedMs = 10.0, elapsedS = 2.0)
        assertEquals(100.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6) // held, nothing dead-reckoned to discard
        // A clean live tick in between: nothing was frozen, so the surplus is zero again.
        c.update(rawDistanceM = 118.0, speedMs = 10.0, elapsedS = 3.0)
        assertEquals(108.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6)
        // Episode 2, back to back: 2 s at 10 m/s from a different freeze point — still within tolerance.
        c.update(rawDistanceM = 118.0, speedMs = 10.0, elapsedS = 4.0)
        c.update(rawDistanceM = 118.0, speedMs = 10.0, elapsedS = 5.0)
        c.update(rawDistanceM = 130.0, speedMs = 10.0, elapsedS = 6.0)
        assertEquals(118.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6) // held, nothing dead-reckoned to discard
    }

    @Test fun `the first call has no freeze to report`() {
        val c = newEstimator()
        c.update(rawDistanceM = 500.0, speedMs = 10.0, elapsedS = 0.0)
        assertEquals(500.0, c.rawAtFreezeM, 1e-6)
        assertEquals(0.0, c.coastedSurplusM, 1e-6)
    }

    private fun holdEstimator(maxCoastS: Double = CoastingEstimator.MAX_COAST_S) =
        CoastingEstimator(coastWindowMs = 30_000L, maxCoastS = maxCoastS, pendingToleranceS = 2.0)

    @Test fun `a one second freeze is a PENDING SAMPLE - nothing invented, quality stays LIVE`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0)
        assertEquals(100.0, c.effectiveDistanceM, 1e-9)   // NOT 110: nothing invented
        assertEquals(CoastQuality.LIVE, c.quality)
        assertTrue(c.pendingSample)
        assertTrue(c.pendingHold)
        assertEquals(1.0, c.coastingSeconds, 1e-9)        // the loss clock still runs
    }

    @Test fun `the settle tick carries the whole measured step`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0)
        c.update(rawDistanceM = 116.0, speedMs = 10.0, elapsedS = 2.0)
        assertEquals(116.0, c.effectiveDistanceM, 1e-9)   // deferred metres DISCARDED, not added
        assertEquals(CoastQuality.LIVE, c.quality)
        assertFalse(c.pendingSample)
        assertFalse(c.pendingHold)
        assertEquals(0.0, c.coastingSeconds, 1e-9)
    }

    @Test fun `escalation past the tolerance flushes the deferred metres`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0)   // hold, defer 10
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 2.0)   // hold, defer 10 (<= 2.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 3.0)   // escalate
        // 100 + 20 deferred + 10 this tick = today's 100 + 10*3
        assertEquals(130.0, c.effectiveDistanceM, 1e-9)
        assertEquals(CoastQuality.COASTING, c.quality)
        assertFalse(c.pendingHold)
    }

    @Test fun `a stop inside a hold keeps the hold open and invents nothing`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0)   // hold
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 2.0)    // stopped
        assertEquals(100.0, c.effectiveDistanceM, 1e-9)
        assertEquals(CoastQuality.LIVE, c.quality)   // nothing was invented
        assertFalse(c.pendingSample)                 // this tick is a STOP, so the race clock must freeze
        assertTrue(c.pendingHold)                    // but the interval is still unresolved
    }

    @Test fun `a null speed with a remembered rate flushes before it coasts`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 1.0)   // remembers 10 m/s
        c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 2.0)   // hold, defer 10
        c.update(rawDistanceM = 110.0, speedMs = null, elapsedS = 3.0)   // null path
        // 110 + 10 deferred + 10 invented on the null path = today's 110 + 10*2
        assertEquals(130.0, c.effectiveDistanceM, 1e-9)
        assertFalse(c.pendingHold)
    }

    @Test fun `a null speed with NO usable rate flushes before the early return`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 0.0, speedMs = 40.0, elapsedS = 0.0)     // implausible: not remembered
        c.update(rawDistanceM = 10.0, speedMs = 40.0, elapsedS = 1.0)    // everMoved, rate still 0
        c.update(rawDistanceM = 10.0, speedMs = 6.0, elapsedS = 2.0)     // hold, defer 6
        c.update(rawDistanceM = 10.0, speedMs = null, elapsedS = 4.0)    // no usable rate: early return
        assertEquals(16.0, c.effectiveDistanceM, 1e-9)                   // the 6 m must not be stranded
        assertFalse(c.pendingHold)
    }

    @Test fun `a budget smaller than the tolerance never enters the hold`() {
        val c = holdEstimator(maxCoastS = 1.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 1.0)
        assertFalse(c.pendingHold)
        assertEquals(110.0, c.effectiveDistanceM, 1e-9)   // today's behaviour, budget respected
    }

    @Test fun `a zero tolerance is completely inert, even on a repeated ELAPSED_TIME`() {
        // The implementation ships dark at PENDING_TOLERANCE_S = 0.0, and Tasks 2-4 depend on that
        // being EXACTLY today's behaviour. dt is 0 whenever ELAPSED_TIME repeats (a pause, or a
        // backward correction that gets clamped), leaving coastingSeconds at 0.0 — and `0.0 <= 0.0`
        // would open an empty hold, raising both flags and firing the tick guards. The
        // `coastingSeconds > 0.0` predicate is what stops it; this test is its lock.
        val c = CoastingEstimator(coastWindowMs = 30_000L, pendingToleranceS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 50.0)   // repeated elapsed: dt = 0
        assertFalse("a zero tolerance must never open a hold", c.pendingHold)
        assertFalse(c.pendingSample)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 49.0)   // backward, clamped to dt = 0
        assertFalse("a clamped backward step must never open a hold", c.pendingHold)
        assertFalse(c.pendingSample)
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 51.0)   // today coasts
        assertFalse("a zero tolerance holds nothing at all", c.pendingHold)
        // dt here is 2.0, not 1.0: the earlier backward-clamped step (49.0) also overwrote
        // prevElapsedS, unaffected by pendingToleranceS — this is today's PRE-EXISTING dt
        // bookkeeping, unchanged by this task, so the locked value follows it: 100 + 10*2.
        assertEquals(120.0, c.effectiveDistanceM, 1e-9)
        assertEquals(CoastQuality.COASTING, c.quality)
    }

    @Test fun `pendingSample is never true while the rider is genuinely stopped`() {
        val c = holdEstimator()
        c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
        c.update(rawDistanceM = 100.0, speedMs = 0.0, elapsedS = 1.0)
        assertFalse(c.pendingSample)
        assertFalse(c.pendingHold)
    }
}
