package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADVERSARIAL round N+1 — target: the gap NUMBER the rider reads on the bike.
 *
 * Everything here drives the FULL production pipeline, not GhostIntegrator alone:
 *
 *     coast.update(distM, speedMs, elapsedS)                      // KGhostExtension.kt:1850
 *     riderDist = coast.effectiveDistanceM                        // :2085
 *     if (prevEl != null && elapsedS > prevEl && riderDist <= integLastRiderDist)
 *         moveStart += (elapsedS - prevEl)                        // :2092 (moving-time race clock)
 *     paceNow = if (fixFresh) tier1 ?: tier2 else null            // :2174-2181
 *     integ.onTick(riderDist, ..., elapsedS - moveStart) { paceNow }   // :2183
 *     integLastRiderDist = riderDist                              // :2184
 *
 * [Rig] is that block verbatim. The previous adversarial pass ([AdversarialGhostIntegratorTest]) fed the
 * integrator a HAND-WRITTEN odometer; it therefore could not see anything that CoastingEstimator does to
 * that odometer before the integrator ever gets it. That is exactly where the fabrication below lives.
 *
 * The two findings this rig originally pinned are now FIXED and the tests below are REGRESSION LOCKS on
 * the corrected numbers (see `.superpowers/sdd/hardening-coast-report.md`):
 *
 *   LOCK 1 — [CoastingEstimator] re-anchors its coast on every legitimately-stopped tick, so a stop that
 *            ELAPSED_TIME keeps counting (auto-pause off) can no longer be dead-reckoned away as one
 *            giant phantom jump.
 *   LOCK 2 — the tick gates the historical-pace lookup on `coast.quality == LIVE` alongside the existing
 *            fix-freshness gate ([Rig.tick] mirrors it), so no dead-reckoned metre ever receives a
 *            historical verdict — which closes the one-signed coast-overshoot ratchet.
 */
class AdvNumPipelineTest {

    /** Faithful replica of the B2 tick: raw host streams in, the gap the rider reads out. */
    private class Rig(
        vp: Double = 0.3,
        pendingToleranceS: Double = CoastingEstimator.PENDING_TOLERANCE_S,
        maxCoastS: Double = CoastingEstimator.MAX_COAST_S,
    ) {
        val coast = CoastingEstimator(maxCoastS = maxCoastS, pendingToleranceS = pendingToleranceS)
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = vp, decimateM = 20.0)
        private var moveStart: Double? = null
        private var prevEl: Double? = null
        private var integLast = 0.0
        private var integInitialised = false
        /** Guard (c): how many ticks published a gap. A pending tick must not. */
        var publishCount = 0; private set

        /** [pace] = what the tiers would answer at this position (null = tier 3 / neutral fill).
         *  [fixFresh] = the GPS-fix freshness gate that both tier 1 and tier 2 sit behind. */
        fun tick(rawDistM: Double, elapsedS: Double, speedMs: Double?, pace: Double?, fixFresh: Boolean = true) {
            // Production normalizes SPEED (`?.takeIf { it.isFinite() }`, KGhostExtension.kt:1947) before
            // either the estimator or the guard sees it; do the same here once, up front.
            val sp = speedMs?.takeIf { it.isFinite() }
            coast.update(rawDistM, sp, elapsedS)
            if (moveStart == null && sp != null && sp > StalenessLogic.MIN_MOVING_MS) moveStart = elapsedS
            var ms = moveStart ?: return // race not started: holdGap() before prevTickElapsedS is stamped
            val riderDist = coast.effectiveDistanceM
            val p = prevEl
            // Guard (a): race clock, keyed on pendingSample — a pending tick must NOT freeze it.
            // A settle tick can land on a stopped rider (CoastingEstimator's `changed` clear runs
            // before its stop branch): freeze there ONLY when the settlement resolved an unresolved
            // hold (`coast.settledPending`) — a coast-recovery residual landing on a stopped tick must
            // NOT freeze (GhostIntegrator ignores the elapsed delta whenever a historical pace is
            // available, so freezing there mints lead with nothing to offset it).
            val stoppedNow = sp != null && sp < StalenessLogic.MIN_MOVING_MS
            if (p != null && elapsedS > p &&
                ((stoppedNow && coast.settledPending) || (riderDist <= integLast && !coast.pendingSample))) {
                ms += (elapsedS - p); moveStart = ms
            }
            prevEl = elapsedS   // ALWAYS, on every tick
            val paceNow = if (verdictAllowed(fixFresh, coast.quality)) pace else null
            // Guard (b): keyed on pendingHold, so it survives a stop inside the interval.
            if (!coast.pendingHold || !integInitialised) {
                g.onTick(riderDist, 0.0, riderDist * 1e-5, 90.0, elapsedS - ms) { _, _, _ -> paceNow }
                integInitialised = true
                integLast = riderDist
            }
            // Guard (c): publication, also keyed on pendingHold.
            if (!coast.pendingHold) publishCount++
        }

