package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * ADVERSARIAL ROUND 4 — targets ONLY the two changes made in 3fa35e6 (reject an implausible remembered
 * speed) and b5981c2 (bound dead reckoning from a REPORTED speed at MAX_COAST_S).
 *
 * Rigs are the two consumers, copied from Adv2/Adv3 (private there).
 */
class Adv4CoastBoundTest {

    // =============================================================================================
    // RIGS
    // =============================================================================================

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

    private class RouteRig(vp: Double = 0.3) {
        val coast = CoastingEstimator()
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = vp, decimateM = 20.0)
        private var moveStart: Double? = null
        private var prevEl: Double? = null
        private var integLast = 0.0

        var raceClockS = 0.0; private set

        fun tick(rawDistM: Double, elapsedS: Double, speedMs: Double?, pace: Double?, fixFresh: Boolean = true) {
            coast.update(rawDistM, speedMs, elapsedS)
            if (moveStart == null && speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS) moveStart = elapsedS
            var ms = moveStart ?: return
            val riderDist = coast.effectiveDistanceM
            val p = prevEl
            if (p != null && elapsedS > p && riderDist <= integLast) { ms += (elapsedS - p); moveStart = ms }
            prevEl = elapsedS
            raceClockS = elapsedS - ms
            val paceNow = if (verdictAllowed(fixFresh, coast.quality)) pace else null
            g.onTick(riderDist, 0.0, riderDist * 1e-5, 90.0, elapsedS - ms) { _, _, _ -> paceNow }
            integLast = riderDist
        }

