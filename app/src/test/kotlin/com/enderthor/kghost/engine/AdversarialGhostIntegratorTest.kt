package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADVERSARIAL: the neutral-fill change vs the CALLER's tick semantics
 * (KGhostExtension.kt:2028-2102 — the B2 block's moving-time race clock).
 *
 * [Caller] is a faithful replica of that block's clock:
 *   riderDist = coast.effectiveDistanceM
 *   if (prevEl != null && elapsedS > prevEl && riderDist <= integLastRiderDist) moveStart += (elapsedS - prevEl)
 *   integ.onTick(riderDist, ..., elapsedS - moveStart) { paceNow }
 *   integLastRiderDist = riderDist
 */
class AdversarialGhostIntegratorTest {

    private class Caller(val g: GhostIntegrator) {
        var moveStart = 0.0
        private var prevEl: Double? = null
        private var integLastRiderDist = 0.0
        /** One host tick: raw ride ELAPSED_TIME seconds + the coast odometer. */
        fun tick(riderDist: Double, elapsedS: Double, pace: Double?) {
            val p = prevEl
            if (p != null && elapsedS > p && riderDist <= integLastRiderDist) moveStart += (elapsedS - p)
            prevEl = elapsedS
            g.onTick(riderDist, 0.0, riderDist, 90.0, elapsedS - moveStart) { _, _, _ -> pace }
            integLastRiderDist = riderDist
        }
    }

    private fun newInt(vp: Double = 0.3) = // vp 0.3 s/m == the 12 km/h Ghost-Pace target
        GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = vp, decimateM = 20.0)

    // ---------------------------------------------------------------------------------------------
    // #1  de == 0 && dd > 0 IS REACHABLE FROM THE CALLER, and it is a ONE-SIGNED RATCHET.
    //
    // combine(DISTANCE, ELAPSED_TIME, SPEED).sample(1000ms) (KGhostExtension.kt:1749-1751) samples on
    // an Android timer that is INDEPENDENT of the ride app's 1 Hz ELAPSED_TIME emitter. Whenever two
    // sample boundaries fall inside one ELAPSED_TIME second, the tuple carries a REPEATED elapsedS
    // with a FRESH distM. The caller's freeze is gated on `elapsedS > prevEl`, so it does not fire;
    // the integrator sees de == 0 with dd > 0 and falls back to vpTimePerM (GhostIntegrator.kt:63).
    //
    // Novel ground everywhere → the correct gap is EXACTLY 0 for the whole ride.
    // ---------------------------------------------------------------------------------------------
    @Test fun `repeated elapsed second with a fresh odometer ratchets fake lead on novel ground`() {
        val g = newInt(); val c = Caller(g)
        var d = 0.0
        var el = 0.0
        var dupes = 0
        // 4 h at 30 km/h (8.333 m/s) = 120 km, novel ground everywhere (pace == null).
        for (i in 0 until 14_400) {
            d += 8.333
            // Sampler/emitter beat: one repeated elapsed second per minute (a very conservative rate).
            if (i % 60 == 59) dupes++ else el += 1.0
            c.tick(d, el, null)
        }
        println("dupes=$dupes gapTimeS=${g.gapTimeS} gapDistM=${g.gapDistM}")
        // The gap MUST be 0 — the rider has no history anywhere, so there is nothing to be ahead of.
        assertEquals("novel ground must produce exactly no verdict", 0.0, g.gapTimeS, 1.0)
    }

    // Same ride, no repeated seconds → proves the ratchet is caused by de==0, not by the pace model.
    @Test fun `same novel ride with a clean monotonic clock is exactly neutral`() {
        val g = newInt(); val c = Caller(g)
        var d = 0.0
        for (i in 0 until 14_400) { d += 8.333; c.tick(d, i.toDouble(), null) }
        println("clean 4h novel gapTimeS=${g.gapTimeS}")
        assertEquals(0.0, g.gapTimeS, 1e-6) // no float drift: ghostTime += (de/dd)*dd == de
    }

    // ---------------------------------------------------------------------------------------------
    // #2  DWELL ASYMMETRY: 10 min stopped, odometer creeping (GPS jitter through CoastingEstimator).
    //     The moving-time freeze only fires when the odometer does NOT advance, so a monotonic creep
    //     defeats it entirely. Novel ground now costs 0; ground WITH history costs the whole dwell.
    //     Before the change both cost the same. The change made the two INCONSISTENT.
    // ---------------------------------------------------------------------------------------------
    @Test fun `ten minute dwell with a creeping odometer - novel vs known ground`() {
        fun dwell(pace: Double?): Double {
            val g = newInt(); val c = Caller(g)
            var d = 0.0; var el = 0.0
            for (i in 0 until 60) { d += 8.333; el += 1.0; c.tick(d, el, pace) } // ride 500 m first
            val before = g.gapTimeS
            for (i in 0 until 600) { d += 0.005; el += 1.0; c.tick(d, el, pace) } // 10 min stop, 3 m creep
            return g.gapTimeS - before
        }
        val novel = dwell(null)
        val known = dwell(0.2)
        println("dwell delta: novel=$novel s  known(0.2 s/m)=$known s")
        assertEquals("novel dwell is free", 0.0, novel, 1e-6)
        assertTrue("known-ground dwell bleeds the whole 600 s stop: $known", known < -595.0)
    }

    // ---------------------------------------------------------------------------------------------
    // #3  BACKWARD dd (coast snap-back on GPS recovery) — the caller freezes E on that tick, so the
    //     re-ridden metres come back with de>0 → neutral. Verify no ratchet hides here.
    // ---------------------------------------------------------------------------------------------
    @Test fun `gps loss coast overshoot then snap back stays neutral on novel ground`() {
        val g = newInt(); val c = Caller(g)
        var d = 0.0; var el = 0.0
        for (i in 0 until 60) { d += 8.333; el += 1.0; c.tick(d, el, null) }
        val before = g.gapTimeS
        for (i in 0 until 30) { d += 11.0; el += 1.0; c.tick(d, el, null) } // coasting overshoots
        d -= 80.0; el += 1.0; c.tick(d, el, null)                          // fix returns → snap back
        for (i in 0 until 20) { d += 8.333; el += 1.0; c.tick(d, el, null) }
        println("gps-loss novel gap delta=${g.gapTimeS - before}")
        assertEquals(0.0, g.gapTimeS - before, 1e-6)
    }

    // ---------------------------------------------------------------------------------------------
    // #4  restore() DOES reset prevElapsedS (to NaN, so the resume tick's `de > 0.0` check is false and
    //     dd is forced to 0 on that tick regardless). This pins that the tick AFTER the resume tick reads
    //     a correct prevElapsedS (the resume tick's own elapsedS, assigned at the bottom of onTick) —
    //     a future edit that moves the `prevElapsedS = elapsedS` assignment above the resume branch (so
    //     it runs before pendingResumeLead is consumed, and gets clobbered by restore()'s NaN reset, or
    //     reordered some other way that stops the resume tick from stamping prevElapsedS) fails here.
    // ---------------------------------------------------------------------------------------------
    @Test fun `neutral fill survives the resume discontinuity`() {
        val g = newInt()
        // Live race, race clock at 1000 s.
        for (i in 0..100) g.onTick(i * 10.0, 0.0, i * 10.0, 90.0, i * 10.0) { _, _, _ -> null }
        // Resume with a FRESH elapsed origin (0) — pendingResumeLead forces dd=0, prevElapsedS := 0.
        g.restore(leadS = 60.0, lastRiderDist = 1000.0)
        g.onTick(1000.0, 0.0, 1000.0, 90.0, 0.0) { _, _, _ -> null }
        assertEquals(60.0, g.gapTimeS, 1e-6) // the guarded path is fine
        // The tick after the resume tick reads prevElapsedS from the resume tick, which is correct. Assert
        // that, so a future edit that moves the `prevElapsedS = elapsedS` assignment above the resume
        // branch fails here.
        g.onTick(1010.0, 0.0, 1010.0, 90.0, 1.0) { _, _, _ -> null }
        assertEquals("neutral fill must survive the resume discontinuity", 60.0, g.gapTimeS, 1e-6)
    }

    // ---------------------------------------------------------------------------------------------
    // #5  Worst realistic case for #1: an urban ride where the sampler beats hard (1 repeat in 10).
    // ---------------------------------------------------------------------------------------------
    @Test fun `heavy sampler beat on a novel ride fabricates minutes of lead`() {
        val g = newInt(); val c = Caller(g)
        var d = 0.0; var el = 0.0
        for (i in 0 until 3_600) { d += 8.333; if (i % 10 == 9) Unit else el += 1.0; c.tick(d, el, null) }
        println("1h novel, 1-in-10 repeat: gapTimeS=${g.gapTimeS} (should be 0)")
        assertEquals(0.0, g.gapTimeS, 1.0)
    }
}
