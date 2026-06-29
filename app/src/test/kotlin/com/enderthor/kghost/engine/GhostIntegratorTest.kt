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

    @Test fun `novel road accrues at the VP pace`() {
        val g = newInt(vp = 0.4); val src = pace(0.2, 50.0)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)       // baseline
        g.onTick(50.0, 0.0, 50.0, 90.0, 10.0, src)    // +50 m hist 0.2 → +10
        g.onTick(100.0, 0.0, 100.0, 90.0, 25.0, src)  // +50 m VP 0.4 → +20
        assertEquals(30.0, g.ghostTime, 1e-6)
    }

    @Test fun `a stop accrues nothing`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.onTick(0.0, 0.0, 0.0, 90.0, 0.0, src)
        g.onTick(50.0, 0.0, 50.0, 90.0, 10.0, src)
        val before = g.ghostTime
        g.onTick(50.0, 0.0, 50.0, 90.0, 30.0, src)    // Δd = 0
        assertEquals(before, g.ghostTime, 1e-9)
    }

    @Test fun `restore continues ghostTime without re-anchoring`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.restore(ghostTime = 100.0, lastRiderDist = 500.0)
        g.onTick(520.0, 0.0, 520.0, 90.0, 90.0, src)  // +20 m at 0.2 → ghostTime 104; gap = 104 − 90 = 14
        assertEquals(104.0, g.ghostTime, 1e-6)
        assertEquals(14.0, g.gapTimeS, 1e-6)
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

    @Test fun `no NaN map-ghost immediately after restore (resumed stopped)`() {
        val g = newInt(); val src = pace(0.2, 1e9)
        g.restore(ghostTime = 100.0, lastRiderDist = 500.0)
        g.onTick(500.0, 41.0, 2.0, 90.0, 100.0, src)  // resumed stopped: Δd = 0
        assertTrue(!g.ghostLat.isNaN() && !g.ghostLng.isNaN())
        assertEquals(41.0, g.ghostLat, 1e-9)          // seeded at the resume position
    }

    @Test fun `constructor rejects a non-positive VP pace`() {
        try { GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.0); org.junit.Assert.fail("expected IAE") }
        catch (e: IllegalArgumentException) { /* expected */ }
    }
}
