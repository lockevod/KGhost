package com.enderthor.kghost.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGlideTest {
    // Two consecutive publishes: ghost at 100 m @ t=1000, then 110 m @ t=2000 (a 10 m/tick advance).
    private val prev = 100.0
    private val prevT = 1000L
    private val cur = 110.0
    private val curT = 2000L

    @Test fun `at the instant a new sample lands the marker sits on the previous one`() {
        assertEquals(prev, MapGlide.interpDistM(prev, prevT, cur, curT, nowMs = curT), 1e-9)
    }

    @Test fun `glides to the current sample over GLIDE_MS then holds (low lag)`() {
        // Reaches cur after the short GLIDE_MS window, NOT after a full inter-tick interval.
        assertEquals(cur, MapGlide.interpDistM(prev, prevT, cur, curT, nowMs = curT + MapGlide.GLIDE_MS.toLong()), 1e-9)
        // Halfway through the glide window → halfway between.
        assertEquals(105.0, MapGlide.interpDistM(prev, prevT, cur, curT, nowMs = curT + (MapGlide.GLIDE_MS / 2).toLong()), 1e-9)
        // Past the glide window but before the next sample → HOLDS at cur (this is what kills the lag:
        // the marker sits on the field's latest value instead of trailing toward it all tick).
        assertEquals(cur, MapGlide.interpDistM(prev, prevT, cur, curT, nowMs = curT + 1500), 1e-9)
    }

    @Test fun `never leads past the current sample however late the loop fires`() {
        val v = MapGlide.interpDistM(prev, prevT, cur, curT, nowMs = curT + 10_000)
        assertEquals(cur, v, 1e-9)
        assertTrue("must not overshoot the latest published distance", v <= cur)
    }

    @Test fun `with only one sample it shows the latest with no glide`() {
        assertEquals(cur, MapGlide.interpDistM(Double.NaN, 0L, cur, curT, nowMs = curT + 400), 1e-9)
    }

    @Test fun `a non-finite current distance has nothing to show`() {
        assertTrue(MapGlide.interpDistM(prev, prevT, Double.NaN, curT, nowMs = curT).isNaN())
    }

    @Test fun `a degenerate non-advancing interval holds at the latest (paused republish)`() {
        // Pause republishes the same frozen value/timestamp → no interval → hold, don't divide by zero.
        assertEquals(cur, MapGlide.interpDistM(prev, curT, cur, curT, nowMs = curT + 300), 1e-9)
    }
}
