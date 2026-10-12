package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-enabling KGhost mid-ride (per-profile enable / master switch) must keep the lead: the disabled
 * stretch contributes exactly 0. The integrator gets no ticks while the gate is inactive, so without the
 * re-anchor its next dd charged the whole stretch at ONE endpoint pace (+200 s on HISTORY, +400 s when that
 * tick also froze the race clock; TARGET too; a backward coast correction subtracted the inactive seconds).
 *
 * [Rig] replicates the route tick in production order — coast.update BEFORE the gate, the gate's inactive
 * branch (snapshot + interstitial), then [gateResume], the [raceClockFreezes] block, the guarded onTick and
 * [routeGapPublication]. The extension's wiring itself has no unit harness: [Rig.tick] is its mirror, so the
 * decision ([gateResume]) and the freeze/publication policies are shared with production, the field
 * assignments around them are replicated.
 */
class GateInterstitialPipelineTest {

    private class Rig(val cmp: RaceComparator, val target: Double = 0.25) {
        val coast = CoastingEstimator()
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.3, decimateM = 20.0)
        private var moveStart: Double? = null
        private var prevEl: Double? = null
        private var integLast = 0.0
        private var integInit = false
        private var verdictSeen = false
        private var published = false
        private var gateInterstitial = false
        private var snapshot: GapState? = null
        private var afterVp = false
        /** False = no route loaded: the active tick takes the VP branch (production's `else` of `rm != null`). */
        var routeLoaded = true
        /** What the field shows (GapStateHolder). gapDistanceM stands in for the route-derived metres. */
        var shown: GapState = GapState.inactive(); private set
        var lastDecision = GateResume.NORMAL; private set

        /** [hist] = the history tier's answer at this position (ignored by TARGET). */
        fun tick(raw: Double, t: Double, speed: Double?, hist: Double?, active: Boolean = true) {
            coast.update(raw, speed, t)
            if (!active) {
                if (!gateInterstitial && integInit) {
                    snapshot = shown.takeIf { published && it.active }
                    gateInterstitial = true
                }
                shown = GapState.inactive()
                return
            }
            if (moveStart == null && speed != null && speed > StalenessLogic.MIN_MOVING_MS) moveStart = t
            if (!routeLoaded) {
                // VP branch: consumes the interstitial once no pending interval straddles the gate (mirror of
                // production, same gateResume decision), then hands off via afterVp.
                if (gateResume(gateInterstitial, coast.pendingHold) == GateResume.REANCHOR) {
                    moveStart?.let { m ->
                        g.rebase(coast.effectiveDistanceM, t - m)
                        integLast = coast.effectiveDistanceM
                        prevEl = t
                    }
                    gateInterstitial = false
                    snapshot = null
                }
                published = false
                if (integInit) afterVp = true
                shown = GapState.inactive() // stand-in for the VP number
                return
            }
            var ms = moveStart ?: return
            val riderDist = coast.effectiveDistanceM
            lastDecision = gateResume(gateInterstitial, coast.pendingHold)
            when (lastDecision) {
                GateResume.WAIT_REPUBLISH -> {
                    prevEl = t
                    shown = snapshot ?: GapState.inactive()
                    return
                }
                GateResume.REANCHOR -> {
                    g.rebase(riderDist, t - ms)
                    integLast = riderDist
                    prevEl = t // load-bearing: without it the re-enable tick freezes/counts the whole stretch
                    // No integInit reset (production dropped it): REANCHOR ⇒ !pendingHold, the guard passes.
                    published = false
                    gateInterstitial = false
                    snapshot = null
                }
                GateResume.NORMAL -> Unit
            }
            val p = prevEl
            val stoppedNow = speed != null && speed < StalenessLogic.MIN_MOVING_MS
            if (p != null && t > p &&
                raceClockFreezes(stoppedNow, coast.settledPending, riderDist > integLast, coast.pendingSample)
            ) {
                ms += (t - p); moveStart = ms
            }
            prevEl = t
            val paceNow = selectRacePace(cmp, verdictAllowed(true, coast.quality), 1.0 / target, { hist }, { null })
            if (!coast.pendingHold || !integInit) {
                val before = g.matchedM
                val tickPace = paceAfterInterstitial(cmp, afterVp, paceNow)
                g.onTick(riderDist, 0.0, riderDist * 1e-5, 90.0, t - ms) { _, _, _ -> tickPace }
                afterVp = false
                if (cmp == RaceComparator.HISTORY && consumedHistory(before, g.matchedM)) verdictSeen = true
                integInit = true
                integLast = riderDist
            }
            when (routeGapPublication(cmp, verdictSeen, coast.pendingHold, published)) {
                RouteGapPublication.INACTIVE -> shown = GapState.inactive()
                RouteGapPublication.HOLD -> Unit
                RouteGapPublication.PUBLISH -> {
                    // Behind, production derives the metres from the route curve at the rider's position
                    // (not integ.gapDistM, which reads 0 there) — stand-in: the lead at this tick's pace.
                    val metres = if (g.gapTimeS < 0 && paceNow != null) g.gapTimeS / paceNow else g.gapDistM
                    shown = GapState(g.gapTimeS, metres, riderDist, 0.0, g.gapTimeS > 0, false, true)
                    published = true
                }
            }
        }

