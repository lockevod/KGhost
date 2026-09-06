package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.TrackRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REGRESSION LOCKS for [CoastingEstimator]'s two clocks, driven through both consumers.
 *
 * History: c672e99 ("a stop without auto-pause no longer mints a phantom kilometre of lead") made the
 * legitimate-stop branch RE-ANCHOR distance and time together. That killed the phantom kilometre and
 * introduced four worse faults, every one of them measured by this file when it was written as an
 * adversarial refutation. The estimator now separates the two things a stop must do:
 *   * the ODOMETER integrates the reported speed tick by tick and a stop merely HOLDS it — a stop can
 *     neither age into phantom metres nor discard real dead-reckoned ones;
 *   * the LOSS CLOCK (`coastingSeconds`) advances only while blind-and-not-provably-stopped and is
 *     cleared only by a real change in the RAW distance, so a stop freezes it without erasing it.
 *
 * Everything below drives the PRODUCTION classes. Two rigs replicate the two consumers verbatim:
 *   [RouteRig] — the B2 route tick (KGhostExtension.kt:2082-2184), identical to AdvNumPipelineTest.Rig.
 *   [VpRig]    — the NO-ROUTE Ghost-Pace tick (:2405-2423): handleGpsLoss + vpGap, which read
 *                `coast.effectiveDistanceM` / `coastingSeconds` / `quality` with NO integrator
 *                between them and the number, and NO layer-2 gate to protect them.
 *
 * Each lock's comment carries the number the broken build produced, so a revert reads as a diff.
 */
class Adv2CoastPipelineTest {

    // =============================================================================================
    // RIGS
    // =============================================================================================