        /** What the rider reads: + = ahead of the historical self. */
        val gap get() = g.gapTimeS
    }

    // =============================================================================================
    // LOCK 1 (was FINDING #1, CRITICAL) — a stop without auto-pause must not mint a phantom kilometre.
    //
    // CoastingEstimator's "legitimate stop" branch used to return EARLY without re-anchoring
    // `lastChangedDistanceM` / `lastChangeElapsedS`. So while the rider stood at a red light with
    // ELAPSED_TIME still running (auto-pause off — the way this rider rides, per the field logs), the
    // coast anchor kept ageing. The instant ONE tick reported speed >= MIN_MOVING_MS (0.5 m/s) — or
    // reported speed == null — while the DISTANCE stream had not yet emitted its new value, the
    // frozen-while-moving branch fired with `gapS = the WHOLE stop`, and
    //
    //     effectiveDistanceM = lastChangedDistanceM + lastMovingSpeedMs * (whole stop)
    //
    // landed as ONE tick of odometer: 726 m here, charged at historical pace by a perfectly FRESH fix
    // (the rider is parked in the open, not in a tunnel), then never refunded — GhostIntegrator's
    // backward-delta branch keeps ghostTime by design. Measured 184 s read against a 40 s truth.
    //
    // The branch now re-anchors, so the coast can only ever cover the ONE tick the streams are out of
    // phase for — the ordinary dropout case, which snaps back on the next real distance sample.
    // =============================================================================================
    @Test fun `LOCK 1 - a 2 minute stop without autopause fabricates nothing`() {
        val r = Rig()
        val hist = 0.2 // s/m — known road, the rider's history says 5 m/s here
        var d = 0.0
        var t = 0.0
        // 100 s at 6 m/s on known ground: 600 m. Truth: 600*0.2 - 100 = +20 s ahead.
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, hist) }
        val truth = r.gap
        assertEquals("sanity: the honest lead before the stop", 20.0, truth, 0.5)

        // 120 s stopped at a light. DISTANCE frozen, SPEED 0.0, ELAPSED_TIME keeps running (no autopause).
        repeat(120) { t += 1.0; r.tick(d, t, 0.0, hist) }
        assertEquals("the moving-time clock correctly freezes the gap while stopped", truth, r.gap, 1e-9)

        // THE TRIGGER — one tick where SPEED has already crossed 0.5 m/s but the DISTANCE stream has not
        // yet emitted. combine(DISTANCE, ELAPSED_TIME, SPEED).sample(1000) (KGhostExtension.kt:1803-1804)
        // samples on a timer independent of the emitters, so a tuple carrying the NEW speed with the OLD
        // distance is a plain phase race, not an exotic fault.
        t += 1.0; r.tick(d, t, 0.8, hist)
        val phantom = r.coast.effectiveDistanceM - d
        println("LOCK 1: phantom odometer injected in ONE tick = ${"%.0f".format(phantom)} m (was 726 m)")

        // The rider now rides away normally for 100 s.
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, hist) }

        // TRUTH: the trigger tick is now a PENDING SAMPLE (within the 2 s tolerance), so it holds
        // rather than dead-reckoning — the race clock's guard (a) does not freeze on it, which counts
        // it as a moving second too: 201 s of MOVING time, not 200. 1200 m ridden on a 0.2 s/m road in
        // 201 s of moving time nets +38.8 s (measured; was 184 s).
        val correct = 38.8
        println("LOCK 1: rider reads ${"%.0f".format(r.gap)}s AHEAD, truth is ${"%.1f".format(correct)}s (was 184s)")
        // The trigger tick is now a pending sample: within tolerance, nothing is invented, so the
        // odometer holds exactly at `d` and the one out-of-phase tick injects no phantom metres at all.
        assertEquals("a pending sample injects no phantom metres", 0.0, phantom, 0.01)
        // ...and LOCK 2 means even a phantom metre would get no historical verdict, so the number is exact.
        assertEquals("the rider reads the truth", correct, r.gap, 0.5)
    }

    /** Same stop, but SPEED merely goes NULL for one tick (StreamState != Streaming — the code comment at
     *  KGhostExtension.kt:1832 says null "means we cannot prove a stop"). This trigger needs no speed
     *  jitter at all, so it is the cheapest way to reach the old fabrication. */
    @Test fun `LOCK 1b - one null SPEED sample during a stop mints nothing`() {
        val r = Rig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, 0.2) }
        val truth = r.gap
        repeat(60) { t += 1.0; r.tick(d, t, 0.0, 0.2) }     // 60 s stopped
        t += 1.0; r.tick(d, t, null, 0.2)                    // ONE tick with no SPEED sample
        println("LOCK 1b: gap ${"%.2f".format(truth)}s -> ${"%.2f".format(r.gap)}s on a null SPEED sample (was +72s)")
        assertEquals("a null SPEED sample during a stop cannot move the number", truth, r.gap, 1e-9)
    }

    /** Control: the SAME stop with a well-behaved SPEED stream (never null, never >=0.5 while the odometer
     *  lags) is clean — which is why 396 green tests never saw this. */
    @Test fun `control - the same stop with a clean SPEED stream fabricates nothing`() {
        val r = Rig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, 0.2) }
        val truth = r.gap
        repeat(120) { t += 1.0; r.tick(d, t, 0.0, 0.2) }
        d += 0.6; t += 1.0; r.tick(d, t, 0.6, 0.2) // distance and speed move on the SAME tick
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, 0.2) }
        // 38.92 = 1194.6 accrued metres * 0.2 - 200 s of race clock. (The first tick anchors the gap at 0,
        // so its own 6 m never accrue, and the race clock starts at that tick: both are by design.)
        println("control: gap=${"%.2f".format(r.gap)}s (truth 38.92s)")
        assertEquals(38.92, r.gap, 0.05)
        assertTrue("no fabrication when the streams stay in phase", r.gap - truth < 21.0)
    }

    // =============================================================================================
    // LOCK 2 (was FINDING #2) — GPS-loss coast OVERSHOOT must not be charged at historical pace.
    //
    // A genuine dropout WHILE MOVING freezes the DISTANCE stream, so CoastingEstimator dead-reckons at
    // the last moving speed. If the rider brakes during the loss, the coast OVERSHOOTS. The fix is
    // still <5 s old for the first few ticks, so the old `fixFresh` gate let tier 1 pay history for
    // those invented metres — and when the fix returns the odometer snaps BACK, which hits
    // GhostIntegrator's dd<0 branch and KEEPS ghostTime. One-signed: undershoot self-corrects (the
    // forward snap accrues the real metres), overshoot never does.
    //
    // The leak per fresh coasting tick is `hist * coastSpeed - 1`, so it is positive exactly when the
    // rider was going FASTER than their history when the fix died — measured +7.0 s for the single
    // dropout below. (Only the first dropout leaks in this construction: `lastMovingSpeedMs` decays to
    // the historical speed on the ride-on, which is what makes the second one free. Nothing about the
    // ORDER is guaranteed on a real ride — every dropout entered above historical pace leaks again, and
    // the odometer's snap BACK is what makes it permanent.)
    //
    // The tick now also requires `coast.quality == LIVE`, so every dead-reckoned metre gets the NEUTRAL
    // fill — which in GhostIntegrator is `de/dd`, the rider's OWN pace over the tick, so those metres
    // move the gap by EXACTLY 0. A dropout is therefore a clean no-op on the number: the engine returns
    // no verdict on metres it did not measure, which is the whole point (a verdict on invented metres is
    // precisely the bug). Not a loss, not a gain — zero, and zero however many dropouts there are.
    // =============================================================================================
    @Test fun `LOCK 2 - coast overshoot during a dropout can never gain lead`() {
        fun ride(dropouts: Int): Double {
            val r = Rig()
            val hist = 0.2 // history says 5 m/s on this road
            var d = 0.0; var t = 0.0
            // The rider is FASTER than their history (12 m/s on a 5 m/s road) when the fix dies — that is
            // what makes the overshoot pay: the coast dead-reckons at 12 m/s and history charges 0.2 s/m,
            // so every phantom metre buys 2.4 s of ghost time against 1 s of race clock.
            repeat(60) { d += 12.0; t += 1.0; r.tick(d, t, 12.0, hist) }
            val before = r.gap
            repeat(dropouts) {
                // GPS drops: the host re-emits the LAST distance for 20 s. The rider brakes to 2 m/s, but
                // the SPEED stream lags and keeps reporting 12 m/s — and THAT is what makes this an
                // overshoot at all, because the coast spends the REPORTED speed, not the rider's true rate
                // (CoastingEstimator's `speedMs.coerceAtMost(AGG_MAX_SPEED_MS) * room`). It dead-reckons
                // 12 * 20 = 240 m while the rider covers 40.
                //
                // Feeding the true 2 m/s here instead — as this test did until now, while its comments
                // described the 12 m/s case — made the coast accrue exactly the 40 m the raw stream came
                // back with. dd == 0 on recovery: no overshoot, GhostIntegrator's dd<0 branch never taken,
                // and the lock asserted "a dropout must not move the number" about a stimulus in which
                // nothing could have moved it. The assertions below are unchanged; only the stimulus is
                // real now.
                // The fix is still <5 s old for the first 5 ticks (fixFresh stays true there).
                val frozenAt = d
                repeat(5) { t += 1.0; r.tick(frozenAt, t, 12.0, hist, fixFresh = true) }
                repeat(15) { t += 1.0; r.tick(frozenAt, t, 12.0, hist, fixFresh = false) }
                // Fix returns: raw distance snaps back to what the rider ACTUALLY covered (2 m/s * 20 s),
                // 200 m behind the coast's guess → GhostIntegrator's dd<0 branch, which KEEPS ghostTime.
                d = frozenAt + 40.0
                t += 1.0; r.tick(d, t, 2.0, hist, fixFresh = true)
                // GUARD ON THE STIMULUS, not on the behaviour. Everything below only means something if
                // the coast REALLY overshot; this is the assertion whose absence let the test rot into a
                // no-op. coastedSurplusM is the invention sitting on top of the frozen raw at the moment
                // the freeze resolves: 12 m/s * 20 s = 240 m, against a real 40 m, so recovery hands the
                // integrator dd = 40 - 240 = -200 m and the dd<0 branch is genuinely exercised.
                assertEquals(
                    "the dropout must actually build an overshoot, or this lock proves nothing",
                    240.0, r.coast.coastedSurplusM, 1e-9,
                )
                repeat(30) { d += 5.0; t += 1.0; r.tick(d, t, 5.0, hist) } // ride on at exactly historical pace
            }
            return r.gap - before
        }
        val base = ride(0)
        val one = ride(1) - base
        val ten = ride(10) - base
        println("LOCK 2: per dropout=${"%.1f".format(one)}s  10 dropouts=${"%.1f".format(ten)}s " +
            "(was +7.0s and +7.0s — un-refunded lead, in the rider's favour)")
        assertEquals("no dropout at all moves nothing", 0.0, base, 1e-9)
        // The old code paid history for the coast overshoot and never refunded it. Now the blind metres
        // are neutral-filled, so a dropout contributes exactly nothing...
        assertEquals("a dropout must not move the number at all", 0.0, one, 1e-9)
        // ...and ten of them contribute exactly ten times nothing: no accumulation, in either sign.
        assertEquals("ten dropouts cannot ratchet", 0.0, ten, 1e-9)
    }

    // =============================================================================================
    // #3 TIER BOUNDARIES — COULD NOT REFUTE.
    // A tick's accrual is `pace * dd` with NO state carried between ticks, so crossing tier 1 -> 2 -> 3
    // -> 1 cannot step the gap: it is a plain metre-weighted mean. Two tiers disagreeing by 3x and
    // alternating EVERY tick gives exactly the mean of the two, not a ratchet toward either.
    // =============================================================================================
    @Test fun `tier alternation is an exact metre-weighted mean, not a ratchet`() {
        val t1 = 0.10; val t2 = 0.30
        val r = Rig(); var d = 0.0; var t = 0.0
        repeat(3_600) { i ->
            d += 8.0; t += 1.0
            r.tick(d, t, 8.0, if (i % 2 == 0) t1 else t2)
        }
        // 28 800 m, half at 0.10 s/m, half at 0.30 s/m, in 3600 s.
        // -8*t1: the anchor tick's own 8 m never accrue.  +1: the race clock starts on that tick.
        val expected = 14_400 * t1 + 14_400 * t2 - 3_600.0 - 8.0 * t1 + 1.0
        println("tier alternation: gap=${"%.3f".format(r.gap)}s expected=${"%.3f".format(expected)}s")
        assertEquals("alternating tiers = the mean, no hysteresis", expected, r.gap, 1e-6)
    }

    @Test fun `tier 3 metres move the gap by exactly zero however they are interleaved`() {
        val r = Rig(); var d = 0.0; var t = 0.0
        // 1 h: known ground every 3rd tick, novel ground otherwise, at a pace matching history exactly.
        repeat(3_600) { i -> d += 5.0; t += 1.0; r.tick(d, t, 5.0, if (i % 3 == 0) 0.2 else null) }
        // Only the 1200 known ticks (6000 m) get a verdict, and the rider rode them at exactly 0.2 s/m,
        // so the verdict is 0. The 12 000 novel metres must contribute nothing at all.
        println("interleaved tier3: gap=${"%.6f".format(r.gap)}s matched=${r.g.matchedM} filled=${r.g.filledM}")
        assertEquals(0.0, r.gap, 1e-9)
        assertEquals(5_995.0, r.g.matchedM, 1e-6) // 6000 minus the anchor tick's own 5 m
        assertEquals(12_000.0, r.g.filledM, 1e-6)
    }

    // =============================================================================================
    // #4 FRESHNESS-GATE EDGES — COULD NOT REFUTE (bounded, not a ratchet).
    // The tick a fix goes stale and the tick it recovers can each mis-attribute at most ONE tick's
    // metres, and the error is symmetric: 200 stale/fresh flips at a pace the rider is matching leave
    // the gap where it started.
    // =============================================================================================
    @Test fun `200 fix stale-fresh flips at historical pace leave the number untouched`() {
        val r = Rig(); var d = 0.0; var t = 0.0
        repeat(2_000) { i -> d += 5.0; t += 1.0; r.tick(d, t, 5.0, 0.2, fixFresh = (i % 10) < 5) }
        println("freshness flips: gap=${"%.9f".format(r.gap)}s")
        assertEquals("a flapping fix cannot move a rider who is exactly on their history", 0.0, r.gap, 1e-9)
    }

    // =============================================================================================
    // #5 SIX-HOUR RIDE — the compound. Rider rides EXACTLY at their historical pace all day on known
    // ground, so the honest number is 0.0 at every moment. Stops, dropouts, a reroute (irrelevant to
    // the number by construction) and a shortcut are added. Whatever comes out that is not 0 is drift.
    // =============================================================================================
    @Test fun `LOCK 5 - six hours at exactly historical pace stays on the truth`() {
        val r = Rig()
        val hist = 0.2 // the rider rides 5 m/s all day, which is exactly what history says
        var d = 0.0; var t = 0.0
        fun rideS(n: Int) = repeat(n) { d += 5.0; t += 1.0; r.tick(d, t, 5.0, hist) }
        rideS(600)
        var stops = 0
        var dropouts = 0
        repeat(12) { lap ->
            rideS(900) // 15 min riding
            // A red light, no autopause, with the one out-of-phase tick on pull-away: the raw DISTANCE
            // sample is genuinely still frozen (a GPS phase lag, same trigger as LOCK 1) while SPEED
            // already reports above the moving threshold. The rider's TRUE distance that second is
            // still 5 m (their historical pace all day, the 0.7 report is only the phase-lagged speed
            // sample) — `d` advances by the real 5 m so the NEXT genuine GPS sample carries it forward,
            // same as a real odometer would. Advancing `d` only on later ticks silently dropped that
            // second's ground truth from every following `rideS` step, manufacturing a full extra
            // uncredited second per stop (12 s over the ride) that has nothing to do with the estimator.
            repeat(90) { t += 1.0; r.tick(d, t, 0.0, hist) }
            val stoppedAtD = d
            d += 5.0; t += 1.0; r.tick(stoppedAtD, t, 0.7, hist); stops++
            rideS(300)
            if (lap % 3 == 0) { // a real dropout under trees: DISTANCE freezes, the rider keeps rolling
                val frozenAt = d
                repeat(5) { t += 1.0; r.tick(frozenAt, t, 5.0, hist, fixFresh = true) }
                repeat(25) { t += 1.0; r.tick(frozenAt, t, 5.0, hist, fixFresh = false) }
                d = frozenAt + 150.0 // the fix returns; the rider really did cover 30 s at 5 m/s
                t += 1.0; r.tick(d, t, 5.0, hist); dropouts++
            }
        }
        rideS(600)
        println("LOCK 5: 6 h at exactly historical pace, $stops stops, $dropouts dropouts -> " +
            "gap=${"%.0f".format(r.gap)}s (truth 0 s; was +1060s, all in the rider's favour)")
        // The pull-away tick is now a PENDING SAMPLE (within the 2 s tolerance): nothing is invented
        // and nothing is lost — the odometer holds for that one tick and the real distance reaches the
        // integrator on the very next sample, so a whole day of stops and dropouts reads exactly true.
        assertTrue("a whole day of stops and dropouts drifts less than 2 s", kotlin.math.abs(r.gap) < 2.0)
        assertTrue("and never in the rider's favour", r.gap <= 0.0)
    }

    // =============================================================================================
    // #6 CHECKPOINT RESUME — the paramMatch gate (pick only, since vpTimePerM was retired) is fine for
    // the NUMBER: vpTimePerM genuinely no longer touches the accrual. What the gate cannot separate is
    // an ABORTED ride from a fresh one that starts at the same place: `continuous` accepts any odometer
    // within CHECKPOINT_RESUME_MARGIN_M (300 m) of the checkpoint, and a ride aborted in its first
    // 300 m leaves exactly such a checkpoint. Restart within 6 h on the same route and the fresh ride
    // silently inherits the aborted ride's lead. That is bounded by whatever can accrue in <300 m —
    // which is the point: FINDING #1 used to be able to put MINUTES into those 300 m, and now cannot,
    // so the residual exposure is back to the small bounded thing the 300 m margin was designed for.
    // =============================================================================================
    @Test fun `LOCK 6 - an aborted ride can no longer stuff minutes into the 300 m resume window`() {
        // The production gate, verbatim (KGhostExtension.kt:2136-2139).
        fun continuous(cpLastRiderDist: Double, riderDistNow: Double, sameEpoch: Boolean) =
            sameEpoch || kotlin.math.abs(riderDistNow - cpLastRiderDist) <= 300.0
        assertTrue("a fresh ride 40 m in adopts an aborted ride's 180 m checkpoint",
            continuous(cpLastRiderDist = 180.0, riderDistNow = 40.0, sameEpoch = false))
        assertTrue("only distance separates them — the fresh ride's epoch differs, which the OR ignores",
            !continuous(cpLastRiderDist = 40_000.0, riderDistNow = 40.0, sameEpoch = false))
        // The lead that could get adopted used to be large, because FINDING #1 minted it inside 300 m:
        val r = Rig(); var d = 0.0; var t = 0.0
        repeat(40) { d += 5.0; t += 1.0; r.tick(d, t, 5.0, 0.2) } // 200 m in, at exactly historical pace
        repeat(120) { t += 1.0; r.tick(d, t, 0.0, 0.2) }          // long stop
        t += 1.0; r.tick(d, t, 0.7, 0.2)                          // the out-of-phase pull-away tick
        println("LOCK 6: an abandoned ride checkpoints lead=${"%.2f".format(r.gap)}s at " +
            "${"%.0f".format(d)} m (was >60s)")
        assertEquals("no minutes can be stuffed into the 300 m window any more", 0.0, r.gap, 1e-9)
    }

    // =============================================================================================
    // PENDING SAMPLE INTERVAL — the 1 Hz tick oversamples a slower-changing DISTANCE value, so two
    // consecutive ticks routinely read the same raw distance with the GPS perfectly healthy. These
    // lock the three refuted designs OUT: R1 (hold and let the race clock freeze → mints lead),
    // R3 (hold but keep ticking the integrator → loses 2 s per long loss), R4 (hang both guards on
    // pendingSample → a stop inside the hold consumes the interval).
    // =============================================================================================
    @Test fun `PENDING 1 - a one second beat cannot move the number`() {
        val hist = 0.2
        fun ride(beat: Boolean): Double {
            val r = Rig(pendingToleranceS = 2.0)
            var d = 0.0; var t = 0.0
            r.tick(d, t, 5.0, hist)
            repeat(20) {
                if (beat) {
                    t += 1.0; r.tick(d, t, 5.0, hist)          // the stream skips a sample
                    d += 10.0; t += 1.0; r.tick(d, t, 5.0, hist) // and delivers both metres next tick
                } else {
                    d += 5.0; t += 1.0; r.tick(d, t, 5.0, hist)
                    d += 5.0; t += 1.0; r.tick(d, t, 5.0, hist)
                }
            }
            return r.gap
        }
        assertEquals("a sampling beat must not change the number", ride(false), ride(true), 1e-6)
    }

    @Test fun `PENDING 2 - 470 beats at historical pace mint nothing`() {
        val r = Rig(pendingToleranceS = 2.0)
        val hist = 0.2
        var d = 0.0; var t = 0.0
        r.tick(d, t, 5.0, hist)
        repeat(470) {
            t += 1.0; r.tick(d, t, 5.0, hist)            // pending
            d += 10.0; t += 1.0; r.tick(d, t, 5.0, hist) // settle
        }
        println("PENDING 2: 470 beats -> gap=${"%.2f".format(r.gap)}s (R1 would read +470)")
        assertEquals("470 beats cannot ratchet the lead", 0.0, r.gap, 1e-6)
    }

    @Test fun `PENDING 3 - a pending tick publishes nothing`() {
        val r = Rig(pendingToleranceS = 2.0)
        val hist = 0.2
        r.tick(0.0, 0.0, 5.0, hist)
        val before = r.publishCount
        r.tick(0.0, 1.0, 5.0, hist)                      // pending
        assertEquals("a pending tick must not publish", before, r.publishCount)
        r.tick(10.0, 2.0, 5.0, hist)                     // settle
        assertEquals("the settle tick publishes once", before + 1, r.publishCount)
    }

    @Test fun `PENDING 4 - pending then stop then settle loses no time`() {
        // NEUTRAL pace (null) on purpose: with a historical pace the settle tick charges
        // `ghostTime += hist * dd`, which is identical whoever consumed `prevElapsedS`, so the test
        // could not tell the correct guard from R4. Only the neutral path reads `de`, which is
        // exactly the quantity a stopped tick would eat.
        // Both rides contain the SAME two moving seconds, the SAME one stopped second and the SAME
        // ten metres. They differ only in whether the stream beats.
        fun ride(withBeat: Boolean): Double {
            val r = Rig(pendingToleranceS = 2.0)
            var d = 0.0; var t = 0.0
            r.tick(d, t, 5.0, null)                          // anchor
            if (withBeat) {
                t += 1.0; r.tick(d, t, 5.0, null)            // moving, stream frozen -> pending
                t += 1.0; r.tick(d, t, 0.0, null)            // STOPPED inside the hold
                d += 10.0; t += 1.0; r.tick(d, t, 5.0, null) // moving, stream delivers both metres
            } else {
                d += 5.0; t += 1.0; r.tick(d, t, 5.0, null)  // moving
                t += 1.0; r.tick(d, t, 0.0, null)            // STOPPED
                d += 5.0; t += 1.0; r.tick(d, t, 5.0, null)  // moving
            }
            return r.gap
        }
        // Correct: both 0. Under R4 the stopped tick calls onTick with dd==0, consumes the held
        // second, and the beating ride reads -1.
        assertEquals("a stop inside a hold must not eat the interval", 0.0, ride(withBeat = false), 1e-6)
        assertEquals("a stop inside a hold must not eat the interval", 0.0, ride(withBeat = true), 1e-6)
    }

    @Test fun `PENDING 4b - a stop inside a hold publishes nothing`() {
        val r = Rig(pendingToleranceS = 2.0)
        r.tick(0.0, 0.0, 5.0, null)
        val before = r.publishCount
        r.tick(0.0, 1.0, 5.0, null)                          // pending
        r.tick(0.0, 2.0, 0.0, null)                          // stopped, hold still unresolved
        assertEquals("a stopped tick inside a hold must not publish", before, r.publishCount)
        r.tick(10.0, 3.0, 5.0, null)                         // settle
        assertEquals("the settle tick publishes once", before + 1, r.publishCount)
    }

    @Test fun `PENDING 5 - escalation past the tolerance matches today exactly`() {
        val hist = 0.2
        fun ride(tolerance: Double): Double {
            val r = Rig(pendingToleranceS = tolerance)
            var d = 0.0; var t = 0.0
            r.tick(d, t, 10.0, hist)
            repeat(30) { t += 1.0; r.tick(d, t, 10.0, hist) }   // 30 s blind
            d += 300.0; t += 1.0; r.tick(d, t, 10.0, hist)      // fix returns
            return r.gap
        }
        assertEquals("a real dropout must be unaffected by the hold", ride(0.0), ride(2.0), 1e-6)
    }

    @Test fun `PENDING 5b - escalation leaves the odometer and the budget where today leaves them`() {
        fun odo(tolerance: Double): Pair<Double, Double> {
            val c = CoastingEstimator(pendingToleranceS = tolerance)
            c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
            repeat(10) { c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = it + 1.0) }
            return c.effectiveDistanceM to c.coastingSeconds
        }
        assertEquals("odometer and loss clock must match today past the tolerance", odo(0.0), odo(2.0))
    }

    @Test fun `PENDING 6 - a first tick during a hold keeps a restored lead`() {
        val r = Rig(pendingToleranceS = 2.0)
        val hist = 0.2
        // The estimator is SHARED and ticks before route mode exists, so its first-call guard is
        // already spent by the time the first ROUTE tick runs. Warm it here or the next update()
        // takes the `changed` path and no hold is possible — the test would prove nothing.
        r.coast.update(rawDistanceM = 100.0, speedMs = 5.0, elapsedS = 0.0)
        r.g.restore(leadS = 42.0, lastRiderDist = 100.0)
        // The first ROUTE tick lands on a repeated raw distance: pendingHold is true.
        r.tick(100.0, 1.0, 5.0, hist)
        // Guard (b) must let the INITIALISING call through: restore() only stores pendingResumeLead,
        // and onTick is where it becomes gapTimeS. Skipping it publishes a default zero.
        assertEquals("the initialising onTick is never skipped", 42.0, r.gap, 1e-6)
        // Guard (c) has NO initialisation carve-out, and that is deliberate: the hold is still
        // unresolved, so the published number stays frozen even though the integrator is now
        // correctly anchored. What the external review actually required is the assertion above —
        // that the integrator gets its initialising call, so the checkpoint cannot persist a zero
        // over a restored lead. Publication is a separate contract, and adding an exception here
        // would let the number move mid-hold, which is the jitter guard (c) exists to prevent.
        assertEquals("a pending tick publishes nothing, not even the initialising one", 0, r.publishCount)
        r.tick(110.0, 2.0, 5.0, hist)   // settle
        assertEquals("the restored lead reaches the holder on the settle tick", 1, r.publishCount)
    }

    @Test fun `PENDING 8 - both null variants are exact on both sides of the tolerance`() {
        // Null WITH a remembered rate, not crossing the tolerance.
        run {
            val c = CoastingEstimator(pendingToleranceS = 2.0)
            c.update(rawDistanceM = 100.0, speedMs = 10.0, elapsedS = 0.0)
            c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 1.0)   // remembers 10 m/s
            c.update(rawDistanceM = 110.0, speedMs = 10.0, elapsedS = 1.5)   // hold, defer 5
            c.update(rawDistanceM = 110.0, speedMs = null, elapsedS = 2.0)   // null, still under 2 s
            assertEquals(120.0, c.effectiveDistanceM, 1e-9)                  // 110 + 5 deferred + 5
            assertFalse(c.pendingHold)
        }
        // Null WITHOUT a usable rate, not crossing the tolerance.
        run {
            val c = CoastingEstimator(pendingToleranceS = 2.0)
            c.update(rawDistanceM = 0.0, speedMs = 40.0, elapsedS = 0.0)     // implausible, not remembered
            c.update(rawDistanceM = 10.0, speedMs = 40.0, elapsedS = 1.0)    // everMoved, rate still 0
            c.update(rawDistanceM = 10.0, speedMs = 6.0, elapsedS = 1.5)     // hold, defer 3
            c.update(rawDistanceM = 10.0, speedMs = null, elapsedS = 1.9)    // no rate, under 2 s
            assertEquals(13.0, c.effectiveDistanceM, 1e-9)                   // the 3 m must not strand
            assertFalse(c.pendingHold)
        }
    }

    @Test fun `PENDING 7 - a budget below the tolerance never holds`() {
        val hist = 0.2
        fun ride(tolerance: Double): Double {
            val r = Rig(pendingToleranceS = tolerance, maxCoastS = 1.0)
            var d = 0.0; var t = 0.0
            r.tick(d, t, 10.0, hist)
            repeat(3) { t += 1.0; r.tick(d, t, 10.0, hist) }
            d += 30.0; t += 1.0; r.tick(d, t, 10.0, hist)
            return r.gap
        }
        assertEquals("a budget under the tolerance must behave as today", ride(0.0), ride(2.0), 1e-6)
    }

    @Test fun `PENDING 9 - a hold that settles on a stopped tick charges no stopped second`() {
        // Guard (a)'s riderDist/pendingSample test runs BEFORE CoastingEstimator's stop branch is
        // reached on the SETTLE tick, so a settle that lands on a stopped rider used to look like
        // ordinary movement (the odometer had just advanced) and the stopped second was charged.
        // Both rides carry the same 5 m held-then-settled step and the same stop; they differ only
        // in whether the stream beats first.
        val hist = 0.2
        fun ride(beat: Boolean): Double {
            val r = Rig(pendingToleranceS = 2.0)
            r.tick(100.0, 0.0, 5.0, hist)                    // anchor
            if (beat) {
                r.tick(100.0, 1.0, 5.0, hist)                // stream owes us a sample -> pending
                r.tick(105.0, 2.0, 0.0, hist)                // settle lands on a STOPPED tick
            } else {
                r.tick(105.0, 1.0, 5.0, hist)                // timely: the 5 m arrive on time
                r.tick(105.0, 2.0, 0.0, hist)                // unchanged, rider now stopped
            }
            return r.gap
        }
        val beatGap = ride(beat = true)
        println("PENDING 9: settle-on-stop beat gap=${"%.2f".format(beatGap)}s (broken build reads -1.00)")
        assertEquals("a beating stream must not change the answer", ride(beat = false), beatGap, 1e-6)
        assertEquals("the stopped settle tick charges no stopped second", 0.0, beatGap, 1e-6)
    }

    @Test fun `PENDING 10 - a coast recovery landing on a stopped tick cannot mint lead`() {
        // A genuine dropout escalates past the pending tolerance with an UNDER-REPORTED speed
        // (2.0 m/s), so the coast dead-reckons LESS than the rider actually covered (3.0 s at
        // 2.0 m/s -> 6 m coasted by the time the tolerance is spent). When the real fix returns, raw
        // jumps to 9 m — a 3 m positive residual — on a tick where the rider happens to be STOPPED.
        // That is a coast-RECOVERY correction, not a settling pending hold: the hold was already
        // flushed by the escalation two ticks earlier, so `coast.settledPending` reads false here.
        // The broad `stoppedNow` guard (bd04ba2) cannot tell the two apart and freezes anyway.
        // GhostIntegrator credits `hist * dd` for the residual regardless of the guard (it ignores
        // the elapsed delta whenever a historical pace is available) — freezing the race clock
        // removes the only thing that offsets that credit, minting lead out of a stationary tick.
        val hist = 0.2
        val r = Rig(pendingToleranceS = 2.0)
        r.tick(0.0, 0.0, 5.0, hist)                  // anchor
        r.tick(0.0, 1.0, 2.0, hist)                  // frozen, under-reported speed -> pending (1/2 s)
        r.tick(0.0, 2.0, 2.0, hist)                  // still held (2/2 s, tolerance boundary)
        r.tick(0.0, 3.0, 2.0, hist)                  // escalates: flush + coast -> effective 6 m
        r.tick(9.0, 4.0, 0.0, hist)                  // fix returns (residual +3 m) on a STOPPED tick
        println("PENDING 10: coast-recovery-on-stop gap=${"%.2f".format(r.gap)}s (broken build reads +0.60)")
        assertEquals("the residual arithmetic is pinned", -0.4, r.gap, 1e-6)
        assertTrue("a residual arriving while stationary must not mint lead", r.gap <= 0.0)
    }
}