        val gap get() = g.gapTimeS
    }

    /** The pre-3fa35e6 / pre-b5981c2 `update()` body, verbatim, so "would the author's test fail if the
     *  change were reverted?" can be answered without touching main source. */
    private class LegacyCoast(
        private val coastWindowMs: Long = CoastingEstimator.COAST_WINDOW_MS,
        private val minMovingMs: Double = StalenessLogic.MIN_MOVING_MS,
    ) {
        var effectiveDistanceM = 0.0; private set
        var quality = CoastQuality.LIVE; private set
        var coastingSeconds = 0.0; private set
        private var lastRawM = Double.NaN
        private var prevElapsedS = Double.NaN
        private var lastMovingSpeedMs = 0.0
        private var unprovenCoastS = 0.0

        fun update(rawDistanceM: Double, speedMs: Double?, elapsedS: Double) {
            if (!rawDistanceM.isFinite() || !elapsedS.isFinite()) return
            val firstCall = prevElapsedS.isNaN()
            val dt = if (firstCall) 0.0 else (elapsedS - prevElapsedS).coerceAtLeast(0.0)
            prevElapsedS = elapsedS
            val changed = rawDistanceM != lastRawM || firstCall
            lastRawM = rawDistanceM
            if (changed) {
                if (speedMs != null && speedMs >= minMovingMs) lastMovingSpeedMs = speedMs
                effectiveDistanceM = rawDistanceM
                quality = CoastQuality.LIVE
                coastingSeconds = 0.0
                unprovenCoastS = 0.0
                return
            }
            if (speedMs != null && speedMs < minMovingMs) { quality = qualityOf(coastingSeconds); return }
            if (lastMovingSpeedMs <= 0.0) {
                effectiveDistanceM = rawDistanceM; quality = CoastQuality.LIVE; coastingSeconds = 0.0; return
            }
            coastingSeconds += dt
            if (speedMs != null) {
                effectiveDistanceM += speedMs * dt
            } else {
                val budgetS = (coastWindowMs / 1000.0 - unprovenCoastS).coerceIn(0.0, dt)
                unprovenCoastS += budgetS
                effectiveDistanceM += lastMovingSpeedMs * budgetS
            }
            quality = qualityOf(coastingSeconds)
        }

        private fun qualityOf(lossS: Double) = when {
            lossS <= 0.0 -> CoastQuality.LIVE
            lossS * 1000.0 <= coastWindowMs -> CoastQuality.COASTING
            else -> CoastQuality.LONG_LOSS
        }
    }

    // =============================================================================================
    // A — THE SPEED CLAMP (3fa35e6). Does it cover the rate every dead-reckoned metre is invented at?
    // =============================================================================================

    /** REGRESSION LOCK (finding 1). THE SAME corrupt sample the remembered-rate guard targets, arriving
     *  ONE TICK LATER — while the raw distance is already frozen. The reported-speed path now CLAMPS to
     *  AGG_MAX_SPEED_MS instead of spending the sample raw: we need a rate for this tick and the reported
     *  one is all we have, so cap the corruption rather than discard the tick. Pre-fix: 100 m in one
     *  tick. */
    @Test fun `A1 - a 100 m per s sample DURING the freeze is clamped to the bicycle ceiling`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }   // 6 m/s cruising, healthy fix
        val frozen = d
        t += 1.0; c.update(frozen, 100.0, t)                     // ONE corrupt sample, raw now frozen
        val phantom = c.effectiveDistanceM - frozen
        println("A1: one 100 m/s sample inside a dropout coasts ${"%.0f".format(phantom)} m (was 100 m unclamped)")
        assertEquals("clamped to the not-a-bicycle ceiling", AGG_MAX_SPEED_MS, phantom, 1e-9)
    }

    /** REGRESSION LOCK (finding 1). Sustained: the corrupt reading sticks (a stuck/garbage SPEED
     *  register) while the raw stays frozen. The duration cap alone left the worst case at
     *  MAX_COAST_S x the corrupt RATE = 180 km; with the rate clamped the worst case is
     *  MAX_COAST_S x AGG_MAX_SPEED_MS = 54 km, which is what "no bicycle goes faster for longer than
     *  this" actually bounds. */
    @Test fun `A2 - a stuck 100 m per s sensor is bounded by rate AND time`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        repeat(4 * 3600) { t += 1.0; c.update(frozen, 100.0, t) }
        val phantom = c.effectiveDistanceM - frozen
        println("A2: stuck 100 m/s -> ${"%.0f".format(phantom / 1000.0)} km of phantom (was 180 km)")
        assertEquals("MAX_COAST_S x the CLAMPED rate", AGG_MAX_SPEED_MS * CoastingEstimator.MAX_COAST_S, phantom, 1e-6)
        assertTrue("no longer an order of magnitude past the duration bound", phantom < 100_000.0)
    }

    /** REGRESSION LOCK (finding 2). A persistently unit-slipped SPEED stream (km/h on the wire: a rider
     *  at 9 m/s reports 32.4) is rejected as a REMEMBERED rate on every sample, so `lastMovingSpeedMs`
     *  never leaves 0.0. That used to trip the `lastMovingSpeedMs <= 0.0` guard — written for "the rider
     *  has never moved yet" — mid-ride for a rider who IS moving: a 10-minute loss reported LIVE with a
     *  loss clock of 0, i.e. 0 alerts, 0 estimate marks, 0 blanks. Silent-wrong.
     *  Now the guard only covers the no-rate-at-all case, so a REPORTED speed is spent (clamped), and the
     *  loss announces itself. */
    @Test fun `A3 - a persistently rejected speed no longer silences the estimator`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 9.0; t += 1.0; c.update(d, 9.0 * 3.6, t) }   // km/h stream: reports 32.4, all rejected
        val frozen = d
        repeat(600) { t += 1.0; c.update(frozen, 9.0 * 3.6, t) }        // 10 min genuinely blind while riding
        println("A3: 600 s blind with a km/h stream -> odo +${"%.0f".format(c.effectiveDistanceM - frozen)} m, quality=${c.quality}, lossClock=${c.coastingSeconds} (was 0 m / LIVE / 0 s)")
        assertEquals("dead-reckoned at the CLAMPED reported rate", AGG_MAX_SPEED_MS * 600, c.effectiveDistanceM - frozen, 1e-6)
        assertEquals("and flagged as an estimate", CoastQuality.LONG_LOSS, c.quality)
        assertEquals("the loss clock runs, so the alert and the give-up work", 600.0, c.coastingSeconds, 1e-6)

        // Pre-fix: the same stream remembered 32.4 and coasted at it — wrong by 3.6x, but LOUD.
        val l = LegacyCoast()
        var dl = 0.0; var tl = 0.0
        repeat(60) { dl += 9.0; tl += 1.0; l.update(dl, 9.0 * 3.6, tl) }
        val fl = dl
        repeat(600) { tl += 1.0; l.update(fl, 9.0 * 3.6, tl) }
        assertEquals("legacy coasted 19 440 m (3.6x the true 5400)", 9.0 * 3.6 * 600, l.effectiveDistanceM - fl, 1e-6)
        assertEquals("and flagged it", CoastQuality.LONG_LOSS, l.quality)

        // Through the no-route consumer: the alert and the give-up blank both vanish.
        val r = VpRig()
        var dr = 0.0; var tr = 0.0
        repeat(60) { dr += 9.0; tr += 1.0; r.tick(dr, tr, 9.0 * 3.6) }
        val fr = dr
        repeat(600) { tr += 1.0; r.tick(fr, tr, 9.0 * 3.6) }
        println("A3: consumer sees alerts=${r.alertsFired} estimated=${r.estimatedTicks} blanked=${r.blankedTicks} over a real 600 s loss (was 0/0/0)")
        assertEquals("the 10-minute loss announces itself, once", 1, r.alertsFired)
        assertTrue("the number is marked as an estimate", r.estimatedTicks > 100)
        assertTrue("and past GPS_GIVEUP_S the field blanks", r.blankedTicks > 400)
    }

    /** REGRESSION LOCK (finding 2), the pure form: the speed stream then goes SILENT, so there is no rate
     *  at all — not a reported one, and not a remembered one (every sample was implausible). We invent no
     *  distance, but the loss clock and the quality MUST still run, otherwise the dropout is presented as
     *  a trusted reading. The one case that legitimately stays silent is a rider who has never moved
     *  (`everMoved` false); a raw distance that GREW is proof of movement independent of any speed. */
    @Test fun `A3c - blind with no usable rate invents nothing but still announces the loss`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 9.0; t += 1.0; c.update(d, 9.0 * 3.6, t) }   // km/h stream: never remembered
        val frozen = d
        repeat(600) { t += 1.0; c.update(frozen, null, t) }             // and now silent, 10 min blind
        println("A3c: no rate at all -> odo +${c.effectiveDistanceM - frozen} m, quality=${c.quality}, lossClock=${c.coastingSeconds}")
        assertEquals("no rate means no invented metres", 0.0, c.effectiveDistanceM - frozen, 1e-9)
        assertEquals("but the loss is flagged", CoastQuality.LONG_LOSS, c.quality)
        assertEquals("and the clock the alert and the give-up run on keeps time", 600.0, c.coastingSeconds, 1e-6)

        // The standing start is still silent: nothing ever moved, so nothing is wrong.
        val n = CoastingEstimator()
        n.update(0.0, null, 0.0)
        repeat(600) { i -> n.update(0.0, null, i + 1.0) }
        assertEquals(CoastQuality.LIVE, n.quality)
        assertEquals(0.0, n.coastingSeconds, 0.0)
    }

    /** The same trap without any unit slip: a sensor stuck above the ceiling from the first sample. Any
     *  stream that never once lands in [0.5, 30] m/s disables dead reckoning AND the GPS-loss signalling
     *  for the whole ride. One plausible sample anywhere in the ride is enough to arm it again. */
    @Test fun `A3b - one plausible sample anywhere in the ride re-arms the estimator`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(10) { d += 9.0; t += 1.0; c.update(d, 40.0, t) }   // all rejected
        t += 1.0; d += 9.0; c.update(d, 9.0, t)                    // ONE plausible sample
        repeat(10) { d += 9.0; t += 1.0; c.update(d, 40.0, t) }
        val frozen = d
        repeat(60) { t += 1.0; c.update(frozen, null, t) }
        assertEquals("armed by the single good sample: 30 s x 9 m/s", 9.0 * 30, c.effectiveDistanceM - frozen, 1e-9)
        assertEquals(CoastQuality.LONG_LOSS, c.quality)
    }

    /** Boundary of `speedMs in minMovingMs..AGG_MAX_SPEED_MS`: inclusive both ends, and it meets the
     *  stop test (`speedMs < minMovingMs`) exactly — no gap, no overlap. */
    @Test fun `A4 - the clamp boundary is exact and leaves no hole against the stop test`() {
        fun remembered(sample: Double): Double {
            val c = CoastingEstimator()
            c.update(0.0, 5.0, 0.0)
            c.update(10.0, 5.0, 1.0)          // remembered = 5.0
            c.update(20.0, sample, 2.0)       // candidate
            var t = 2.0
            repeat(10) { t += 1.0; c.update(20.0, null, t) }  // spend 10 s of the remembered rate
            return (c.effectiveDistanceM - 20.0) / 10.0
        }
        assertEquals("exactly 30.0 accepted", 30.0, remembered(30.0), 1e-9)
        assertEquals("30 + 1 ulp rejected, previous stands", 5.0, remembered(Math.nextUp(30.0)), 1e-9)
        assertEquals("exactly 0.5 accepted", 0.5, remembered(StalenessLogic.MIN_MOVING_MS), 1e-9)
        assertEquals("just below 0.5 not remembered", 5.0, remembered(Math.nextDown(0.5)), 1e-9)
    }

    /** REJECT-not-clamp means the PREVIOUS value stands. Construct the case where that is worse: a crawl
     *  immediately followed by a single implausible sample and then silence. */
    @Test fun `A5 - a stale crawl beats a capped corruption only because the jump is synthetic`() {
        val c = CoastingEstimator()
        // 0.6 m/s crawl (a steep ramp), then ONE 40 m/s sample, then the speed stream dies mid-tunnel.
        var d = 0.0; var t = 0.0
        repeat(20) { d += 0.6; t += 1.0; c.update(d, 0.6, t) }
        t += 1.0; d += 40.0; c.update(d, 40.0, t)   // rejected
        val frozen = d
        repeat(30) { t += 1.0; c.update(frozen, null, t) }
        val coasted = c.effectiveDistanceM - frozen
        println("A5: rejected -> coasts ${"%.0f".format(coasted)} m in 30 s; clamped-at-30 would coast 900 m; the sample itself claims 1200 m")
        assertEquals("the stale crawl rate is what gets spent", 0.6 * 30, coasted, 1e-9)
        // Reaching this state needs the speed to jump 0.6 -> 40 in ONE tick with no plausible sample in
        // between: a real descent passes through 5, 10, 20, 29 m/s, all accepted. Sanity-check that.
        val c2 = CoastingEstimator()
        var d2 = 0.0; var t2 = 0.0
        repeat(20) { d2 += 0.6; t2 += 1.0; c2.update(d2, 0.6, t2) }
        for (v in listOf(3.0, 8.0, 15.0, 22.0, 29.0, 31.0)) { t2 += 1.0; d2 += v; c2.update(d2, v, t2) }
        val frozen2 = d2
        repeat(30) { t2 += 1.0; c2.update(frozen2, null, t2) }
        assertEquals("a real accelerating descent leaves 29 m/s remembered, not the crawl", 29.0 * 30, c2.effectiveDistanceM - frozen2, 1e-9)
    }

    /** REGRESSION LOCK (finding 3). A NaN speed passed the input guard (which checked only distance and
     *  elapsed) and the `< minMovingMs` stop test (NaN compares false), reached the coast path and
     *  poisoned the odometer PERMANENTLY — the budget freeze does not help, NaN * 0.0 is NaN. Production
     *  survived only because the CALLER filters (`takeIf { it.isFinite() }`). The class now treats a
     *  non-finite speed as ABSENT, so it falls to the remembered-rate path like any other silent tick. */
    @Test fun `A6 - a NaN speed is treated as an absent speed`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        t += 1.0; c.update(frozen, Double.NaN, t)
        println("A6: after one NaN speed sample -> odo=${c.effectiveDistanceM} (was NaN)")
        assertEquals("one second of the remembered 6 m/s, not NaN", frozen + 6.0, c.effectiveDistanceM, 1e-9)
        // ...and it stays finite while blind, right past the budget.
        val c2 = CoastingEstimator()
        var d2 = 0.0; var t2 = 0.0
        repeat(30) { d2 += 6.0; t2 += 1.0; c2.update(d2, 6.0, t2) }
        val f2 = d2
        t2 += 1.0; c2.update(f2, Double.NaN, t2)
        repeat(4000) { t2 += 1.0; c2.update(f2, 6.0, t2) }
        assertEquals("still finite 4000 s later: the whole budget at 6 m/s", 6.0 * CoastingEstimator.MAX_COAST_S, c2.effectiveDistanceM - f2, 1e-6)
    }

    // =============================================================================================
    // B — THE TWO BUDGETS (b5981c2). Interaction, double-counting, starvation, worst mixed order.
    // =============================================================================================

    /** INVARIANT: over any interleaving of null / present / stopped / paused / backward-elapsed ticks
     *  within ONE loss, the total dead-reckoned SECONDS never exceed MAX_COAST_S and the null-sourced
     *  seconds never exceed one coast window. Checked by reconstructing the spend from the odometer. */
    @Test fun `B1 - randomised mixed sequences never exceed either budget`() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val c = CoastingEstimator()
            var d = 0.0; var t = 0.0
            repeat(30) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }   // remembered rate = 6.0
            val frozen = d
            var nullSpend = 0.0
            var totalSpend = 0.0
            var odo = c.effectiveDistanceM
            repeat(6000) {
                val dt = if (rnd.nextInt(8) == 0) 0.0 else 0.5 + rnd.nextDouble() * 1.5
                t += dt
                val speed: Double? = when (rnd.nextInt(4)) {
                    0 -> null
                    1 -> 0.0            // provable stop
                    else -> 6.0
                }
                c.update(frozen, speed, t)
                val gained = c.effectiveDistanceM - odo
                odo = c.effectiveDistanceM
                totalSpend += gained / 6.0
                if (speed == null) nullSpend += gained / 6.0
            }
            assertTrue("seed=$seed total spend ${"%.3f".format(totalSpend)} s <= MAX_COAST_S", totalSpend <= CoastingEstimator.MAX_COAST_S + 1e-6)
            assertTrue("seed=$seed null spend ${"%.3f".format(nullSpend)} s <= one coast window", nullSpend <= 30.0 + 1e-6)
        }
    }

    /** ORDERING: does spending the speed budget first STARVE the null budget, or vice versa? Both
     *  directions, to the second. */
    @Test fun `B2 - the speed path starves the null path, never the reverse-double-spend`() {
        // (i) speed first, to exhaustion, then silence.
        val a = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 6.0; t += 1.0; a.update(d, 6.0, t) }
        val fa = d
        repeat(1800) { t += 1.0; a.update(fa, 6.0, t) }
        val afterSpeed = a.effectiveDistanceM
        repeat(60) { t += 1.0; a.update(fa, null, t) }
        println("B2(i): budget spent on speed, then 60 s of silence buys ${"%.1f".format(a.effectiveDistanceM - afterSpeed)} m")
        assertEquals("the null window is starved to zero — total cap wins", 0.0, a.effectiveDistanceM - afterSpeed, 1e-9)

        // (ii) silence first (spends its 30 s window), then speed for the rest.
        val b = CoastingEstimator()
        var d2 = 0.0; var t2 = 0.0
        repeat(30) { d2 += 6.0; t2 += 1.0; b.update(d2, 6.0, t2) }
        val fb = d2
        repeat(60) { t2 += 1.0; b.update(fb, null, t2) }      // only 30 s of it is spent
        assertEquals(6.0 * 30, b.effectiveDistanceM - fb, 1e-9)
        repeat(4000) { t2 += 1.0; b.update(fb, 6.0, t2) }
        val total = (b.effectiveDistanceM - fb) / 6.0
        println("B2(ii): silence-then-speed spends ${"%.1f".format(total)} s total (cap ${CoastingEstimator.MAX_COAST_S})")
        assertEquals("the 30 s of silence counts against the SAME total — no double spend", CoastingEstimator.MAX_COAST_S, total, 1e-6)
    }

    /** The worst mixed order I can build: alternate null / speed every tick so BOTH counters advance,
     *  with stops sprinkled in to freeze the odometer without clearing anything. Total is still capped. */
    @Test fun `B3 - the worst interleaving still lands exactly on the cap`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        repeat(20_000) { i ->
            t += 1.0
            c.update(frozen, when (i % 3) { 0 -> null; 1 -> 0.0; else -> 6.0 }, t)
        }
        val spentS = (c.effectiveDistanceM - frozen) / 6.0
        println("B3: null/stop/speed round-robin over 20 000 s -> ${"%.1f".format(spentS)} s dead-reckoned")
        assertEquals(CoastingEstimator.MAX_COAST_S, spentS, 1e-6)
    }

    // =============================================================================================
    // C — RESET CORRECTNESS. `coastSpentS` is cleared ONLY on the `changed` branch.
    // =============================================================================================

    /** A raw distance that TWITCHES once (GPS noise, a map-match nudge) refreshes the whole budget. This
     *  is a real budget-refresh channel — but the same branch re-anchors `effective = raw`, so the
     *  phantom is DISCARDED at the same instant. Net: the twitch cannot accumulate the runaway. */
    @Test fun `C1 - a raw twitch refreshes the budget but also wipes the phantom`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 8.0; t += 1.0; c.update(d, 8.0, t) }
        var raw = d
        // 4 h parked with a lying 8 m/s sensor, but the raw odometer twitches 1 cm every 1800 s.
        repeat(8) {
            repeat(1800) { t += 1.0; c.update(raw, 8.0, t) }
            raw += 0.01
            t += 1.0; c.update(raw, 8.0, t)      // twitch: LIVE, budget reset, phantom wiped
        }
        println("C1: 4 h parked with a twitch every 30 min -> odo=${"%.2f".format(c.effectiveDistanceM)} m, raw=${"%.2f".format(raw)} m")
        assertEquals("the re-anchor beats the budget refresh — no accumulation", raw, c.effectiveDistanceM, 1e-9)
        // ...but the budget IS fresh, so the very next loss gets a full 1800 s again.
        repeat(4000) { t += 1.0; c.update(raw, 8.0, t) }
        assertEquals("full budget available after each twitch", 8.0 * 1800.0, c.effectiveDistanceM - raw, 1e-6)
    }

    /** Every path that does NOT clear coastSpentS: a stop, a pause (elapsed frozen), the never-moved
     *  branch. None of them should clear it, and none of them spends. */
    @Test fun `C2 - stop, pause and never-moved neither spend nor clear the budget`() {
        // Stop: budget survives (same loss), spends nothing.
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        repeat(1790) { t += 1.0; c.update(frozen, 6.0, t) }
        val atStop = c.effectiveDistanceM
        repeat(600) { t += 1.0; c.update(frozen, 0.0, t) }        // 10 min at a light, still blind
        assertEquals("a stop adds nothing", atStop, c.effectiveDistanceM, 0.0)
        repeat(100) { t += 1.0; c.update(frozen, 6.0, t) }
        assertEquals("and does NOT refund the budget: only 10 s left", atStop + 60.0, c.effectiveDistanceM, 1e-6)
        assertEquals("the loss clock, however, kept counting only blind-and-moving seconds", 1890.0, c.coastingSeconds, 1e-6)

        // Pause: elapsed frozen -> dt == 0 -> nothing spent, budget untouched.
        val p = CoastingEstimator()
        var dp = 0.0; var tp = 0.0
        repeat(30) { dp += 6.0; tp += 1.0; p.update(dp, 6.0, tp) }
        val fp = dp
        repeat(3600) { p.update(fp, 6.0, tp) }                     // 1 h of paused ticks at the SAME elapsed
        assertEquals("a pause invents nothing", fp, p.effectiveDistanceM, 0.0)
        repeat(4000) { tp += 1.0; p.update(fp, 6.0, tp) }
        assertEquals("and spends none of the budget", 6.0 * 1800.0, p.effectiveDistanceM - fp, 1e-6)

        // Never-moved: no plausible sample was ever remembered, so the frozen distance reads LIVE.
        val n = CoastingEstimator()
        n.update(0.0, null, 0.0)
        repeat(300) { i -> n.update(0.0, null, i + 1.0) }
        assertEquals(CoastQuality.LIVE, n.quality)
        assertEquals(0.0, n.coastingSeconds, 0.0)
    }

    /** A backward raw step (a source reset / a new activity on the same estimator) is `changed`, so it
     *  clears both budgets and re-anchors. */
    @Test fun `C3 - a backward raw step and a ride restart both reset the budget`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(30) { d += 6.0; t += 1.0; c.update(d, 6.0, t) }
        val frozen = d
        repeat(2000) { t += 1.0; c.update(frozen, 6.0, t) }        // budget fully spent
        t += 1.0; c.update(0.0, 6.0, t)                            // ride restart: odometer back to 0
        assertEquals(0.0, c.effectiveDistanceM, 0.0)
        assertEquals(0.0, c.coastingSeconds, 0.0)
        assertEquals(CoastQuality.LIVE, c.quality)
        repeat(4000) { t += 1.0; c.update(0.0, 6.0, t) }
        assertEquals("fresh ride, fresh budget", 6.0 * 1800.0, c.effectiveDistanceM, 1e-6)
    }

    // =============================================================================================
    // D — IS 1800 s SAFE? Find a LEGITIMATE loss it truncates.
    // =============================================================================================

    /** Walking a bike through a long tunnel / a building at 1.1 m/s. 1800 s of budget is 2 km of walking;
     *  anything longer is truncated. Also: 30 min at any cycling speed is 9-18 km of continuous GPS loss,
     *  which no real tunnel provides. Quantifies the shortfall for the worst realistic case. */
    @Test fun `D1 - the only legitimate loss 1800 s truncates is an hour of walking pace`() {
        // A 45-minute push through a building / a closed tunnel at 4 km/h.
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 1.1; t += 1.0; c.update(d, 1.1, t) }
        val frozen = d
        repeat(2700) { t += 1.0; c.update(frozen, 1.1, t) }
        val shortfall = 1.1 * 2700 - (c.effectiveDistanceM - frozen)
        println("D1: 45 min of walking blind -> coasted ${"%.0f".format(c.effectiveDistanceM - frozen)} m of a true ${"%.0f".format(1.1 * 2700)} m (short by ${"%.0f".format(shortfall)} m)")
        assertEquals(1.1 * 900, shortfall, 1e-6)
        // The same 45 min at a cycling 8 m/s would be a 21.6 km continuous tunnel — no such road tunnel is
        // open to bicycles; the longest (Gotthard, 17 km) is not, and the longest cyclable ones are < 5 km.
        val fast = CoastingEstimator()
        var d2 = 0.0; var t2 = 0.0
        repeat(60) { d2 += 8.0; t2 += 1.0; fast.update(d2, 8.0, t2) }
        val f2 = d2
        repeat(600) { t2 += 1.0; fast.update(f2, 8.0, t2) }        // a 4.8 km tunnel, 10 min
        assertEquals("every realistic tunnel is coasted end to end", 8.0 * 600, fast.effectiveDistanceM - f2, 1e-6)
    }

    /** A stop-start urban loss lasting most of an hour: 55 min wall, but only the BLIND-AND-MOVING
     *  seconds are spent, so the budget covers far more real time than 1800 s of clock. */
    @Test fun `D2 - a 55 minute stop-start urban loss is coasted whole because stops cost nothing`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val frozen = d
        var movingS = 0.0
        repeat(44) {                                   // 44 blocks x (25 s rolling + 50 s at a light) = 55 min
            repeat(25) { t += 1.0; movingS += 1.0; r.tick(frozen, t, 6.0) }
            repeat(50) { t += 1.0; r.tick(frozen, t, 0.0) }
        }
        println("D2: 55 min urban loss, ${"%.0f".format(movingS)} s of it moving -> odo=${"%.0f".format(r.odoM)} m, truth=${"%.0f".format(1200 + 6.0 * movingS)} m")
        assertEquals("under the budget: every ridden metre is still coasted", 1200.0 + 6.0 * movingS, r.odoM, 1e-6)
        assertTrue("only $movingS s of the 3300 s wall clock cost budget", movingS < CoastingEstimator.MAX_COAST_S)
    }

    /** A ferry / train: the wheel is still, so the stop branch fires and NOTHING is dead-reckoned. The
     *  budget is irrelevant to those. */
    @Test fun `D3 - a ferry or train spends no budget at all`() {
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 7.0; t += 1.0; c.update(d, 7.0, t) }
        val frozen = d
        repeat(3 * 3600) { t += 1.0; c.update(frozen, 0.0, t) }    // 3 h on a ferry, wheel still
        assertEquals(frozen, c.effectiveDistanceM, 0.0)
        repeat(1000) { t += 1.0; c.update(frozen, 7.0, t) }        // rolls off, still no fix
        assertEquals("full budget intact after 3 h aboard", 7.0 * 1000, c.effectiveDistanceM - frozen, 1e-6)
    }

    // =============================================================================================
    // E — WHAT THE TWO CONSUMERS SEE AT THE MOMENT THE BUDGET RUNS OUT.
    // =============================================================================================

    /** No-route Ghost-Pace: the gap reads effectiveDistanceM straight against the target curve. When the
     *  odometer freezes at 1800 s the gap would start collapsing at the target pace — except the rig's
     *  own give-up (GPS_GIVEUP_S = 180 s) blanked the field 27 minutes earlier. The bound is invisible
     *  to this consumer. */
    @Test fun `E1 - the no-route field is already blank 1620 s before the budget runs out`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val frozen = d
        var lastVisibleAtLossS = 0.0
        repeat(3600) {
            t += 1.0; r.tick(frozen, t, 8.0)
            if (r.gap != null) lastVisibleAtLossS = r.coastS
        }
        println("E1: last visible gap at lossS=${"%.0f".format(lastVisibleAtLossS)}; budget runs out at ${CoastingEstimator.MAX_COAST_S}")
        assertTrue("the field goes dark ~1620 s before the odometer freezes", lastVisibleAtLossS < 180.0)
        assertEquals("so the bound changes NOTHING the rider sees in this mode", 3600 - 179, r.blankedTicks)
    }

    /** Route mode has no give-up — but the moving-time race clock is keyed on the odometer delta, so
     *  freezing the odometer also freezes the race clock. Compare the bounded engine against the legacy
     *  one over the same 4 h blind stretch. */
    @Test fun `E2 - in route mode the bound freezes the race clock as a side effect`() {
        val r = RouteRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0, 0.1) }
        val gapBefore = r.gap
        val clockAt1799 = run {
            repeat(1799) { t += 1.0; r.tick(d, t, 8.0, 0.1) }
            r.raceClockS
        }
        repeat(1800) { t += 1.0; r.tick(d, t, 8.0, 0.1) }
        println("E2: race clock ${"%.0f".format(clockAt1799)} s at the cap -> ${"%.0f".format(r.raceClockS)} s 1800 s later; gap ${"%.1f".format(gapBefore)} -> ${"%.1f".format(r.gap)}")
        assertEquals("past the cap the race clock stops advancing (dd == 0 -> the freeze branch fires)", clockAt1799, r.raceClockS, 1.5)
        assertTrue("the gap NUMBER is unharmed either way (neutral fill)", abs(r.gap - gapBefore) < 2.0)
    }

    /** When the fix finally returns, the LIVE branch assigns effective = raw, so the entire phantom —
     *  spent or frozen — is erased in one tick regardless of the bound. */
    @Test fun `E3 - the snap-back erases the phantom whether or not the bound bit`() {
        for (blindS in listOf(600, 1800, 14_400)) {
            val c = CoastingEstimator()
            var d = 0.0; var t = 0.0
            repeat(60) { d += 8.0; t += 1.0; c.update(d, 8.0, t) }
            val frozen = d
            repeat(blindS) { t += 1.0; c.update(frozen, 8.0, t) }
            t += 1.0; c.update(frozen + 3.0, 8.0, t)
            assertEquals("blind ${blindS}s -> snap-back is exact", frozen + 3.0, c.effectiveDistanceM, 0.0)
            assertEquals(CoastQuality.LIVE, c.quality)
            assertEquals(0.0, c.coastingSeconds, 0.0)
        }
    }

    // =============================================================================================
    // F — WOULD THE AUTHOR'S TESTS FAIL IF THE CHANGES WERE REVERTED?
    // =============================================================================================

    /** Replays the author's own assertions against [LegacyCoast] (the pre-fix body). Each must produce
     *  the OLD number, i.e. each test really locks its change. */
    @Test fun `F1 - both author tests fail against the pre-fix implementation`() {
        // 3fa35e6's test: `an implausible speed sample is never remembered as the dead-reckoning rate`.
        val l = LegacyCoast()
        l.update(0.0, 8.0, 0.0); l.update(8.0, 8.0, 1.0); l.update(16.0, 100.0, 2.0)
        var t = 2.0
        repeat(30) { t += 1.0; l.update(16.0, null, t) }
        assertEquals("legacy remembers the spike: 3016 m, not 256", 3016.0, l.effectiveDistanceM, 1e-6)
        assertNotEquals(256.0, l.effectiveDistanceM, 1e-6)

        // b5981c2's test: `a lying sensor cannot invent distance forever`.
        val l2 = LegacyCoast()
        l2.update(0.0, 8.0, 0.0); l2.update(8.0, 8.0, 1.0)
        var t2 = 1.0
        repeat(14_400) { t2 += 1.0; l2.update(8.0, 8.0, t2) }
        println("F1: legacy 4 h -> ${"%.0f".format(l2.effectiveDistanceM / 1000.0)} km; bounded -> 14.4 km")
        assertEquals("legacy invents 115 km", 8.0 + 8.0 * 14_400, l2.effectiveDistanceM, 1e-6)

        // ...and the "genuine tunnel" companion test passes on BOTH, so it locks nothing new.
        val l3 = LegacyCoast()
        l3.update(0.0, 9.0, 0.0); l3.update(9.0, 9.0, 1.0)
        var t3 = 1.0
        repeat(120) { t3 += 1.0; l3.update(9.0, 9.0, t3) }
        assertEquals("the 2-minute-tunnel test is green on the pre-fix code too", 9.0 + 9.0 * 120, l3.effectiveDistanceM, 1e-6)
    }
}
