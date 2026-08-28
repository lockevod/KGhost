package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradePaceTest {

    /** A straight track: [n] points every [stepM] metres, climbing [gradePct]%, ridden at [speedMs]. */
    private fun track(
        id: String, n: Int, stepM: Double, gradePct: Double, speedMs: Double, startEpoch: Long = 1L,
    ): RecordedTrack {
        val pts = (0 until n).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1,
                distanceM = i * stepM,
                timeS = i * stepM / speedMs,
                eleM = i * stepM * gradePct / 100.0,
            )
        }
        return RecordedTrack(id = id, startedAtEpoch = startEpoch, points = pts)
    }

    @Test fun `flat history answers a flat lookup with the flat pace`() {
        // 4 km of flat at 10 m/s = 0.1 s/m.
        val g = GradePace.build(listOf(track("a", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0)))
        assertEquals(0.1, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3)
    }

    @Test fun `a climb is answered with the climbing pace, not the flat one`() {
        // Flat at 10 m/s, 6% at 3 m/s. Both bins must keep their own pace.
        val g = GradePace.build(
            listOf(
                track("flat", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0),
                track("climb", n = 201, stepM = 20.0, gradePct = 6.0, speedMs = 3.0, startEpoch = 2L),
            )
        )
        assertEquals(0.1, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3)
        assertEquals(1.0 / 3.0, g.pace(6.0, GhostPick.AVERAGE)!!, 1e-2)
    }

    @Test fun `a gradient with no history returns null so the caller falls through to neutral`() {
        val g = GradePace.build(listOf(track("flat", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0)))
        assertNull(g.pace(-12.0, GhostPick.AVERAGE))
    }

    @Test fun `a gradient with only a sliver of history is not trusted`() {
        // 300 m of 4%: the first 100 m only fills the gradient window, so bin 4 folds 220 m of history —
        // the bin EXISTS (so the lookup really does reach the GRADE_MIN_BIN_M gate) but is under 400 m.
        val g = GradePace.build(listOf(track("sliver", n = 16, stepM = 20.0, gradePct = 4.0, speedMs = 5.0)))
        val bin = g.toDto().bins.single { it.bin == 4 }
        assertTrue("bin 4 must have folded some metres: ${bin.metres}", bin.metres > GRADE_WINDOW_M)
        assertTrue("bin 4 must stay under the trust floor: ${bin.metres}", bin.metres < GRADE_MIN_BIN_M)
        assertNull(g.pace(4.0, GhostPick.AVERAGE))
    }

    @Test fun `a gradient answers once its bin clears the trust floor`() {
        // 500 m of 4% -> 420 m folded into bin 4, over GRADE_MIN_BIN_M, from ONE ride.
        val g = GradePace.build(listOf(track("enough", n = 26, stepM = 20.0, gradePct = 4.0, speedMs = 5.0)))
        assertEquals(0.2, g.pace(4.0, GhostPick.AVERAGE)!!, 1e-6)
        // One track = one sample per bin (the PacePatch rule): count is RIDES, not 20 m steps.
        assertEquals(1, g.toDto().bins.single { it.bin == 4 }.count)
    }

    @Test fun `tracks with no elevation contribute nothing`() {
        val noEle = RecordedTrack(
            id = "noele", startedAtEpoch = 1L,
            points = (0 until 201).map {
                TrackPointDto(lat = 41.4 + it * 0.0001, lng = 2.1, distanceM = it * 20.0, timeS = it * 2.0)
            },
        )
        val g = GradePace.build(listOf(noEle))
        assertEquals(0.0, g.coveredM, 1e-9)
        assertNull(g.pace(0.0, GhostPick.AVERAGE))
    }

    @Test fun `BEST is clamped so it can never be more than BEST_MAX_SPEEDUP faster than the average`() {
        // 5 m/s (0.2 s/m) then 25 m/s (0.04 s/m) — both under AGG_MAX_SPEED_MS, so BOTH rides land in bin 0
        // and the raw min (0.04) really is more than BEST_MAX_SPEEDUP faster than the average (0.12).
        val g = GradePace.build(
            listOf(
                track("slow", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 5.0),
                track("fast", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 25.0, startEpoch = 2L),
            )
        )
        val avg = g.pace(0.0, GhostPick.AVERAGE)!!
        val best = g.pace(0.0, GhostPick.BEST)!!
        assertEquals(0.12, avg, 1e-9)                      // seed running mean of the two rides
        assertEquals(avg / BEST_MAX_SPEEDUP, best, 1e-9)   // the clamp binds, exactly
    }

    @Test fun `gradient is smoothed over the window so one noisy altitude cannot invent a wall`() {
        // Altitude alternating 0/+3 m every 20 m step — a plain baro wobble. Measured over the STEP that is
        // +-15% (in range, so the GRADE_MAX_PCT drop cannot mask it) and ~2 km would land in bin +-15.
        // Measured over the 100 m window the two ends are 5 steps apart, so it is only +-3%.
        val pts = (0 until 201).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.0,
                eleM = if (i % 2 == 0) 0.0 else 3.0,
            )
        }
        val g = GradePace.build(listOf(RecordedTrack(id = "spike", startedAtEpoch = 1L, points = pts)))
        assertNull("a 20 m baro wobble must not invent a 15% ramp", g.pace(15.0, GhostPick.AVERAGE))
        assertNull(g.pace(-15.0, GhostPick.AVERAGE))
        // ...and the track is not simply thrown away: the smoothed +-3% bins carry it.
        assertNotNull(g.pace(3.0, GhostPick.AVERAGE))
        assertTrue(g.coveredM > 3000.0)
    }

    @Test fun `the dto round-trip preserves every bin`() {
        // Three flat rides at DIFFERENT paces so ema (0.1417), last (0.125) and min (0.1) all differ —
        // a field swap between toDto/fromDto is invisible on a constant-pace fixture.
        val g = GradePace.build(
            listOf(
                track("f1", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0, startEpoch = 1L),
                track("f2", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 5.0, startEpoch = 2L),
                track("f3", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 8.0, startEpoch = 3L),
                track("climb", n = 201, stepM = 20.0, gradePct = 6.0, speedMs = 3.0, startEpoch = 4L),
            )
        )
        val avg = g.pace(0.0, GhostPick.AVERAGE)!!
        val last = g.pace(0.0, GhostPick.LAST)!!
        val best = g.pace(0.0, GhostPick.BEST)!!
        assertTrue("fixture must separate the three picks: $avg / $last / $best",
            avg != last && last != best && avg != best)

        val back = GradePace.fromDto(g.toDto())
        for (pick in GhostPick.entries) {
            assertEquals(pick.name, g.pace(0.0, pick)!!, back.pace(0.0, pick)!!, 1e-12)
            assertEquals(pick.name, g.pace(6.0, pick)!!, back.pace(6.0, pick)!!, 1e-12)
        }
        assertEquals(g.coveredM, back.coveredM, 1e-9)
        assertEquals(listOf(0, 6), g.toDto().bins.map { it.bin })
        assertEquals(listOf(3, 1), g.toDto().bins.map { it.count }) // rides, not samples
        assertEquals(g.toDto(), back.toDto())
    }

    @Test fun `a dense track does not outvote a decimated one over the same road`() {
        // Same 4% road twice, same length, different point spacing AND different pace. One sample per track
        // per bin means the fold is the plain mean of the two rides, whichever order they are folded in.
        fun coarse(epoch: Long) = track("coarse", n = 201, stepM = 20.0, gradePct = 4.0, speedMs = 4.0, startEpoch = epoch)
        fun dense(epoch: Long) = track("dense", n = 801, stepM = 5.0, gradePct = 4.0, speedMs = 8.0, startEpoch = epoch)

        val coarseFirst = GradePace.build(listOf(coarse(1L), dense(2L))).pace(4.0, GhostPick.AVERAGE)!!
        val denseFirst = GradePace.build(listOf(dense(1L), coarse(2L))).pace(4.0, GhostPick.AVERAGE)!!
        assertEquals("fold order must not matter at 2 rides", coarseFirst, denseFirst, 1e-9)
        // Plain mean of 0.25 s/m and 0.125 s/m. A per-sample fold would land on whichever ride was last
        // (the dense one contributes ~4x the updates).
        assertEquals(0.1875, coarseFirst, 1e-9)
    }

    @Test fun `a stop inside one stretch is clipped instead of laundering the whole bin`() {
        // 4 km flat at 8 m/s (0.125 s/m) with a 45 s stop on one 20 m step near the end. Over the 100 m
        // window that stop reads 1.74 m/s — above AGG_MIN_SPEED_MS, so a window-level clip never fires and
        // the stretch records 0.575 s/m. Clipped at the STEP it costs the bin ~37 s over 3.9 km.
        val stopStep = 198 // step 197 -> 198, inside the last few windows
        val pts = (0 until 201).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1,
                distanceM = i * 20.0,
                timeS = i * 2.5 + if (i >= stopStep) 45.0 else 0.0,
                eleM = 0.0,
            )
        }
        val g = GradePace.build(listOf(RecordedTrack(id = "stop", startedAtEpoch = 1L, points = pts)))
        val p = g.pace(0.0, GhostPick.AVERAGE)!!
        assertTrue("a single stop must not launder the flat bin: $p vs 0.125 s/m", p < 0.125 * 1.15)
        assertTrue(p >= 0.125)
    }

    @Test fun `a dropout jump does not invent a bin`() {
        // 4 km of flat, then a single 3000 m / 300 s jump climbing 300 m: 10 m/s and 10% both look
        // plausible, so only the DROPOUT_GAP_M step guard can reject it.
        val flat = (0 until 201).map { i ->
            TrackPointDto(lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.5, eleM = 0.0)
        }
        val jump = TrackPointDto(lat = 41.5, lng = 2.1, distanceM = 7000.0, timeS = 800.0, eleM = 300.0)
        val g = GradePace.build(listOf(RecordedTrack(id = "gap", startedAtEpoch = 1L, points = flat + jump)))
        assertNull("a device-off gap is not 3 km of 10% climbing", g.pace(10.0, GhostPick.AVERAGE))
        assertNotNull("the real riding before the gap still counts", g.pace(0.0, GhostPick.AVERAGE))
        assertEquals(3920.0, g.coveredM, 1e-6)
    }
}
