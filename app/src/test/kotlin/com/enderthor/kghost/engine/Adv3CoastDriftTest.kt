package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * ADVERSARIAL ROUND 3 against [CoastingEstimator] as re-fixed in 6f8c8fc ("split the stop anchor into
 * an odometer and a loss clock").
 *
 * Primary hypothesis under attack: "the odometer now INTEGRATES the reported speed per tick", so it
 * should accumulate sensor error by construction over a 4-6 h ride.
 *
 * The rigs are the two consumers, copied verbatim from Adv2CoastPipelineTest (they are private there):
 *   [VpRig]    — the NO-ROUTE Ghost-Pace tick: reads effectiveDistanceM straight into GapCalculator,
 *                plus handleGpsLoss (alert at 60 s, give-up blank at 180 s). Nothing absorbs anything.
 *   [RouteRig] — the B2 route tick: effectiveDistanceM into GhostIntegrator, which neutral-fills
 *                coasted metres (coast gate) and re-baselines on a backward step.
 */
class Adv3CoastDriftTest {

    // =============================================================================================
    // RIGS (verbatim from Adv2CoastPipelineTest)
    // =============================================================================================

    private class RouteRig(vp: Double = 0.3) {
        val coast = CoastingEstimator()
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = vp, decimateM = 20.0)
        private var moveStart: Double? = null
        private var prevEl: Double? = null
        private var integLast = 0.0

        fun tick(rawDistM: Double, elapsedS: Double, speedMs: Double?, pace: Double?, fixFresh: Boolean = true) {
            coast.update(rawDistM, speedMs, elapsedS)
            if (moveStart == null && speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS) moveStart = elapsedS
            var ms = moveStart ?: return
            val riderDist = coast.effectiveDistanceM
            val p = prevEl
            if (p != null && elapsedS > p && riderDist <= integLast) { ms += (elapsedS - p); moveStart = ms }
            prevEl = elapsedS
            val paceNow = if (fixFresh && coast.quality == CoastQuality.LIVE) pace else null
            g.onTick(riderDist, 0.0, riderDist * 1e-5, 90.0, elapsedS - ms) { _, _, _ -> paceNow }
            integLast = riderDist
        }

        val gap get() = g.gapTimeS
    }

    private class VpRig(targetMs: Double = 12.0 / 3.6) {
        val coast = CoastingEstimator()
        private val curve = GhostPaceSource(targetMs).curve()
        private var moveStart: Double? = null
        private var gpsAlertFired = false

        var alertsFired = 0; private set
        var blankedTicks = 0; private set
        var estimatedTicks = 0; private set
        var gap: GapState? = null; private set

        val coastS get() = coast.coastingSeconds

        private fun handleGpsLoss(coastingS: Double): Boolean {
            if (coastingS >= 60.0) {
                if (!gpsAlertFired) { gpsAlertFired = true; alertsFired++ }
            } else if (coastingS < 30.0) {
                gpsAlertFired = false
            }
            return coastingS >= 180.0
        }

        fun tick(rawDistM: Double, elapsedS: Double, speedMs: Double?) {
            coast.update(rawDistM, speedMs, elapsedS)
            if (moveStart == null && speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS) moveStart = elapsedS
            val ms = moveStart ?: return
            if (handleGpsLoss(coastS)) { gap = null; blankedTicks++; return }
            val g = GapCalculator.compute(
                coast.effectiveDistanceM, elapsedS - ms, curve,
                fresh = coast.quality != CoastQuality.LONG_LOSS,
            )
            if (g.estimated) estimatedTicks++
            gap = g
        }

        val aheadS get() = -(gap?.gapTimeS ?: Double.NaN)
        val odoM get() = coast.effectiveDistanceM
    }

    // =============================================================================================
    // H1 — DRIFT. The assigned primary attack: 6 h at ~1 Hz with a mis-calibrated / noisy / quantised
    // speed and an irregular tick. Does the integrated odometer walk away from the truth?
    // =============================================================================================

    /** 6 h, 1 Hz, GPS healthy. Speed sensor 3% fast (wrong wheel circumference), speed quantised to
     *  0.1 km/h, tick interval jittered 0.6-1.6 s (the production flow is sample(1000).conflate()). */
    @Test fun `H1 - six hours with a 3 percent fast speed sensor and a jittery tick`() {
        val c = CoastingEstimator()
        val rnd = Random(7)
        var trueD = 0.0; var t = 0.0
        var maxErr = 0.0
        var ticks = 0
        while (t < 6 * 3600.0) {
            val dt = 0.6 + rnd.nextDouble() * 1.0
            val v = 6.0 + 3.0 * kotlin.math.sin(t / 400.0)          // 3-9 m/s rolling profile
            trueD += v * dt; t += dt
            val reported = floor(v * 1.03 * 36.0) / 36.0            // 3% fast, quantised to 0.1 km/h
            c.update(trueD, reported, t)
            maxErr = maxOf(maxErr, abs(c.effectiveDistanceM - trueD))
            ticks++
        }
        println("H1: $ticks ticks / 6 h, trueD=${"%.0f".format(trueD)} m, odo=${"%.6f".format(c.effectiveDistanceM)} m, maxErr=${"%.9f".format(maxErr)} m")
        assertEquals("odometer == raw EXACTLY at every tick where the raw moved", trueD, c.effectiveDistanceM, 0.0)
        assertEquals("the mis-calibrated speed never touches the odometer", 0.0, maxErr, 0.0)
    }

    /** The structural reason H1 cannot fail: the `changed` branch assigns `effective = raw`, so every
     *  LIVE tick re-anchors. Randomised: speeds absurdly wrong (0, 100 m/s, negative), sign-flipped,
     *  null — the odometer still equals raw whenever raw moves. */
    @Test fun `H1b - no reported speed however wrong can move a live odometer`() {
        val c = CoastingEstimator()
        val rnd = Random(11)
        var d = 0.0; var t = 0.0
        repeat(20_000) {
            d += rnd.nextDouble() * 12.0 + 0.001   // raw always changes
            t += 1.0
            val bogus = when (rnd.nextInt(5)) {
                0 -> null; 1 -> 0.0; 2 -> 100.0; 3 -> -5.0; else -> rnd.nextDouble() * 50.0
            }
            c.update(d, bogus, t)
            assertEquals(d, c.effectiveDistanceM, 0.0)
            assertEquals(CoastQuality.LIVE, c.quality)
        }
        println("H1b: 20 000 ticks of adversarial speed, odometer == raw at every one")
    }

    /** Quantised DISTANCE: the one place the integration is visible with GPS healthy. A host that steps
     *  DISTANCE in whole units leaves ticks where raw is frozen but the rider is moving — those get
     *  dead-reckoned. Measures whether the resulting bias ACCUMULATES (it must not: the next real step
     *  re-anchors) and whether it is one-signed. */
    @Test fun `H1c - quantised DISTANCE makes a one-signed sawtooth that never accumulates`() {
        for (q in listOf(1.0, 5.0, 10.0)) {
            val c = CoastingEstimator()
            var trueD = 0.0; var t = 0.0
            var sumErrRaw = 0.0; var sumErrOdo = 0.0; var maxOver = 0.0; var minErr = 0.0
            val n = 7200
            repeat(n) {
                trueD += 6.0; t += 1.0
                val raw = floor(trueD / q) * q
                c.update(raw, 6.0, t)
                val eo = c.effectiveDistanceM - trueD
                sumErrOdo += eo; sumErrRaw += raw - trueD
                maxOver = maxOf(maxOver, c.effectiveDistanceM - raw)
                minErr = minOf(minErr, eo)
            }
            println(
                "H1c q=${q}m: mean(odo-true)=${"%.2f".format(sumErrOdo / n)} m  " +
                    "mean(raw-true)=${"%.2f".format(sumErrRaw / n)} m  maxOvershoot(odo-raw)=${"%.2f".format(maxOver)} m",
            )
            assertTrue("overshoot is bounded by one quantum, not cumulative", maxOver <= q + 1e-9)
            assertTrue("the coast is CLOSER to the truth than the raw it replaces", abs(sumErrOdo) <= abs(sumErrRaw) + 1e-9)
        }
    }

    // =============================================================================================
    // H2 — THE SNAP-BACK CLIFF. The odometer is monotone WITHIN a dropout (the KDoc's claim) but the
    // class as a whole is NOT: `effective = raw` on recovery hands back every coasted metre in one
    // tick. Measured through BOTH consumers.
    // =============================================================================================

    /** 170 s blind at 10 m/s (a long tunnel / urban canyon) — deliberately just UNDER the 180 s give-up
     *  so the whole cliff is visible rather than hidden behind a blank. The host resumes DISTANCE from
     *  where it froze (no back-fill). The rider watches the lead climb, then loses 8 min in one second. */
    @Test fun `H2 - a 170 s dropout costs the no-route field an 8 minute cliff in one tick`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(300) { d += 10.0; t += 1.0; r.tick(d, t, 10.0) }
        val before = r.aheadS
        val frozen = d
        repeat(170) { t += 1.0; r.tick(frozen, t, 10.0) }   // 170 s blind at 10 m/s
        val peak = r.aheadS
        val peakOdo = r.odoM
        t += 1.0; r.tick(frozen + 10.0, t, 10.0)            // fix returns, raw continues from frozen
        val after = r.aheadS
        println(
            "H2: lead ${"%.0f".format(before)}s -> ${"%.0f".format(peak)}s (blind, odo +${"%.0f".format(peakOdo - frozen)} m) " +
                "-> ${"%.0f".format(after)}s in ONE tick. Cliff=${"%.0f".format(peak - after)}s, " +
                "odometer step=${"%.0f".format(r.odoM - peakOdo)} m, blanked=${r.blankedTicks}",
        )
        assertEquals("no blank yet: the cliff is fully visible", 0, r.blankedTicks)
        assertTrue("the odometer invents 1700 m while blind", peakOdo - frozen > 1_690.0)
        assertTrue("and gives all of it back in one tick", r.odoM - peakOdo <= -1_690.0)
        assertTrue("a >480 s discontinuity in the displayed lead", peak - after > 480.0)
    }

    /** Ten seconds longer and the cliff is hidden behind the give-up blank instead: the rider sees the
     *  lead climb for 3 min, then `---`, then a number 900 s lower than the one they last saw. */
    @Test fun `H2c - past 180 s the cliff is served with a blank in the middle`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(300) { d += 10.0; t += 1.0; r.tick(d, t, 10.0) }
        val frozen = d
        var lastSeen = r.aheadS
        repeat(300) { t += 1.0; r.tick(frozen, t, 10.0); if (r.gap != null) lastSeen = r.aheadS }
        t += 1.0; r.tick(frozen + 10.0, t, 10.0)
        println("H2c: last number shown before the blank=${"%.0f".format(lastSeen)}s, blank for ${r.blankedTicks} ticks, back at ${"%.0f".format(r.aheadS)}s")
        assertEquals(121, r.blankedTicks)
        assertTrue("the number returns >600 s below the last one shown", lastSeen - r.aheadS > 600.0)
    }

    /** The same ride through the ROUTE consumer. The coast gate (fixFresh requires quality == LIVE)
     *  neutral-fills the coasted metres, and GhostIntegrator's dd<0 branch keeps ghostTime, so the
     *  number holds instead of climbing and cliffing. This is the absorption the VP path lacks. */
    @Test fun `H2b - the route consumer absorbs the same dropout to within a second`() {
        val r = RouteRig()
        var d = 0.0; var t = 0.0
        repeat(300) { d += 10.0; t += 1.0; r.tick(d, t, 10.0, 0.05) }   // hist pace 0.05 s/m = 20 m/s
        val before = r.gap
        val frozen = d
        repeat(300) { t += 1.0; r.tick(frozen, t, 10.0, 0.05) }
        val peak = r.gap
        t += 1.0; r.tick(frozen + 10.0, t, 10.0, 0.05)
        val after = r.gap
        println(
            "H2b: route gap ${"%.1f".format(before)}s -> ${"%.1f".format(peak)}s (blind) -> ${"%.1f".format(after)}s. " +
                "Cliff=${"%.1f".format(peak - after)}s (VP's was >800 s)",
        )
        assertTrue("no climb while blind", abs(peak - before) < 2.0)
        assertTrue("no cliff on recovery", abs(peak - after) < 2.0)
    }

    // =============================================================================================
    // H3 — THE LOSS CLOCK vs A LEGITIMATELY STATIONARY RIDER. "cleared only by a real raw-distance
    // change" is attacked with the case where the raw distance legitimately cannot change.
    // =============================================================================================

    /** A 10 min cafe stop with a perfect GPS fix, where the paired wheel sensor stops broadcasting so
     *  SPEED reads null (the estimator's own KDoc names this stream behaviour). The stop branch is
     *  gated on `speedMs != null`, so a still bike is indistinguishable from a blind one: the loss
     *  clock runs the whole stop, fires "GPS lost" and then BLANKS the field for 7 of the 10 minutes. */
    @Test fun `H3 - a ten minute cafe stop with a silent speed sensor blanks the field for seven minutes`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val beforeOdo = r.odoM
        repeat(600) { t += 1.0; r.tick(d, t, null) }        // 10 min parked, SPEED stream quiet
        println(
            "H3: 600 s parked with a perfect fix -> coastS=${"%.0f".format(r.coastS)} " +
                "alerts=${r.alertsFired} blankedTicks=${r.blankedTicks} phantom=${"%.0f".format(r.odoM - beforeOdo)} m",
        )
        assertEquals("the loss clock runs the whole stop", 600.0, r.coastS, 1e-9)
        assertEquals("a false GPS-lost alert", 1, r.alertsFired)
        assertEquals("and 7 of the 10 minutes show `---`", 421, r.blankedTicks)
        // It self-heals on the first metre ridden.
        t += 1.0; r.tick(d + 6.0, t, 6.0)
        assertEquals(0.0, r.coastS, 1e-9)
        assertEquals(CoastQuality.LIVE, r.coast.quality)
        println("H3: recovery latency = 1 tick")
    }

    /** Control: the SAME stop with the SPEED stream reporting an honest 0.0 is a clean no-op. The
     *  entire fault above is the null, not the stop. */
    @Test fun `H3b - the same stop with speed 0 costs nothing`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        repeat(600) { t += 1.0; r.tick(d, t, 0.0) }
        assertEquals(0.0, r.coastS, 1e-9)
        assertEquals(0, r.alertsFired)
        assertEquals(0, r.blankedTicks)
        println("H3b: control -> coastS=0, alerts=0, blanked=0")
    }

    /** The estimate MARK (not the blank) survives a stop by design. A 40 s tunnel that ends with the
     *  rider stopping at a light for 5 min with a perfect fix: quality stays LONG_LOSS and the number
     *  is rendered as an estimate for all 5 min, because only a raw change may clear it and a stopped
     *  rider produces none. */
    @Test fun `H3c - a stop after a dropout keeps the estimate mark for the whole stop`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val frozen = d
        repeat(40) { t += 1.0; r.tick(frozen, t, 8.0) }      // 40 s blind -> LONG_LOSS
        val markedAfterLoss = r.estimatedTicks
        repeat(300) { t += 1.0; r.tick(frozen, t, 0.0) }     // 5 min at a light, GPS perfect
        println(
            "H3c: estimate-marked ticks ${markedAfterLoss} -> ${r.estimatedTicks} " +
                "(+${r.estimatedTicks - markedAfterLoss} while parked with a good fix), alerts=${r.alertsFired}",
        )
        assertEquals(CoastQuality.LONG_LOSS, r.coast.quality)
        assertTrue("300 stopped ticks all marked as estimates", r.estimatedTicks - markedAfterLoss >= 299)
        assertEquals("but no blank: the clock is frozen at 40 s, not running to 180", 0, r.blankedTicks)
    }

    // =============================================================================================
    // H4 — THE BOUND. What one coast window of the LAST MOVING SPEED actually costs, worst case.
    // =============================================================================================

    /** `lastMovingSpeedMs` is whatever the speed was on the last tick the RAW distance moved, with no
     *  ceiling. Bottom of a fast descent (22 m/s = 79 km/h), rider brakes to a stop at the junction and
     *  the sensor goes quiet: the bound spends 30 s x 22 m/s. */
    @Test fun `H4 - the null-speed bound costs 660 m after a descent, not 180 m`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 22.0; t += 1.0; r.tick(d, t, 22.0) }
        val before = r.odoM; val leadBefore = r.aheadS
        repeat(120) { t += 1.0; r.tick(d, t, null) }   // 120 s, under the give-up so the number is visible
        println(
            "H4: descent -> phantom=${"%.0f".format(r.odoM - before)} m, " +
                "lead ${"%.0f".format(leadBefore)}s -> ${"%.0f".format(r.aheadS)}s, alerts=${r.alertsFired}",
        )
        assertEquals("30 s x the peak speed remembered", 660.0, r.odoM - before, 0.01)
        assertTrue("+78 s of invented lead from a stop", r.aheadS - leadBefore > 70.0)
    }

    /** Worse: nothing sanity-checks `lastMovingSpeedMs`. ONE garbage SPEED sample on a tick where the
     *  raw moved (a GPS speed spike) is remembered forever and becomes the dead-reckoning rate for the
     *  next silent stretch. 100 m/s x 30 s = 3 km of phantom, ~900 s of false lead. */
    @Test fun `H4b - one speed spike poisons the bound into 3 km of phantom distance`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        d += 6.0; t += 1.0; r.tick(d, t, 100.0)          // one spiked sample, raw moved normally
        val before = r.odoM; val leadBefore = r.aheadS
        repeat(60) { t += 1.0; r.tick(d, t, null) }
        println(
            "H4b: one 100 m/s sample -> phantom=${"%.0f".format(r.odoM - before)} m, " +
                "lead ${"%.0f".format(leadBefore)}s -> ${"%.0f".format(r.aheadS)}s",
        )
        // FIXED: `lastMovingSpeedMs` now rejects a sample above AGG_MAX_SPEED_MS (108 km/h — not a
        // bicycle), so the spike is never remembered and the fallback rate stays the ride's real 6 m/s,
        // spent over one 30 s coast window = 180 m. Before the clamp this asserted 3000 m, i.e. the spike
        // set the rate and bought 3 km of phantom distance plus ~840 s of unearned lead on the NEXT
        // dropout — long after the bad sample arrived.
        assertEquals("the ride, not the spike, sets the rate", 180.0, r.odoM - before, 0.01)
        // The lead follows: 180 m of phantom against a 12 km/h target curve is ~54 s, not the ~840 s the
        // spike used to buy. Bounded by the coast window and the rider's own speed, which is the point.
        assertTrue("the invented lead is bounded, not the spike's ~840 s", r.aheadS - leadBefore < 100.0)
    }

    /** The bound only governs the NULL path. A speed that keeps ARRIVING is dead-reckoned without any
     *  budget, so a sensor flapping between null and a stale coasting value while the bike is parked
     *  spends the 30 s budget AND every present-speed tick on top. */
    @Test fun `H4c - a flapping sensor spends the speed budget, not the null window`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val before = r.odoM
        // 10 min parked; the sensor alternately drops out (null) and re-reports its stale 8 m/s.
        repeat(600) { i -> t += 1.0; r.tick(d, t, if (i % 2 == 0) null else 8.0) }
        val phantom = r.odoM - before
        println("H4c: phantom=${"%.0f".format(phantom)} m over a parked 600 s")
        // The 30 s null window bounds only the NULL ticks. Every tick that DOES report a speed is
        // positive evidence of movement and spends the generous MAX_COAST_S budget, so alternating buys
        // 300 present-speed seconds + the 30 s window = 2640 m. That is the designed behaviour, not a
        // bypass: what bounds this case is MAX_COAST_S x the clamped rate, and 600 s is well inside it.
        assertEquals("300 present-speed ticks + the 30 s null window", 8.0 * (300 + 30), phantom, 1e-6)
    }

    /** A wheel sensor that keeps reporting the last speed for 3 s after every stop. 60 stops on a city
     *  commute. Each stop mints speed x 3 s, but the next metre ridden re-anchors, so the phantom does
     *  NOT accumulate across stops — the error at the end of the ride is exactly zero. */
    @Test fun `H4d - a laggy sensor mints per-stop phantom that never accumulates`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        var worst = 0.0
        repeat(60) {
            repeat(120) { d += 7.0; t += 1.0; c.update(d, 7.0, t) }   // 840 m between lights
            repeat(3) { t += 1.0; c.update(d, 7.0, t) }               // sensor still says 7 m/s
            worst = maxOf(worst, c.effectiveDistanceM - d)
            repeat(20) { t += 1.0; c.update(d, 0.0, t) }              // then honest zeros
        }
        d += 7.0; t += 1.0; c.update(d, 7.0, t)
        println("H4d: 60 laggy stops -> worst instantaneous phantom=${"%.0f".format(worst)} m, end-of-ride error=${"%.9f".format(c.effectiveDistanceM - d)} m")
        assertEquals("21 m per stop while it lasts", 21.0, worst, 1e-9)
        assertEquals("zero after 60 of them", d, c.effectiveDistanceM, 0.0)
    }

    /** SPEED delivered in km/h instead of m/s (a host/firmware unit slip). With GPS healthy it is
     *  invisible (the odometer is raw), and a genuine stop still reads 0 — but every dropout is
     *  dead-reckoned 3.6x too fast, and the 0.5 threshold now means 0.14 m/s, so a slow crawl is
     *  "moving". Quantifies how far the unit slip can travel before the re-anchor kills it. */
    @Test fun `H4e - a km per hour SPEED stream only bites inside a dropout, at 3_6x`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; c.update(d, 6.0 * 3.6, t) }
        assertEquals("healthy GPS hides the slip entirely", d, c.effectiveDistanceM, 0.0)
        val frozen = d
        repeat(30) { t += 1.0; c.update(frozen, 6.0 * 3.6, t) }
        val over = c.effectiveDistanceM - frozen
        println("H4e: 30 s dropout coasts ${"%.0f".format(over)} m instead of 180 m (${"%.1f".format(over / 180.0)}x)")
        assertEquals(648.0, over, 0.01)
        t += 1.0; c.update(frozen + 6.0, null, t)
        assertEquals("and the re-anchor wipes it", frozen + 6.0, c.effectiveDistanceM, 0.0)
    }

    // =============================================================================================
    // H5 — PERMANENT LOSS. The only regime where the integration really is unbounded.
    // =============================================================================================

    /** 4 h with the raw distance frozen forever (dead GPS chip / an indoor ride whose DISTANCE never
     *  advances) while SPEED reports a healthy 8 m/s. Nothing ever clears the loss clock, so the
     *  odometer integrates 115 km that no consumer will ever refund. The no-route field blanks after
     *  3 min and stays blank; the ROUTE consumer never calls handleGpsLoss at all, so it keeps racing
     *  on the invented odometer (its gap holds, but progressM/ghostProgressM are fiction). */
    @Test fun `H5 - a permanent loss is bounded at one coast budget, not 115 km`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val frozen = d
        repeat(4 * 3600) { t += 1.0; r.tick(frozen, t, 8.0) }
        println(
            "H5: 4 h blind -> odo=${"%.0f".format(r.odoM)} m (raw ${"%.0f".format(frozen)} m), " +
                "coastS=${"%.0f".format(r.coastS)}, blanked=${r.blankedTicks}/${4 * 3600}",
        )
        // FIXED: one loss buys at most MAX_COAST_S (1800 s) of dead reckoning from any speed source, so
        // four hours of a sensor insisting on 8 m/s invents 14.4 km and then the odometer freezes. Before
        // the bound this asserted > 115 km. The cap is deliberately generous — a 180 s version was tried
        // and rejected because it cut 420 m off a real 450 s urban loss (see Adv2CoastPipelineTest).
        assertEquals("bounded at one coast budget", 8.0 * 1_800.0, r.odoM - frozen, 8.0)
        assertEquals("the no-route field is dark for all but the first 3 min", 4 * 3600 - 179, r.blankedTicks)

        // The route consumer has no give-up at all — its gap holds (neutral fill) but riderDist is fiction.
        val rr = RouteRig()
        var d2 = 0.0; var t2 = 0.0
        repeat(60) { d2 += 8.0; t2 += 1.0; rr.tick(d2, t2, 8.0, 0.1) }
        val gapBefore = rr.gap
        repeat(4 * 3600) { t2 += 1.0; rr.tick(d2, t2, 8.0, 0.1) }
        println("H5: route gap ${"%.1f".format(gapBefore)}s -> ${"%.1f".format(rr.gap)}s after 4 h blind, riderDist=${"%.0f".format(rr.coast.effectiveDistanceM)} m")
        assertTrue("the route NUMBER is unharmed (neutral fill)", abs(rr.gap - gapBefore) < 2.0)
    }

    // =============================================================================================
    // H6 — TICK CADENCE. sample(1000).conflate() does not deliver exact 1 s steps.
    // =============================================================================================

    /** A dropout during a hard deceleration, integrated on an irregular tick (0.4-2.0 s), against the
     *  same dropout integrated at a true 10 Hz. Zero-order hold on a changing speed: is the error
     *  one-signed? Also drives the dt == 0 case (a repeated ELAPSED_TIME value, which the production
     *  combine can emit). */
    @Test fun `H6 - irregular tick cadence during a dropout is a small two-signed error`() {
        fun speedAt(x: Double) = (14.0 - 0.4 * x).coerceAtLeast(0.5)  // 14 m/s braking to 0.5 over 34 s
        // Truth by fine integration.
        var truth = 0.0
        var x = 0.0
        while (x < 30.0) { truth += speedAt(x) * 0.01; x += 0.01 }

        for (seed in 1..5) {
            val c = CoastingEstimator()
            val rnd = Random(seed)
            var d = 0.0; var t = 0.0
            repeat(30) { d += 14.0; t += 1.0; c.update(d, 14.0, t) }
            val frozen = d; val base = c.effectiveDistanceM
            var el = 0.0
            while (el < 30.0) {
                val dt = if (rnd.nextInt(6) == 0) 0.0 else 0.4 + rnd.nextDouble() * 1.6
                el += dt; t += dt
                c.update(frozen, speedAt(el), t)
            }
            val err = (c.effectiveDistanceM - base) - truth
            println("H6 seed=$seed: coasted=${"%.1f".format(c.effectiveDistanceM - base)} m vs truth ${"%.1f".format(truth)} m, err=${"%.1f".format(err)} m")
            assertTrue("bounded well under 5% of the coasted distance", abs(err) < 0.05 * truth)
        }
    }

    /** A backward ELAPSED_TIME step (the host re-zeroing / a clock correction) must not rewind the
     *  odometer or the loss clock. */
    @Test fun `H6b - a backward elapsed step is a no-op, not a negative integration`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(50) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        repeat(10) { t += 1.0; c.update(frozen, 6.0, t) }
        val odo = c.effectiveDistanceM; val loss = c.coastingSeconds
        c.update(frozen, 6.0, t - 500.0)                     // elapsed jumps back 500 s
        println("H6b: after a -500 s elapsed jump, odo ${"%.1f".format(odo)} -> ${"%.1f".format(c.effectiveDistanceM)}, loss ${"%.0f".format(loss)} -> ${"%.0f".format(c.coastingSeconds)}")
        assertEquals(odo, c.effectiveDistanceM, 1e-9)
        assertEquals(loss, c.coastingSeconds, 1e-9)
    }
}
