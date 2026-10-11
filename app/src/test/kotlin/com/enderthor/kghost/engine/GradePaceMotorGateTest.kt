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

    @Test fun `a car session - city 30-50 km h with 4 min over 60 km h on the flat - yields an empty fold`() {
        assertFalse(kept(Seg(40.0, 900.0), Seg(30.0, 300.0, 1.0), Seg(65.0, 240.0), Seg(50.0, 600.0, -0.5)))
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
        // 2 min at 120 km/h on the flat: foldOf drops those steps as spikes, the gate must still see them.
        assertFalse(kept(Seg(40.0, 1800.0), Seg(120.0, 120.0)))
    }

    @Test fun `a gap step - 1000 m over 60 s - does not count as fast`() {
        // Two dropouts = 120 s "at 60 km/h" against 600 s of riding: 17 %, if they counted.
        assertTrue(kept(Seg(15.0, 300.0), Seg(0.0, 60.0, gapM = 1000.0), Seg(15.0, 300.0), Seg(0.0, 60.0, gapM = 1000.0), Seg(15.0, 300.0)))
    }

    @Test fun `unknown gradient never counts as flat - fast steps with no altitude are no evidence`() {
        assertTrue(kept(Seg(40.0, 1800.0), Seg(65.0, 240.0, ele = false), Seg(40.0, 600.0)))
    }
}
