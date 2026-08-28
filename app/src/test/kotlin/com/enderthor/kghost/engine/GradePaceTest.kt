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
        // Metre-weighted mean of the two rides: (0.2*3920 + 0.04*3920) / 7840 = 0.12.
        assertEquals(0.12, avg, 1e-9)
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
        // Three flat rides at DIFFERENT paces so mean (0.1417), last (0.125) and min (0.1) all differ —
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

    @Test fun `a dense track does not outvote a decimated one by point count over the same road`() {
        // Same 4% road twice, same length, different point spacing AND different pace. The vote is
        // metre-weighted, not per-sample, so the dense track's ~4x point count over the coarse one must not
        // skew the result toward its own pace.
        fun coarse(epoch: Long) = track("coarse", n = 201, stepM = 20.0, gradePct = 4.0, speedMs = 4.0, startEpoch = epoch)
        fun dense(epoch: Long) = track("dense", n = 801, stepM = 5.0, gradePct = 4.0, speedMs = 8.0, startEpoch = epoch)

        val coarseFirst = GradePace.build(listOf(coarse(1L), dense(2L))).pace(4.0, GhostPick.AVERAGE)!!
        val denseFirst = GradePace.build(listOf(dense(1L), coarse(2L))).pace(4.0, GhostPick.AVERAGE)!!
        assertEquals("fold order must not matter at 2 rides", coarseFirst, denseFirst, 1e-9)
        // Metre-weighted mean of 0.25 s/m (coarse) and 0.125 s/m (dense). Each track folds a 4 km road
        // through the 100 m grade window, but window-edge loss differs slightly by point spacing: coarse
        // covers 3920 m, dense covers 3905 m (confirmed via GradePace.build(...).coveredM on each alone).
        // (0.25*3920 + 0.125*3905) / (3920+3905) = 0.18761980830670927 - slightly ABOVE the arithmetic mean
        // 0.1875, toward coarse's pace, because coarse contributes the (slightly) larger share of metres
        // despite having 4x fewer points than dense. That's the actual claim of this test.
        assertEquals(0.18761980830670927, coarseFirst, 1e-9)
    }

    @Test fun `a stop inside one stretch is clipped instead of laundering the whole bin`() {
        // 4 km flat at 8 m/s (0.125 s/m) with a 1200 s cafe stop on one 20 m step near the end. Clipped at
        // the STEP the stop costs ~37.5 s over 3.92 km -> ~0.1346 s/m; left unclipped the same stop adds
        // its full 1200 s -> ~0.4311 s/m. That 3.4x separation means the bound below only holds when the
        // clip is actually firing (delete the clip line in `build` and this test must fail).
        val stopStep = 198 // step 197 -> 198, inside the last few windows
        val pts = (0 until 201).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1,
                distanceM = i * 20.0,
                timeS = i * 2.5 + if (i >= stopStep) 1200.0 else 0.0,
                eleM = 0.0,
            )
        }
        val g = GradePace.build(listOf(RecordedTrack(id = "stop", startedAtEpoch = 1L, points = pts)))
        val p = g.pace(0.0, GhostPick.AVERAGE)!!
        assertTrue("a cafe stop must not launder the flat bin: $p vs clipped ~0.1346 s/m", p < 0.2)
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

    @Test fun `a long track dominates a short errand ride over the same flat bin`() {
        // The motivating case: 100 km at 0.12 s/m, then a 5 km errand at 0.20 s/m, both flat. Unweighted
        // (the old fold) that's a plain mean of the two per-track samples = 0.16 s/m — a 33% error on the
        // bin 95% of the metres say is 0.12. Metre-weighted: (0.12*99920 + 0.20*4920) / (99920+4920) =
        // 0.12375429... — within 0.4% of the long track's own pace, as it should be.
        val g = GradePace.build(
            listOf(
                track("century", n = 5001, stepM = 20.0, gradePct = 0.0, speedMs = 1.0 / 0.12),
                track("errand", n = 251, stepM = 20.0, gradePct = 0.0, speedMs = 5.0, startEpoch = 2L),
            )
        )
        val avg = g.pace(0.0, GhostPick.AVERAGE)!!
        assertEquals(0.12375429225486455, avg, 1e-9)
        assertTrue("the long track must dominate, not split the difference at 0.16", avg < 0.13)
    }

    @Test fun `six rides keep folding as one metre-weighted mean, with no EMA phase after four`() {
        // The old fold switched to an EMA after AGG_SEED_LAPS rides, at which point ride 6 alone carried
        // 25% of the answer. There is no phase switch any more: every ride is just its metres.
        // 4 identical 0.2 s/m rides (3920 m each) -> mean 0.2 over 15680 m.
        // track5 = 420 m at 0.1 s/m -> (0.2*15680 + 0.1*420) / 16100.
        // track6 = 1920 m at 0.5 s/m -> (that * 16100 + 0.5*1920) / 18020
        //        = (0.2*15680 + 0.1*420 + 0.5*1920) / 18020 = 4138 / 18020 = 0.2296337402885682.
        // The old EMA ladder answered 0.25625 here — ride 6 (11% of the metres) moving the bin by 0.06.
        val seeds = (1..4).map { track("seed$it", n = 201, stepM = 20.0, gradePct = 8.0, speedMs = 5.0, startEpoch = it.toLong()) }
        val track5 = track("t5", n = 26, stepM = 20.0, gradePct = 8.0, speedMs = 10.0, startEpoch = 5L)
        val track6 = track("t6", n = 101, stepM = 20.0, gradePct = 8.0, speedMs = 2.0, startEpoch = 6L)
        val g = GradePace.build(seeds + track5 + track6)
        assertEquals(4138.0 / 18020.0, g.pace(8.0, GhostPick.AVERAGE)!!, 1e-9)
    }

    @Test fun `one short ride barely moves a bin that already holds a lot of history`() {
        // The motivating case, in STEADY state (well past the old AGG_SEED_LAPS boundary): 20 flat rides of
        // 20 km at 0.12 s/m (19920 m folded each -> 398400 m), then ONE 5 km errand at 0.20 s/m (4920 m).
        // Metre-weighted: (0.12*398400 + 0.2*4920) / 403320 = 48792 / 403320 = 0.120975900... — the errand
        // is 1.2% of the metres and moves the bin by 0.8%. The old EMA ladder gave the errand full weight
        // (w = AGG_ALPHA, since 4920 m >= GRADE_MIN_BIN_M) and answered 0.25*0.2 + 0.75*0.12 = 0.14: a 17%
        // swing from 1.2% of the history. That is the failure this model exists to stop.
        val history = (1..20).map {
            track("long$it", n = 1001, stepM = 20.0, gradePct = 0.0, speedMs = 1.0 / 0.12, startEpoch = it.toLong())
        }
        val errand = track("errand", n = 251, stepM = 20.0, gradePct = 0.0, speedMs = 5.0, startEpoch = 21L)
        val g = GradePace.build(history + errand)
        val avg = g.pace(0.0, GhostPick.AVERAGE)!!
        assertEquals(48792.0 / 403320.0, avg, 1e-9)
        assertTrue("one errand must not drag an all-time average to 0.14: $avg", avg < 0.1215)
    }

    @Test fun `the mean is metre-weighted, not a plain average of the two tracks`() {
        // Two tracks: a 20 km ride at 1/6 s/m and a 1 km ride at 0.5 s/m over
        // the same 2% bin. Metre-weighted: (1/6*19920 + 0.5*920) / (19920+920) = 0.18138195... The plain
        // arithmetic mean of the two paces would be (1/6 + 0.5)/2 = 0.33333... — far enough apart (0.181 vs
        // 0.333) that this assertion actually discriminates between the two formulas.
        val g = GradePace.build(
            listOf(
                track("big", n = 1001, stepM = 20.0, gradePct = 2.0, speedMs = 6.0),
                track("small", n = 51, stepM = 20.0, gradePct = 2.0, speedMs = 2.0, startEpoch = 2L),
            )
        )
        val avg = g.pace(2.0, GhostPick.AVERAGE)!!
        assertEquals(0.1813819577735125, avg, 1e-9)
        assertTrue("must differ from the plain mean of 0.33333", avg < 0.25)
    }

    @Test fun `a gap in the middle does not launder its straddled bin from either side's flat pace`() {
        // 4 km flat @ 8 m/s (ele 0), then a device-off jump to distance 7000 / ele 300, then normal flat
        // riding resumes at ele ~300. The jump step itself is dropped by the DROPOUT_GAP_M guard, but
        // unless the window RESTARTS at the gap the next few steps still take their trailing edge from
        // BEFORE it: the step to 7020 sees dd = 7020 - 4000 = 3020, grade = 300 / 3020 * 100 = 9.93% ->
        // bin 10, pouring that step's real FLAT pace into a bogus "10% climb" bin. Six such tracks pour
        // ~480 m into bin 10 -- enough to clear GRADE_MIN_BIN_M and answer ~0.125 s/m for a 10% wall.
        fun trackWithMidGap(id: String, epoch: Long): RecordedTrack {
            val flatA = (0 until 201).map { i ->
                TrackPointDto(lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.5, eleM = 0.0)
            }
            val jump = TrackPointDto(lat = 41.5, lng = 2.1, distanceM = 7000.0, timeS = 800.0, eleM = 300.0)
            val flatB = (1..50).map { k ->
                TrackPointDto(
                    lat = 41.5 + k * 0.0001, lng = 2.1,
                    distanceM = 7000.0 + k * 20.0, timeS = 800.0 + k * 2.5, eleM = 300.0,
                )
            }
            return RecordedTrack(id = id, startedAtEpoch = epoch, points = flatA + jump + flatB)
        }
        val g = GradePace.build((1..6).map { trackWithMidGap("gap$it", it.toLong()) })
        assertNull("a gap-straddling window must not invent a 10% climb", g.pace(10.0, GhostPick.AVERAGE))
        assertEquals(
            "the real flat pace either side of the gap must still be answered",
            0.125, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3,
        )
    }

    @Test fun `a 250 m gap is too small for a span bound to catch and must still not invent a bin`() {
        // The leak a dd upper bound of GRADE_WINDOW_M + DROPOUT_GAP_M (= 300 m) could not close. 4 km flat
        // @ 8 m/s (ele 0), a 250 m device-off jump to distance 4250 / ele 25, then flat riding at ele 25.
        // The jump step is dropped, but with the trailing edge left at 4000 the next two steps still pass a
        // 300 m span bound: 4270 -> dd = 270, grade = 25/270 = 9.26% -> bin 9; 4290 -> dd = 290, grade =
        // 8.62% -> bin 9. Each leaks its flat 20 m, so 11 such rides put 440 m into bin 9 -- over
        // GRADE_MIN_BIN_M, so the table would confidently answer a FLAT 0.125 s/m for a 9% climb.
        // Restarting the window at the gap leaves bin 9 empty at any gap size.
        fun trackWithSmallGap(id: String, epoch: Long): RecordedTrack {
            val flatA = (0 until 201).map { i ->
                TrackPointDto(lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.5, eleM = 0.0)
            }
            val jump = TrackPointDto(lat = 41.43, lng = 2.1, distanceM = 4250.0, timeS = 530.0, eleM = 25.0)
            val flatB = (1..50).map { k ->
                TrackPointDto(
                    lat = 41.43 + k * 0.0001, lng = 2.1,
                    distanceM = 4250.0 + k * 20.0, timeS = 530.0 + k * 2.5, eleM = 25.0,
                )
            }
            return RecordedTrack(id = id, startedAtEpoch = epoch, points = flatA + jump + flatB)
        }
        val g = GradePace.build((1..11).map { trackWithSmallGap("small$it", it.toLong()) })
        assertNull("a 250 m dropout is not a 9% climb", g.pace(9.0, GhostPick.AVERAGE))
        assertTrue("nothing may leak into bin 9 at all", g.toDto().bins.none { it.bin == 9 })
        assertEquals(
            "the real flat pace either side of the gap must still be answered",
            0.125, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3,
        )
    }

    @Test fun `a dropout whose landing point duplicates the previous timestamp still restarts the window`() {
        // Same shape as the mid-track gap above, but the jump point's timeS DUPLICATES the last flat
        // point's timeS (stepT == 0), a real failure class (backward/duplicate clock on the landing
        // point). If the stepT<=0 guard ran before the stepM>DROPOUT_GAP_M guard, it would `continue`
        // without restarting the window (j stays on the pre-gap side), reopening the exact bin-10 leak
        // the mid-track-gap test guards against.
        fun trackWithDupTimestampGap(id: String, epoch: Long): RecordedTrack {
            val flatA = (0 until 201).map { i ->
                TrackPointDto(lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.5, eleM = 0.0)
            }
            // timeS = 500.0 duplicates flatA's last point (i=200 -> timeS = 500.0): stepT == 0 here.
            val jump = TrackPointDto(lat = 41.5, lng = 2.1, distanceM = 7000.0, timeS = 500.0, eleM = 300.0)
            val flatB = (1..50).map { k ->
                TrackPointDto(
                    lat = 41.5 + k * 0.0001, lng = 2.1,
                    distanceM = 7000.0 + k * 20.0, timeS = 500.0 + k * 2.5, eleM = 300.0,
                )
            }
            return RecordedTrack(id = id, startedAtEpoch = epoch, points = flatA + jump + flatB)
        }
        val g = GradePace.build((1..6).map { trackWithDupTimestampGap("dup$it", it.toLong()) })
        assertNull(
            "a duplicate-timestamp dropout landing must not invent a 10% climb",
            g.pace(10.0, GhostPick.AVERAGE),
        )
        assertEquals(
            "the real flat pace either side of the gap must still be answered",
            0.125, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3,
        )
    }
}