        /** The lead the checkpoint persists (integ.gapTimeS). */
        val lead get() = g.gapTimeS
    }

    private var d = 0.0
    private var t = 0.0
    private fun Rig.ride(n: Int, v: Double, hist: Double?, active: Boolean = true) =
        repeat(n) { d += v; t += 1.0; tick(d, t, v, hist, active) }
    private fun Rig.stop(n: Int, hist: Double?, active: Boolean = true) =
        repeat(n) { t += 1.0; tick(d, t, 0.0, hist, active) }

    @Test fun `pure decision`() {
        assertEquals(GateResume.NORMAL, gateResume(gateInterstitial = false, pendingHold = false))
        assertEquals(GateResume.NORMAL, gateResume(gateInterstitial = false, pendingHold = true))
        assertEquals(GateResume.WAIT_REPUBLISH, gateResume(gateInterstitial = true, pendingHold = true))
        assertEquals(GateResume.REANCHOR, gateResume(gateInterstitial = true, pendingHold = false))
    }

    @Test fun `inactive tick - keeps what exists resumable, starts nothing`() {
        assertEquals(InactiveTick(snapshot = true, refreshVpEvidence = true, refreshCheckpoint = true),
            inactiveTick(gateInterstitial = false, hasIntegrator = true, hasVpOrigin = true, hasRoute = true))
        // Snapshot once per stretch; refreshes every (throttled) tick.
        assertEquals(InactiveTick(snapshot = false, refreshVpEvidence = true, refreshCheckpoint = true),
            inactiveTick(gateInterstitial = true, hasIntegrator = true, hasVpOrigin = true, hasRoute = true))
        // No route: the checkpoint has no routeKey to carry — VP evidence only.
        assertEquals(InactiveTick(snapshot = true, refreshVpEvidence = true, refreshCheckpoint = false),
            inactiveTick(gateInterstitial = false, hasIntegrator = true, hasVpOrigin = true, hasRoute = false))
        // Nothing started yet: nothing to refresh, nothing to start.
        assertEquals(InactiveTick(snapshot = false, refreshVpEvidence = false, refreshCheckpoint = false),
            inactiveTick(gateInterstitial = false, hasIntegrator = false, hasVpOrigin = false, hasRoute = true))
    }

    @Test fun `a checkpoint refreshed while disabled resumes after a restart past the margin`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        val lead = r.lead
        val stale = raceCheckpoint(1L, lead, r.coast.effectiveDistanceM, GhostPick.AVERAGE, 0.3, 0L, "k", t,
            RaceComparator.HISTORY, true)
        r.ride(200, 6.0, 0.4, active = false) // 1200 m disabled
        // The inactive tick's write: the race's lead re-anchored at the CURRENT odometer and clock.
        val refreshed = raceCheckpoint(1L, r.lead, r.coast.effectiveDistanceM, GhostPick.AVERAGE, 0.3, 0L, "k", t,
            RaceComparator.HISTORY, true)
        val distNow = r.coast.effectiveDistanceM - 100.0 // restart: odometer back a little, clock carried on
        val elapsedNow = t + 60.0
        assertFalse("pre-disable evidence is 1200 m stale", stale.continuesRide(2L, distNow, elapsedNow))
        assertTrue(refreshed.continuesRide(2L, distNow, elapsedNow))
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.3)
        g.restore(refreshed.leadS, refreshed.lastRiderDist)
        g.onTick(distNow, 0.0, 0.0, 90.0, 5.0) { _, _, _ -> 0.4 }
        assertEquals("lead resumes, the disabled stretch charged nothing", lead, g.gapTimeS, 1e-9)
    }

    @Test fun `HISTORY - the disabled stretch is neither earned nor lost`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        val before = r.lead
        val beforeM = r.shown.gapDistanceM
        assertEquals(20.0, before, 1.0)
        assertTrue(beforeM > 50.0)
        r.ride(200, 6.0, 0.4, active = false) // 1200 m while disabled, slow-history road at the far end
        r.ride(1, 6.0, 0.4) // re-enable tick
        assertEquals(GateResume.REANCHOR, r.lastDecision)
        assertEquals("checkpoint lead after the re-anchor tick", before, r.lead, 1e-9)
        assertEquals("displayed seconds", before, r.shown.gapTimeS, 1e-9)
        assertEquals("displayed metres (restore() collapsed them to 0)", beforeM, r.shown.gapDistanceM, 1e-9)
        // and the race carries on normally from there: 10 s at 6 m/s on a 0.4 s/m road = +14 s
        r.ride(10, 6.0, 0.4)
        assertEquals(before + 14.0, r.lead, 1e-6)
    }

    @Test fun `TARGET - the disabled stretch is neither earned nor lost`() {
        val r = Rig(RaceComparator.TARGET, target = 4.0)
        r.ride(100, 6.0, null)
        val before = r.lead
        val beforeM = r.shown.gapDistanceM
        assertTrue(before > 10.0)
        assertTrue(beforeM > 50.0)
        r.ride(200, 6.0, null, active = false)
        r.ride(1, 6.0, null)
        assertEquals(before, r.lead, 1e-9)
        assertEquals(before, r.shown.gapTimeS, 1e-9)
        assertEquals(beforeM, r.shown.gapDistanceM, 1e-9)
    }

    @Test fun `forward distance jump during the gap is excluded`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        val before = r.lead
        r.ride(30, 6.0, 0.2, active = false)
        d += 2_000.0; t += 1.0; r.tick(d, t, 6.0, 0.2, active = false) // odometer glitch while inert
        r.ride(30, 6.0, 0.2, active = false)
        r.ride(1, 6.0, 0.2)
        assertEquals(before, r.lead, 1e-9)
    }

    @Test fun `backward coast correction during the gap does not subtract the inactive seconds`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        // Dropout while active: raw frozen, speed 10 m/s → coast dead-reckons past the tolerance (neutral fill).
        repeat(30) { t += 1.0; r.tick(d, t, 10.0, 0.2) }
        assertTrue("coast overshot", r.coast.effectiveDistanceM - d > 200.0)
        val before = r.lead
        // Disabled: the fix returns BELOW the coasted odometer (snap back), then the rider rides 60 s.
        r.ride(60, 6.0, 0.2, active = false)
        r.ride(1, 6.0, 0.2)
        assertEquals(GateResume.REANCHOR, r.lastDecision)
        assertEquals(before, r.lead, 1e-9)
    }

    @Test fun `stopped and settled re-enable tick`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        val before = r.lead
        r.ride(60, 6.0, 0.4, active = false)
        r.stop(30, 0.4, active = false)
        r.stop(1, 0.4) // re-enabled while stopped, nothing pending
        assertEquals(GateResume.REANCHOR, r.lastDecision)
        assertEquals(before, r.lead, 1e-9)
        r.stop(20, 0.4)
        assertEquals("still frozen through the rest of the stop", before, r.lead, 1e-9)
    }

    @Test fun `a pending interval open at disable that settles after re-enable is excluded`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.2)
        val before = r.lead
        t += 1.0; r.tick(d, t, 6.0, 0.4) // raw lags: pending sample opens on the last active tick
        assertTrue(r.coast.pendingHold)
        r.stop(40, 0.4, active = false) // stops inside the interval — the hold persists
        assertTrue(r.coast.pendingHold)
        r.stop(5, 0.4) // re-enabled: hold unresolved → wait, republish
        assertEquals(GateResume.WAIT_REPUBLISH, r.lastDecision)
        assertEquals(before, r.lead, 1e-9)
        d += 6.0; t += 1.0; r.tick(d, t, 0.0, 0.4) // the delayed sample settles while stopped
        assertTrue(r.coast.settledPending)
        assertEquals(GateResume.REANCHOR, r.lastDecision)
        assertEquals("the settled metres are excluded", before, r.lead, 1e-9)
        r.stop(10, 0.4)
        assertEquals(before, r.lead, 1e-9)
    }

    @Test fun `the republished snapshot keeps seconds AND metres through a long stopped hold`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, 0.15) // behind: 600 m * 0.15 - 100 = -10 s
        r.ride(50, 6.0, 0.17) // crosses a pace boundary, still behind
        val published = r.shown
        assertTrue(published.active)
        assertTrue(published.gapTimeS < 0.0)
        assertNotEquals(0.0, published.gapDistanceM, 1e-9)
        t += 1.0; r.tick(d, t, 6.0, 0.17) // pending opens → HOLD keeps `published`
        assertEquals(published, r.shown)
        r.stop(5, 0.17, active = false)
        assertFalse(r.shown.active) // inert: cleared
        repeat(300) {
            t += 1.0; r.tick(d, t, 0.0, 0.4) // stopped on a different-pace road: metres must NOT re-derive
            assertEquals(GateResume.WAIT_REPUBLISH, r.lastDecision)
            assertEquals("seconds AND metres unchanged", published, r.shown)
        }
    }

    @Test fun `re-enabled with no route - the VP stretch still earns its TARGET lead on route reload`() {
        val r = Rig(RaceComparator.TARGET) // target speed 4 m/s = 0.25 s/m
        r.ride(100, 6.0, null)
        val before = r.lead
        r.ride(200, 6.0, null, active = false) // disabled: nothing earned
        r.routeLoaded = false
        // Re-enabled with no route. The re-enable tick itself is folded into the rebase (as the route
        // REANCHOR's tick is); the other 99 VP ticks are 594 m in 99 s at 0.25 s/m = +49.5 s.
        r.ride(100, 6.0, null)
        r.routeLoaded = true
        r.ride(1, 6.0, null) // route reloads: VP stretch + this tick charged at the target (+0.5 s), not re-anchored away
        assertEquals(GateResume.NORMAL, r.lastDecision)
        assertEquals(before + 50.0, r.lead, 1e-6)
    }

    @Test fun `a pending interval straddling the gate settles in VP and is not charged on route reload`() {
        val r = Rig(RaceComparator.TARGET) // target speed 4 m/s = 0.25 s/m
        r.ride(100, 6.0, null)
        val before = r.lead
        t += 1.0; r.tick(d, t, 6.0, null) // raw lags: pending sample opens on the last active tick
        assertTrue(r.coast.pendingHold)
        r.stop(40, null, active = false) // stops inside the interval — the hold persists
        r.routeLoaded = false
        r.stop(5, null) // re-enabled with no route, hold unresolved: VP must NOT consume the interstitial yet
        assertTrue(r.coast.pendingHold)
        d += 12.0; t += 1.0; r.tick(d, t, 0.0, null) // the delayed sample settles (12 m) while in VP
        assertTrue(r.coast.settledPending)
        r.routeLoaded = true
        r.stop(5, null) // route reloads while stopped
        assertEquals("the straddling 12 m are excluded (+0 s, not 3 s)", before, r.lead, 1e-9)
    }

    @Test fun `a HISTORY race with no verdict stays blank while waiting`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0, null) // novel ground: nothing compared → "---"
        assertFalse(r.shown.active)
        t += 1.0; r.tick(d, t, 6.0, null)
        r.stop(5, null, active = false)
        r.stop(10, null)
        assertEquals(GateResume.WAIT_REPUBLISH, r.lastDecision)
        assertFalse(r.shown.active)
    }
}