    /** The B2 route tick, verbatim (same shape as AdvNumPipelineTest.Rig). */
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
            val paceNow = if (fixFresh && (coast.quality == CoastQuality.LIVE || coast.isPhaseSlip)) pace else null
            g.onTick(riderDist, 0.0, riderDist * 1e-5, 90.0, elapsedS - ms) { _, _, _ -> paceNow }
            integLast = riderDist
        }

        val gap get() = g.gapTimeS
    }

    /**
     * The NO-ROUTE Ghost-Pace tick, verbatim (KGhostExtension.kt:1878-1905, 1985-1993, 2405-2423).
     * GPS_ALERT_S = 60, GPS_GIVEUP_S = 180, re-arm hysteresis at ALERT*0.5 = 30.
     */
    private class VpRig(targetMs: Double = 12.0 / 3.6) {
        val coast = CoastingEstimator()
        private val curve = GhostPaceSource(targetMs).curve()
        private var moveStart: Double? = null
        private var gpsAlertFired = false

        /** Every "GPS lost" InRideAlert this ride would have dispatched. */
        var alertsFired = 0; private set
        /** Ticks where the field went `---` (give-up blank). */
        var blankedTicks = 0; private set
        /** Ticks where the number was rendered with the "estimate" mark. */
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

        /** Seconds AHEAD the rider reads (GapCalculator's sign is inverted: gapTimeS<0 == ahead). */
        val aheadS get() = -(gap?.gapTimeS ?: Double.NaN)
        val odoM get() = coast.effectiveDistanceM
    }

    // =============================================================================================
    // LOCK A — a stop the SPEED stream never reports. A paired wheel/ANT+ sensor stops broadcasting
    // exactly when the wheel stops, so the tick reads `speedMs == null` (KGhostExtension.kt:1834
    // `takeIf { it.isFinite() }` — the code's own comment calls a null "cannot prove a stop"). The old
    // stop branch was gated on `speedMs != null`, so this case never reached it and dead-reckoned the
    // WHOLE stop: 720 phantom metres, the field reading 177 s AHEAD of a 39 s deficit.
    //
    // A silent SPEED stream is indistinguishable from a dropout BY SPEED ALONE, so the estimator does
    // not try: it BOUNDS the invention at one coast window of the last moving speed and lets the loss
    // clock run true. The alert therefore still fires — that is honest, not spurious: with no speed and
    // no distance the device genuinely cannot tell a parked bike from a blind one.
    // =============================================================================================
    @Test fun `A - a null SPEED stop is bounded to one coast window, not the whole stop`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }   // 600 m at 6 m/s
        val beforeOdo = r.odoM
        // 120 s at a light. DISTANCE frozen, ELAPSED running (no auto-pause), SPEED stream gone quiet.
        repeat(120) { t += 1.0; r.tick(d, t, null) }
        val phantom = r.odoM - beforeOdo
        val readAhead = r.aheadS
        // Truth: 600 m ridden, 219 s of race clock, 12 km/h ghost => 600/3.333 = 180 s of ghost time.
        val truthAhead = 180.0 - 219.0
        println(
            "A: null-SPEED stop -> phantom=${"%.0f".format(phantom)} m (was 720), reads " +
                "${"%.0f".format(readAhead)}s AHEAD (was 177), truth ${"%.0f".format(truthAhead)}s; " +
                "alerts=${r.alertsFired} estimatedTicks=${r.estimatedTicks} blanked=${r.blankedTicks}",
        )
        assertEquals("bounded at COAST_WINDOW_MS x 6 m/s, whatever the stop's length", 180.0, phantom, 0.01)
        assertEquals("the rider reads ~15 s ahead (was 177 s)", 15.0, readAhead, 1.0)
        assertEquals("truth is ~39 s BEHIND", -39.0, truthAhead, 1.0)
        assertTrue("the error is a quarter of what it was (54 s, was 216 s)", readAhead - truthAhead < 60.0)
        // The alert is the honest half of the trade — and the number is MARKED for the whole stop,
        // so the rider is never handed an unmarked estimate.
        assertEquals("we cannot prove a stop, so we say so exactly once", 1, r.alertsFired)
        assertTrue("and every tick of it is rendered as an ESTIMATE", r.estimatedTicks >= 89)
    }

    /** The same hole with no null needed: GPS speed noise pinned at 0.5 m/s while parked is "moving"
     *  by the class's own threshold. It can no longer be charged the REMEMBERED 6 m/s — dead reckoning
     *  integrates the speed actually reported, so a 0.5 m/s noise floor invents 0.5 m/s of distance. */
    @Test fun `A2 - parked at the moving threshold coasts at the reported speed, not the remembered one`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val before = r.odoM
        repeat(120) { t += 1.0; r.tick(d, t, 0.5) } // exactly at MIN_MOVING_MS => NOT < => not a stop
        println("A2: parked at a 0.5 m/s noise floor -> phantom=${"%.0f".format(r.odoM - before)} m (was 720), alerts=${r.alertsFired}")
        assertEquals("120 s x the reported 0.5 m/s, not x the remembered 6 m/s", 60.0, r.odoM - before, 0.01)
        assertEquals(1, r.alertsFired)
    }

    // =============================================================================================
    // LOCK B — urban canyon: a GENUINE, sustained GPS loss punctuated by ordinary traffic stops. When
    // every stop reset the loss clock, `coastingSeconds` restarted at each light and NEVER reached
    // GPS_ALERT_S (60 s) or GPS_GIVEUP_S (180 s): 0 alerts, 0 marked ticks, 0 blanks over a 450 s loss,
    // with the odometer 1500 real metres stale and the field presenting it as a TRUSTED reading.
    // The loss clock now survives stops, so the loss announces itself, marks the number, and finally
    // gives up — while the odometer, which now keeps the metres coasted before each light, lands on the
    // truth to the metre.
    // =============================================================================================
    @Test fun `B - a real GPS loss broken by traffic lights is announced, marked and given up on`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }  // 1200 m with a good fix
        val frozenAt = d                                        // GPS dies here, for the rest of the ride
        repeat(10) {                                            // 10 blocks: 25 s rolling, 20 s at a light
            repeat(25) { t += 1.0; r.tick(frozenAt, t, 6.0) }
            repeat(20) { t += 1.0; r.tick(frozenAt, t, 0.0) }
        }
        println(
            "B: alerts=${r.alertsFired} (was 0) estimatedTicks=${r.estimatedTicks} (was 0) " +
                "blanked=${r.blankedTicks} (was 0) odo=${"%.0f".format(r.odoM)} m (was 1200, truth 2700)",
        )
        // Truth: 1200 m + 10*25 s*6 m/s = 2700 m, and 250 s of blind-while-moving out of 450 s frozen.
        assertEquals("the loss announces itself, once", 1, r.alertsFired)
        assertTrue("and the number is marked as an estimate throughout", r.estimatedTicks > 250)
        assertTrue("and past GPS_GIVEUP_S the field gives up and blanks", r.blankedTicks > 100)
        assertEquals("the odometer tracks the truth to the metre", 2700.0, r.odoM, 0.01)
        // The loss clock counts BLIND-AND-MOVING seconds only: 10 x 25 s, so the give-up lands where a
        // rider has really been dead-reckoned for 3 minutes, not merely stationary for 3 minutes.
        assertEquals("stops freeze the loss clock without erasing it", 250.0, r.coastS, 1e-9)
    }

    // =============================================================================================
    // LOCK C — a tree-tunnel dropout while moving, a junction stop INSIDE the loss, then blind riding
    // again. Re-anchoring the DISTANCE on a stop threw away every metre dead-reckoned before it, for
    // good: the odometer error was -800 m (3.3x the old +240 m, with the sign flipped so that it no
    // longer announced itself). Holding the odometer through the stop lands it exactly on the truth.
    // =============================================================================================
    @Test fun `C - a stop inside a dropout keeps the coasted metres`() {
        val trueDist = 480.0 + 800.0 + 480.0
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }   // 480 m at 8 m/s, good fix
        val frozenAt = d
        repeat(100) { t += 1.0; r.tick(frozenAt, t, 8.0) }      // 100 s blind at 8 m/s = 800 m
        repeat(30) { t += 1.0; r.tick(frozenAt, t, 0.0) }       // 30 s stopped at a junction, still blind
        repeat(60) { t += 1.0; r.tick(frozenAt, t, 8.0) }       // 60 s blind again = 480 m
        val err = r.odoM - trueDist
        println("C: odometer error=${"%.0f".format(err)} m (was -800; pre-c672e99 +240), blanked=${r.blankedTicks}")
        assertEquals("every blind metre is kept, and none is invented", 0.0, err, 0.01)
        // 160 s of blind-while-moving, so the give-up (180 s) correctly has NOT fired: the 30 s the
        // rider provably stood still are not counted against them.
        assertEquals(160.0, r.coastS, 1e-9)
        assertEquals(0, r.blankedTicks)
        assertTrue("but the number is marked as an estimate", r.gap!!.estimated)
    }

    /** The trust signal. One stopped tick inside a loss used to clear `estimated` in the same tick it
     *  dropped the odometer by 360 m: a wrong number was promoted from *marked estimate* to *trusted*. */
    @Test fun `C2 - a stopped tick inside a loss changes neither the number nor the estimate mark`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val frozenAt = d
        repeat(45) { t += 1.0; r.tick(frozenAt, t, 8.0) }     // 45 s blind: past the 30 s window
        val duringLoss = r.gap!!
        t += 1.0; r.tick(frozenAt, t, 0.0)                    // ONE stopped tick inside the loss
        val atStop = r.gap!!
        println(
            "C2: during loss estimated=${duringLoss.estimated} odo=${"%.0f".format(duringLoss.progressM)} m -> " +
                "one stopped tick: estimated=${atStop.estimated} odo=${"%.0f".format(atStop.progressM)} m " +
                "(${"%.0f".format(atStop.progressM - duringLoss.progressM)} m in one tick, was -360)",
        )
        assertTrue("mid-loss the number is correctly marked as an estimate", duringLoss.estimated)
        assertTrue("and one stopped tick cannot un-mark it", atStop.estimated)
        assertEquals("nor move the odometer", 0.0, atStop.progressM - duringLoss.progressM, 1e-9)
        assertEquals(CoastQuality.LONG_LOSS, r.coast.quality)
    }

    // =============================================================================================
    // LOCK D — the discarded metres came BACK as one giant LIVE forward step on the recovery tick,
    // where the layer-2 coast gate cannot withhold anything, so the whole blind batch was charged the
    // historical pace sampled at ONE point: blind on a descent, junction stop, re-acquire on a climb
    // minted +299 s of lead in a single second. With the metres kept, the recovery tick has nothing
    // left to refund.
    // =============================================================================================
    @Test fun `D - a stop inside a dropout refunds nothing to the recovery tick`() {
        // The rider is blind on a fast descent (history there: 0.1 s/m) and re-acquires on a climb
        // (history there: 0.5 s/m). `pace` is the tier answer AT THIS TICK'S position.
        fun ride(withStop: Boolean): Double {
            val r = RouteRig()
            var d = 0.0; var t = 0.0
            repeat(60) { d += 10.0; t += 1.0; r.tick(d, t, 10.0, 0.1) }   // 600 m descending, on history
            val frozenAt = d
            repeat(60) { t += 1.0; r.tick(frozenAt, t, 10.0, 0.1, fixFresh = false) } // 60 s blind = 600 m
            if (withStop) repeat(10) { t += 1.0; r.tick(frozenAt, t, 0.0, 0.1) }      // stop inside the loss
            d = frozenAt + 600.0                                                       // fix returns, on a climb
            t += 1.0; r.tick(d, t, 10.0, 0.5)                                          // ONE recovery tick
            return r.gap
        }
        val noStop = ride(withStop = false)
        val withStop = ride(withStop = true)
        println("D: recovery WITHOUT a stop inside the loss -> ${"%.0f".format(noStop)}s; WITH one -> ${"%.0f".format(withStop)}s (was +299s)")
        assertEquals("no stop: the recovery tick accrues nothing (rider was exactly on history)", 0.0, noStop, 2.0)
        assertEquals("a stop inside the loss must not change that", noStop, withStop, 1e-9)
    }

    // =============================================================================================
    // LOCK E — a DISTANCE stream merely OUT OF PHASE with the 1 Hz `sample()` is still judged.
    // This lock used to assert the opposite, and the comment invited exactly this change. A field
    // ride made the cost concrete: 578 one-tick "gps-loss episodes", 627 non-LIVE ticks of 8787
    // (7.1%), 1417 m of 24911 (5.7%) left unjudged — on a device whose GNSS was measured at a steady
    // 2.00 Hz, i.e. with no fixes missing at all. The gate now also accepts `coast.isPhaseSlip`.
    // NOTE the shape of the fix: the odometer, the loss clock, the alert and the quality are all
    // untouched. Holding the odometer instead was tried first and broke 40 locks, because every real
    // dropout then lost its first second of dead reckoning. Only the VERDICT gate moved.
    // =============================================================================================
    @Test fun `E - a DISTANCE stream slower than the tick is still judged`() {
        val r = RouteRig()
        val hist = 0.5 // history says 2 m/s here; the rider is doing 6 m/s — a huge, real lead
        var d = 0.0; var t = 0.0
        // DISTANCE emits every OTHER tick (12 m at a time) while SPEED reports the true 6 m/s.
        repeat(1_800) { i ->
            t += 1.0
            if (i % 2 == 1) d += 12.0
            r.tick(d, t, 6.0, hist)
        }
        val truth = 1_800 * 6.0 * hist - 1_800.0 // 10 800 m at 0.5 s/m against 1800 s = +3600 s
        println(
            "E: 30 min at 3x historical pace, DISTANCE at 0.5 Hz -> reads ${"%.0f".format(r.gap)}s, " +
                "truth ${"%.0f".format(truth)}s; matched=${"%.0f".format(r.g.matchedM)} m " +
                "filled=${"%.0f".format(r.g.filledM)} m",
        )
        val coverage = r.g.matchedM / (r.g.matchedM + r.g.filledM)
        assertEquals("every real metre now gets a verdict", 1.0, coverage, 0.01)
        assertEquals("so the lead reads the truth, not half of it", truth, r.gap, truth * 0.01)
    }

    /** Control for E: the SAME ride with DISTANCE in phase reads the truth exactly, so E is purely the
     *  cost of the coast classification, not of the pace model. */
    @Test fun `E2 - in phase, the same ride reads the truth`() {
        val r = RouteRig()
        var d = 0.0; var t = 0.0
        repeat(1_800) { d += 6.0; t += 1.0; r.tick(d, t, 6.0, 0.5) }
        val truth = 1_800 * 6.0 * 0.5 - 1_800.0 - 6.0 * 0.5 + 1.0 // minus the anchor tick, plus its clock
        println("E2: in-phase control -> ${"%.1f".format(r.gap)}s vs ${"%.1f".format(truth)}s")
        assertEquals(truth, r.gap, 0.01)
    }

    // =============================================================================================
    // THE CASES THAT MUST NOT MOVE — misclassification the estimator is expected to absorb.
    // =============================================================================================

    /** A 20% wall at 1.6 km/h (0.44 m/s), DISTANCE quantised to 1 m so it only steps every ~2 s.
     *  Below MIN_MOVING_MS the frozen ticks take the STOP branch, so nothing is invented and the
     *  odometer tracks the raw exactly — the classification is "wrong" but the output is right. */
    @Test fun `crawl below the moving threshold invents nothing`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val base = d
        var acc = 0.0
        repeat(300) { t += 1.0; acc += 0.44; r.tick(base + kotlin.math.floor(acc), t, 0.44) }
        println("crawl: odo=${"%.1f".format(r.odoM)} m raw=${"%.1f".format(base + kotlin.math.floor(acc))} m alerts=${r.alertsFired}")
        assertEquals("odometer == raw, no dead reckoning at all", base + kotlin.math.floor(acc), r.odoM, 1e-9)
        assertEquals("and no spurious GPS-lost alert on a wall", 0, r.alertsFired)
    }

    /** A track-stand: bolt upright, speed 0.00, DISTANCE frozen, ELAPSED running, for 3 minutes. The
     *  loss clock must NOT run — a provably still wheel is not a GPS failure, however long it lasts. */
    @Test fun `a three minute track stand is a clean no-op`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val before = r.aheadS
        repeat(180) { t += 1.0; r.tick(d, t, 0.0) }
        println("track-stand: ahead ${"%.0f".format(before)}s -> ${"%.0f".format(r.aheadS)}s, alerts=${r.alertsFired}")
        assertEquals("the odometer does not move", 600.0, r.odoM, 1e-9)
        assertEquals("no alert, no blank, no estimate mark", 0, r.alertsFired)
        assertEquals(0, r.blankedTicks)
        assertEquals(0.0, r.coastS, 1e-9)
        assertEquals(CoastQuality.LIVE, r.coast.quality)
        // The VP race clock keeps running (no moving-time freeze in ① mode), so the lead decays 1:1 —
        // that is by design for Ghost-Pace and is unaffected by this fix.
        assertEquals("lead decays exactly 1 s per stopped second", before - 180.0, r.aheadS, 1e-9)
    }

    /** Pushing the bike on foot: 1.2 m/s, above the threshold, GPS fine. */
    @Test fun `pushing the bike on foot is treated as riding, correctly`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        repeat(300) { d += 1.2; t += 1.0; r.tick(d, t, 1.2) }
        assertEquals("odometer == raw throughout", d, r.odoM, 1e-9)
        assertEquals(0, r.alertsFired)
    }

    /** A wheel sensor reporting exact zeros while GPS is fine and the odometer advances. The `changed`
     *  branch runs FIRST, so the bogus zero is irrelevant to the distance. */
    @Test fun `a wheel sensor stuck at zero cannot freeze a live odometer`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(50) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        repeat(200) { d += 6.0; t += 1.0; r.tick(d, t, 0.0) } // GPS advancing, sensor lying
        assertEquals(d, r.odoM, 1e-9)
        assertEquals(0, r.alertsFired)
    }

    /** GPS speed jittering across 0.5 m/s while GENUINELY parked, DISTANCE frozen — the worst case for
     *  integrating the reported speed, and the one place this change costs something. Every
     *  above-threshold sample is dead-reckoned, so 300 s of noise invents 0.7 m/s x 150 s = 105 m and
     *  the loss clock reaches the alert. That is the deliberate trade: discarding on every stopped tick
     *  bounded THIS to 6 m but silently swallowed the real dropouts in locks B and C. It stays far below
     *  the 1800 m the pre-c672e99 estimator invented here, and it is bounded by the REPORTED speed —
     *  the noise floor, not the rider's cruising speed. */
    @Test fun `speed jitter across the threshold while parked is bounded by the reported speed`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val before = r.odoM
        // 300 s parked, speed alternating 0.7 / 0.2 (worst realistic GPS noise at a standstill).
        repeat(300) { i -> t += 1.0; r.tick(d, t, if (i % 2 == 0) 0.7 else 0.2) }
        println("jitter: phantom=${"%.0f".format(r.odoM - before)} m over 300 s parked (6 m before, 1800 m pre-c672e99), alerts=${r.alertsFired}")
        assertEquals("150 above-threshold samples x the 0.7 m/s they report", 105.0, r.odoM - before, 0.01)
        assertTrue("far below the cruising-speed fabrication it replaces", r.odoM - before < 200.0)
    }

    /** Dropout -> stop -> dropout is ONE loss, not two. The quality must not flip back to LIVE at a red
     *  light (it did, which is how lock B's real loss went unannounced), and the window must resume
     *  where it left off rather than restarting. */
    @Test fun `dropout to stop to dropout is one continuous loss`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 8.0; t += 1.0; r.tick(d, t, 8.0) }
        val frozenAt = d
        repeat(40) { t += 1.0; r.tick(frozenAt, t, 8.0) }        // 40 s blind -> LONG_LOSS after 30 s
        assertEquals(CoastQuality.LONG_LOSS, r.coast.quality)
        t += 1.0; r.tick(frozenAt, t, 0.0)                        // one stopped tick
        assertEquals("a stop cannot demote a live loss to LIVE", CoastQuality.LONG_LOSS, r.coast.quality)
        assertEquals("nor rewind its clock", 40.0, r.coastS, 1e-9)
        repeat(20) { t += 1.0; r.tick(frozenAt, t, 8.0) }         // blind again
        println("dropout/stop/dropout: coastS=${"%.0f".format(r.coastS)} (a 40 s + 20 s loss reads as 60 s, was 20 s)")
        assertEquals("the second stretch continues the first", 60.0, r.coastS, 1e-9)
        // ...and a real fix clears it, which is the ONLY thing that may.
        t += 1.0; r.tick(frozenAt + 8.0, t, 8.0)
        assertEquals(CoastQuality.LIVE, r.coast.quality)
        assertEquals(0.0, r.coastS, 1e-9)
    }

    /** A pause: ELAPSED_TIME freezes while ticks keep arriving. Because the estimator's clock IS
     *  elapsed, a pause of any length contributes zero coast and zero loss. */
    @Test fun `a paused ride contributes zero coast however long the pause`() {
        val r = VpRig()
        var d = 0.0; var t = 0.0
        repeat(100) { d += 6.0; t += 1.0; r.tick(d, t, 6.0) }
        val before = r.odoM
        repeat(1_800) { r.tick(d, t, null) }  // 30 min of paused ticks: elapsedS FROZEN, speed unavailable
        println("pause: phantom=${"%.1f".format(r.odoM - before)} m over a 30 min pause")
        assertEquals("elapsed-driven clock makes a pause a no-op", 0.0, r.odoM - before, 1e-9)
        assertEquals(0.0, r.coastS, 1e-9)
        assertEquals(0, r.alertsFired)
    }

    /** The recorder. It is fed the RAW `distM` (KGhostExtension.kt:1866), never
     *  `coast.effectiveDistanceM`, so no coast can reach the rider's own recorded track — and therefore
     *  cannot reach the pace model built from it. */
    @Test fun `the recorder never sees the coasted odometer`() {
        val rec = TrackRecorder()
        val c = CoastingEstimator()
        var d = 0.0; var t = 0.0
        repeat(60) { d += 20.0; t += 1.0; c.update(d, 20.0, t); rec.onSample(0.0, d * 1e-5, d, t) }
        val frozenAt = d
        repeat(60) { t += 1.0; c.update(frozenAt, 20.0, t); rec.onSample(0.0, frozenAt * 1e-5, frozenAt, t) }
        val track = rec.build("adv2", 0L)!!
        println("recorder: coast odo=${"%.0f".format(c.effectiveDistanceM)} m, recorded tail=${"%.0f".format(track.points.last().distanceM)} m")
        assertTrue("the coast dead-reckoned 1200 m", c.effectiveDistanceM > frozenAt + 1_000.0)
        assertEquals("the track ends at the RAW distance, uncoasted", frozenAt, track.points.last().distanceM, 1e-9)
    }
}
