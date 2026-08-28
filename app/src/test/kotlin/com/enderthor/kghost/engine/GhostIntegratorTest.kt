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
