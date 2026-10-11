package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MOTORISED gate in [GradePace.foldOf]: a car session must not feed the model. */
class GradePaceMotorGateTest {

    /** [kmh] for [durS] on [gradePct], decimated to 20 m steps; [ele] false records no altitude. A [gapM]
     *  segment is ONE step of that many metres over [durS] (a dropout), not riding. */
    private data class Seg(val kmh: Double, val durS: Double, val gradePct: Double = 0.0, val ele: Boolean = true, val gapM: Double = 0.0)

    /** A straight northbound track (never home drift) built from [segs]. */
    private fun track(vararg segs: Seg): RecordedTrack {
        val pts = ArrayList<TrackPointDto>()
        var d = 0.0; var t = 0.0; var e = 100.0
        fun add(ele: Boolean) { pts += TrackPointDto(41.0 + d / 111_195.0, 2.0, d, t, if (ele) e else null) }
        add(true)
        for (s in segs) {
            if (s.gapM > 0.0) { d += s.gapM; t += s.durS; add(s.ele); continue }
            val v = s.kmh / 3.6
            repeat((s.durS * v / 20.0).toInt()) { d += 20.0; t += 20.0 / v; e += 20.0 * s.gradePct / 100.0; add(s.ele) }
        }
        return RecordedTrack("t", 1L, pts)
    }

    private fun kept(vararg segs: Seg) = !GradePace.foldOf(track(*segs)).isEmpty

    @Test fun `a car session - city 30-50 km h, 4 min over 60 km h with 1 min of it uphill - yields an empty fold`() {
        assertFalse(kept(Seg(40.0, 900.0), Seg(30.0, 300.0, 1.0), Seg(65.0, 180.0), Seg(60.0, 60.0, 3.0), Seg(50.0, 600.0, -0.5)))
    }

    @Test fun `a car that never goes fast uphill is kept - the documented accepted miss`() {
        // One 8-min city session in the real library is such a car.
        assertTrue(kept(Seg(40.0, 900.0), Seg(30.0, 300.0, 1.0), Seg(65.0, 240.0), Seg(50.0, 600.0, -0.5)))
    }

    @Test fun `a tailwind out-and-back - 30 km back at 56 km h on the flat - is kept`() {
        assertTrue(kept(Seg(25.0, 4320.0), Seg(56.0, 1930.0)))
        assertTrue("on a +1.5 % false flat too", kept(Seg(25.0, 4320.0, -1.5), Seg(56.0, 1930.0, 1.5)))
    }

    @Test fun `a valley ride - up at 20 km h, back at 55 km h on -0_8 pct - is kept`() {
        assertTrue(kept(Seg(20.0, 3600.0, 1.0), Seg(55.0, 1800.0, -0.8)))
    }

    @Test fun `29 s fast uphill does not corroborate, 31 s does`() {
        // A slow +4 % lead-in so every fast step's window is all +4 %: 24 x 1.2 s = 28.8 s, then 26 x 1.2 = 31.2 s.
        assertTrue(kept(Seg(40.0, 1200.0), Seg(65.0, 240.0), Seg(20.0, 60.0, 4.0), Seg(60.0, 29.0, 4.0)))
        assertFalse(kept(Seg(40.0, 1200.0), Seg(65.0, 240.0), Seg(20.0, 60.0, 4.0), Seg(60.0, 31.5, 4.0)))
    }

    @Test fun `a 120 s recording with an 18 s sprint at 57_6 km h is kept - under the 45 s minimum`() {
        // 18 of 120 s is 15 % of the moving time: only the absolute minimum keeps it.
        assertTrue(kept(Seg(30.0, 102.0), Seg(57.6, 18.0)))
    }

    @Test fun `a 20 min climb at 5 km h with 18 s fast is kept`() {
        assertTrue(kept(Seg(5.0, 1200.0, 6.0), Seg(57.6, 18.0)))
    }

    @Test fun `slow 14 s steps count as moving time - 55 s fast after a 20 min climb is under 5 pct`() {
        // The dt-cap trap: a 20 m decimated 5 km/h climb is 14.4 s per step. Dropping steps over 10 s from the
        // moving time would leave ~55 s of it, all fast -> 100 % -> a genuine ride excluded.
        assertTrue(kept(Seg(5.0, 1200.0, 6.0), Seg(57.6, 55.0)))
    }

    @Test fun `a 1 h road ride at 45-50 km h is kept`() {
        assertTrue(kept(Seg(45.0, 1800.0), Seg(50.0, 1800.0, 0.5)))
    }

    @Test fun `a 3 min descent at 70 km h on -4 pct is kept - a descent is no evidence`() {
        assertTrue(kept(Seg(20.0, 1200.0, 3.0), Seg(70.0, 180.0, -4.0), Seg(25.0, 600.0)))
    }

    @Test fun `steps above the speed cap still count as evidence`() {
        // A +4 % climb where the only steps with a known window are 40 m-in-1 s steps (above AGG_MAX_SPEED_MS):
        // each restarts the window, so the 60 km/h steps between them stay unknown. foldOf drops such steps as
        // spikes; the gate must still count them: 50 s of uphill evidence.
        // 80 m between them (4 steps) keeps every window between them under GRADE_WINDOW_M.
        val climb = (1..50).flatMap { listOf(Seg(60.0, 5.0, 4.0), Seg(0.0, 1.0, gapM = 40.0)) }
        assertFalse(kept(Seg(40.0, 300.0), *climb.toTypedArray()))
    }

    @Test fun `an above-cap spike restarts the gradient window - its metres cannot drag a descent into flat evidence`() {
        // 35 s genuinely fast uphill (corroborated, but under 45 s), then 2 min at 60 km/h on -2 % with a 150 m
        // GPS spike every 100 m. Left in the window, each spike's metres read the descent as ~-0.8 % -> ~2 min
        // of false flat evidence -> motorised. Restarted, those windows are unknown.
        val descent = (1..20).flatMap { listOf(Seg(60.0, 6.1, -2.0), Seg(0.0, 1.0, gapM = 150.0)) }
        assertTrue(kept(Seg(30.0, 1800.0, 1.0), Seg(60.0, 35.0, 4.0), *descent.toTypedArray()))
    }

    @Test fun `a gap step - 1000 m over 60 s - does not count as fast`() {
        // Two dropouts = 120 s "at 60 km/h" against 600 s of riding: 17 %, if they counted.
        assertTrue(kept(Seg(15.0, 300.0), Seg(0.0, 60.0, gapM = 1000.0), Seg(15.0, 300.0), Seg(0.0, 60.0, gapM = 1000.0), Seg(15.0, 300.0)))
    }

    @Test fun `unknown gradient never counts as flat - fast steps with no altitude are no evidence`() {
        assertTrue(kept(Seg(40.0, 1800.0), Seg(65.0, 240.0, ele = false), Seg(40.0, 600.0)))
    }
}
