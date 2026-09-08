package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostIntegratorTest {
    // Historical pace 0.2 s/m where lng <= `until`, null (→ VP) beyond.
    private fun pace(hist: Double, until: Double): (Double, Double, Double) -> Double? =
        { _, lng, _ -> if (lng <= until) hist else null }
    private fun newInt(vp: Double = 0.4) = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = vp, decimateM = 20.0)

    @Test fun `rider faster than history shows AHEAD in time and distance`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        // Baseline at 0, then ride to 100 m. Historical = 100*0.2 = 20 s; rider's elapsed at 100 m = 15 s → +5 AHEAD.
        for (i in 0..5) g.onTick(riderDist = i * 20.0, lat = 0.0, lng = i * 20.0, bearingDeg = 90.0, elapsedS = i * 3.0, paceAt = src)
        assertEquals(20.0, g.ghostTime, 1e-6)
        assertEquals(5.0, g.gapTimeS, 1e-6)
        assertTrue(g.gapDistM > 0.0)
    }

    @Test fun `novel road is neutral - it neither grows nor shrinks the lead`() {
        val g = newInt(vp = 0.4); val src = pace(0.2, 50.0)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)       // baseline
        g.onTick(50.0, 0.0, 50.0, 90.0, 5.0, src)     // +50 m hist 0.2 → ghost +10 vs elapsed 5 → lead +5
        assertEquals(5.0, g.gapTimeS, 1e-6)
        g.onTick(100.0, 0.0, 100.0, 90.0, 20.0, src)  // +50 m novel in 15 s → ghost +15 → lead UNCHANGED
        assertEquals(25.0, g.ghostTime, 1e-6)
        assertEquals(5.0, g.gapTimeS, 1e-6)
    }

    // The reported field bug: a whole route on ground with NO history used to accrue at the 12 km/h VP
    // target, so a rider averaging > 2× that target ended up "ahead" by MORE than their own elapsed time
    // (and by kilometres). Novel ground must return no verdict at all, not a fabricated one.
    @Test fun `a fully novel route never fabricates a gap`() {
        val g = newInt(vp = 0.3); val src = pace(0.2, -1.0) // history nowhere
        for (i in 0..300) g.onTick(i * 100.0, 0.0, i * 100.0, 90.0, i * 10.0, src) // 30 km at 10 m/s
        assertEquals(0.0, g.gapTimeS, 1e-6)
        assertEquals(0.0, g.gapDistM, 1.0)
    }

    // A repeated ELAPSED_TIME value against a fresh distance (the caller's combine+sample can emit one)
    // must accrue NOTHING on novel ground. Charging the VP pace there minted unearned lead on every such
    // tick, one-signed and never given back — a 4 h ride with one repeat a minute came out +10 min AHEAD.
    @Test fun `a repeated race-clock tick accrues nothing on novel ground`() {
        val g = newInt(vp = 0.4); val src = pace(0.2, -1.0)
        g.onTick(0.0, 0.0, 0.0, 90.0, 10.0, src)      // baseline
        g.onTick(50.0, 0.0, 50.0, 90.0, 10.0, src)    // +50 m, Δelapsed = 0 → neutral contribution = 0
        assertEquals(10.0, g.ghostTime, 1e-9)
        assertEquals(0.0, g.gapTimeS, 1e-9)
    }

    @Test fun `a stop accrues nothing`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)
        g.onTick(50.0, 0.0, 50.0, 90.0, 10.0, src)
        val before = g.ghostTime
        g.onTick(50.0, 0.0, 50.0, 90.0, 30.0, src)    // Δd = 0
        assertEquals(before, g.ghostTime, 1e-9)
    }

    @Test fun `restore reproduces the LEAD against a fresh elapsed origin (no whole-ride inflation)`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        // Resume with a +30 s lead at odometer 500, but the resumed race clock restarts near 0 (fresh
        // firstMoveElapsed). Persisting absolute ghostTime would have published ~elapsed-at-checkpoint;
        // restore takes the LEAD and re-anchors ghostTime = elapsed + lead at the first resumed tick.
        g.restore(leadS = 30.0, lastRiderDist = 500.0)
        g.onTick(500.0, 0.0, 500.0, 90.0, 0.0, src)   // first resumed tick: elapsed≈0, no distance moved yet
        assertEquals(30.0, g.gapTimeS, 1e-6)          // lead reproduced, NOT inflated
        assertEquals(30.0, g.ghostTime, 1e-6)         // = elapsed(0) + lead(30)
        g.onTick(520.0, 0.0, 520.0, 90.0, 10.0, src)  // +20 m at 0.2 → +4 ghostTime; +10 s elapsed
        assertEquals(34.0, g.ghostTime, 1e-6)
        assertEquals(24.0, g.gapTimeS, 1e-6)          // 34 − 10
    }

    @Test fun `first tick at a non-zero distance anchors the gap to zero`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(500.0, 0.0, 500.0, 90.0, 120.0, src) // race already 500 m / 120 s in
        assertEquals(120.0, g.ghostTime, 1e-6)         // anchored to elapsed, NOT 0
        assertEquals(0.0, g.gapTimeS, 1e-6)
    }

    @Test fun `a backward coast correction re-baselines without wiping the lead or freezing`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)
        g.onTick(8000.0, 0.0, 8000.0, 90.0, 1500.0, src)   // ghostTime = 1600 (8000 m * 0.2 s/m)
        val ghostBefore = g.ghostTime
        g.onTick(7100.0, 0.0, 7100.0, 90.0, 1510.0, src)   // GPS recovers: distance snaps back 900 m
        assertEquals(ghostBefore, g.ghostTime, 1e-9)        // lead NOT wiped (old reset set it to 1510)
        g.onTick(7200.0, 0.0, 7200.0, 90.0, 1525.0, src)   // forward +100 m at 0.2 → +20
        assertEquals(ghostBefore + 20.0, g.ghostTime, 1e-6) // accrual resumed, NOT frozen
    }

    // The rollback used to re-baseline the odometer ONLY, leaving every breadcrumb in the
    // over-estimated frame. place() then interpolated the ghost across ground the rider never
    // covered, so the SAME field could read AHEAD in time and BEHIND in distance — GapFormat derives
    // the two signs independently, so it renders "+0:01" green next to "-20 m". The existing rollback
    // test above only asserts ghostTime, which survived the bug untouched.
    @Test fun `a rollback leaves time and distance agreeing on who is ahead`() {
        val g = newInt()
        // A pace source whose value this test drives tick by tick: the sequence needs a genuine TIE
        // (rider exactly at historical pace) for the contradiction to be visible — with the rider
        // already ahead, a positive gapDistM hides it.
        var p: Double? = 0.1
        val src: (Double, Double, Double) -> Double? = { _, _, _ -> p }

        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)         // anchor
        g.onTick(100.0, 0.0, 100.0, 90.0, 10.0, src)    // 100 m at 0.1 s/m in 10 s → dead level
        assertEquals(0.0, g.gapTimeS, 1e-6)
        p = null
        g.onTick(200.0, 0.0, 200.0, 90.0, 20.0, src)    // 100 m of novel ground → neutral, still level
        assertEquals(0.0, g.gapTimeS, 1e-6)

        // GPS returns and the coast estimator hands back 50 phantom metres. The crumb at 200 m was
        // recorded against ground the rider never covered.
        p = 0.1
        g.onTick(150.0, 0.0, 150.0, 90.0, 20.0, src)
        assertEquals("still a tie in time", 0.0, g.gapTimeS, 1e-6)
        assertEquals("so it must be a tie in distance too (was -50)", 0.0, g.gapDistM, 1e-6)

        // And on the next real metres: +10 m buying +2 s of history against +1 s of clock.
        p = 0.2
        g.onTick(160.0, 0.0, 160.0, 90.0, 21.0, src)
        assertTrue("rider is ahead in time", g.gapTimeS > 0.0)
        // Assert the VALUE, not the sign: a "clamp every negative AHEAD distance to zero" fix also
        // satisfies >= 0 while leaving the field useless. The ghost is interpolated between the 100 m
        // crumb (ghost-time 10) and the fresh 160 m crumb (ghost-time 22), at elapsed 21 → 155 m.
        assertEquals("the placement itself must be right, not merely non-negative", 5.0, g.gapDistM, 0.01)
    }

    // A correction bigger than every crumb must still leave a usable anchor rather than an empty trail.
    @Test fun `a rollback past every crumb re-seeds instead of emptying the trail`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(1000.0, 0.0, 1000.0, 90.0, 100.0, src)  // anchor at 1000 m
        g.onTick(1200.0, 0.0, 1200.0, 90.0, 120.0, src)
        val ghostBefore = g.ghostTime
        g.onTick(10.0, 0.0, 10.0, 90.0, 125.0, src)      // odometer collapses below every crumb
        assertEquals("the accrued lead is never a casualty of the correction", ghostBefore, g.ghostTime, 1e-9)
        assertTrue("gapDistM stays finite", g.gapDistM.isFinite())

        // The re-seed must be stamped with the RACE clock, not ghostTime. Stamping the accrued lead puts
        // the sole crumb AHEAD of elapsedS, which pins place() into its `lo == 0` branch: the marker
        // freezes at one coordinate for gapTimeS seconds while gapDistM quietly reports "metres since the
        // glitch" instead of the lead. Assert the marker MOVES, not merely that the number is finite.
        val frozenLng = g.ghostLng
        g.onTick(110.0, 0.0, 110.0, 90.0, 135.0, src)    // +100 m at 0.2 → +20 s accrues normally
        assertEquals(ghostBefore + 20.0, g.ghostTime, 1e-6)
        assertTrue("the ghost marker must not be frozen at the re-seed point", g.ghostLng != frozenLng)
    }

    @Test fun `gap distance never reports ahead while the rider is behind (end clamp)`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)
        g.onTick(100.0, 0.0, 100.0, 90.0, 30.0, src)  // crumb at 100 m / ghostTime 20
        g.onTick(110.0, 0.0, 110.0, 90.0, 33.0, src)  // +10 m (< decimate) → live point lags the crumb
        assertTrue(g.gapTimeS < 0.0)                  // rider behind (ghostTime 22 < elapsed 33)
        assertTrue(g.gapDistM <= 0.0)                 // must NOT read +10 (the old end-clamp bug)
    }

    @Test fun `restore re-baselines the odometer so an un-checkpointed segment or a reset does not skew the lead`() {
        val src = pace(0.2, 1e9)
        // (a) rider rode 100 m past the last checkpoint before the cut: first resumed tick at 5100 vs
        //     cp.lastRiderDist 5000 must still read exactly the lead (no +pace·100 overshoot).
        val g1 = newInt()
        g1.restore(leadS = 30.0, lastRiderDist = 5000.0)
        g1.onTick(5100.0, 0.0, 5100.0, 90.0, 0.0, src)
        assertEquals(30.0, g1.gapTimeS, 1e-6)
        // (b) odometer RESET across the resume (new activity zeroed DISTANCE): riderDist 0 vs 5000 must
        //     not blow up via a huge negative dd — the lead is preserved.
        val g2 = newInt()
        g2.restore(leadS = 30.0, lastRiderDist = 5000.0)
        g2.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)
        assertEquals(30.0, g2.gapTimeS, 1e-6)
    }

    @Test fun `no NaN map-ghost immediately after restore (resumed stopped)`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.restore(leadS = 100.0, lastRiderDist = 500.0)
        g.onTick(500.0, 41.0, 2.0, 90.0, 100.0, src)  // resumed stopped: Δd = 0
        assertTrue(!g.ghostLat.isNaN() && !g.ghostLng.isNaN())
        assertEquals(41.0, g.ghostLat, 1e-9)          // seeded at the resume position
    }

    @Test fun `constructor rejects a non-positive VP pace`() {
        try { GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.0); org.junit.Assert.fail("expected IAE") }
        catch (e: IllegalArgumentException) { /* expected */ }
    }
}
