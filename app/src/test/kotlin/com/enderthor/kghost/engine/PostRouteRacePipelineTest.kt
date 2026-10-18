package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After the route ends, the field keeps showing THE SAME B2 race (same integrator, lead, latched comparator,
 * race clock) instead of the stateless whole-ride Ghost-Pace race; Fixed pace ends the race.
 *
 * [Rig] mirrors the tick in production order and only WIRES the engine functions the extension calls: route
 * ticks [gateResume] → [advanceRace] → [routeGapPublication]; no-route ticks [noRouteAction] → [postRouteStep]
 * → [postRouteGap] → [routeGapPublication]. Every decision lives in those functions; the rig replicates only
 * the field assignments around them (rebase, endB2Race's resets), as [GateInterstitialPipelineTest] does.
 */
class PostRouteRacePipelineTest {

    private class Rig(val cmp: RaceComparator, val target: Double = 5.0) {
        val coast = CoastingEstimator()
        var g: GhostIntegrator? = null; private set
        val st = RaceStepState()
        private var published = false
        private var gateInterstitial = false
        private var snapshot: GapState? = null
        var routeLoaded = true
        var raceEnabled = true
        var fixOk = true
        var gradeFresh = true
        /** PacePatch's answer (route ticks only — post-route has no history tier). */
        var patch: Double? = null
        /** GradePace's answer at the current gradient; null = no model. */
        var grade: Double? = null
        var gradeCalls = 0; private set
        var patchCalls = 0; private set
        /** What the field shows (GapStateHolder). */
        var shown: GapState = GapState.inactive(); private set
        var lastAction: NoRouteAction? = null; private set
        val lead get() = g!!.gapTimeS

        fun tick(raw: Double, t: Double, speed: Double?, active: Boolean = true) {
            coast.update(raw, speed, t)
            if (!active) {
                if (!gateInterstitial && g != null) {
                    snapshot = shown.takeIf { published && it.active }
                    gateInterstitial = true
                }
                shown = GapState.inactive()
                return
            }
            if (st.moveStartS == null && speed != null && speed > StalenessLogic.MIN_MOVING_MS) st.moveStartS = t
            val riderDist = coast.effectiveDistanceM
            lastAction = null
            if (routeLoaded) {
                routeTick(riderDist, t, speed)
                return
            }
            val action = noRouteAction(g != null, raceEnabled, gateInterstitial, coast.pendingHold)
            lastAction = action
            when (action) {
                NoRouteAction.VP -> { shown = VP_SHOWN; return }
                NoRouteAction.END_RACE_THEN_VP -> { endRace(); shown = VP_SHOWN; return }
                NoRouteAction.POST_WAIT_REPUBLISH -> {
                    st.prevTickElapsedS = t
                    shown = snapshot ?: GapState.inactive()
                    return
                }
                NoRouteAction.POST_REANCHOR_THEN_STEP -> reanchor(riderDist, t)
                NoRouteAction.POST_STEP -> Unit
            }
            val integ = g!!
            val step = postRouteStep(
                st, integ, cmp, coast, riderDist, t, speed, 0.0, riderDist * 1e-5, 90.0,
                fixOk, gradeFresh, target,
            ) { gradeCalls++; grade }
            publish(postRouteGap(integ, cmp, step, riderDist, target, coast.quality == CoastQuality.LONG_LOSS))
        }

        private fun routeTick(riderDist: Double, t: Double, speed: Double?) {
            st.moveStartS ?: return
            when (gateResume(gateInterstitial, coast.pendingHold)) {
                GateResume.WAIT_REPUBLISH -> {
                    st.prevTickElapsedS = t
                    shown = snapshot ?: GapState.inactive()
                    return
                }
                GateResume.REANCHOR -> reanchor(riderDist, t)
                GateResume.NORMAL -> Unit
            }
            var integ = g
            if (integ == null) {
                integ = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 1.0 / target, decimateM = 20.0)
                g = integ
                st.resetForNewRace()
                published = false
            }
            advanceRace(
                st, integ, cmp, coast, riderDist, t, speed, 0.0, riderDist * 1e-5, 90.0,
                fixOk, gradeFresh, target,
                historyPace = { patchCalls++; patch }, gradePace = { gradeCalls++; grade },
            )
            // Production's behind-metres come from the route marker curve; the seconds are what matter here.
            publish(GapState(-integ.gapTimeS, integ.gapDistM, riderDist, riderDist - integ.gapDistM,
                integ.gapTimeS > 0.0, coast.quality == CoastQuality.LONG_LOSS, true))
        }

        private fun reanchor(riderDist: Double, t: Double) {
            g?.rebase(riderDist, t - st.moveStartS!!)
            st.lastRiderDist = riderDist
            st.prevTickElapsedS = t
            published = false
            gateInterstitial = false
            snapshot = null
        }

        private fun publish(gap: GapState) {
            when (routeGapPublication(cmp, st.historyVerdictSeen, coast.pendingHold, published)) {
                RouteGapPublication.INACTIVE -> shown = GapState.inactive()
                RouteGapPublication.HOLD -> Unit
                RouteGapPublication.PUBLISH -> { shown = gap; published = true }
            }
        }

        /** Mirror of the extension's endB2Race field resets. */
        private fun endRace() {
            g = null
            published = false
            gateInterstitial = false
            snapshot = null
            st.resetForNewRace()
            st.lastRiderDist = 0.0
            st.prevTickElapsedS = null
        }
    }

    private var d = 0.0
    private var t = 0.0
    private fun Rig.ride(n: Int, v: Double, active: Boolean = true) =
        repeat(n) { d += v; t += 1.0; tick(d, t, v, active) }
    private fun Rig.stop(n: Int, active: Boolean = true) =
        repeat(n) { t += 1.0; tick(d, t, 0.0, active) }

    // --- the race continues ---

    @Test fun `seconds continuous at route clear - TARGET`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(100, 6.0)
        val before = r.shown.gapTimeS
        assertTrue("a lead was earned on the route", before < -10.0)
        r.routeLoaded = false
        r.ride(1, 5.0) // at the target speed: the tick itself earns nothing
        assertEquals(NoRouteAction.POST_STEP, r.lastAction)
        assertEquals(before, r.shown.gapTimeS, 0.01)
    }

    @Test fun `seconds continuous at route clear - HISTORY`() {
        val r = Rig(RaceComparator.HISTORY)
        r.patch = 0.2
        r.ride(100, 6.0)
        val before = r.shown.gapTimeS
        assertTrue(before < -10.0)
        r.routeLoaded = false
        r.ride(1, 6.0)
        assertEquals(NoRouteAction.POST_STEP, r.lastAction)
        assertEquals(before, r.shown.gapTimeS, 0.01)
    }

    @Test fun `TARGET keeps accruing post-route`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(50, 6.0)
        r.routeLoaded = false
        val lead0 = r.lead
        r.ride(60, 6.0) // 360 m at 0.2 s/m = 72 s of ghost time in 60 s
        assertEquals(lead0 + 12.0, r.lead, 0.1)
    }

    @Test fun `fresh fix, stale gradient post-route - neutral, GradePace not consulted, HISTORY behind-metres NaN`() {
        val r = Rig(RaceComparator.HISTORY)
        r.patch = 0.15 // behind: 6 m/s * 0.15 = 0.9 s per 1 s
        r.ride(100, 6.0)
        val lead0 = r.lead
        assertTrue(lead0 < -5.0)
        r.routeLoaded = false
        r.grade = 0.3
        r.gradeFresh = false
        val calls = r.gradeCalls
        r.ride(30, 6.0)
        assertEquals(lead0, r.lead, 1e-9)
        assertEquals("GradePace not consulted", calls, r.gradeCalls)
        assertTrue(r.shown.active)
        assertTrue(r.shown.gapTimeS > 0.0)
        assertTrue(r.shown.gapDistanceM.isNaN())
    }

    @Test fun `HISTORY without GradePace holds the lead while riding`() {
        val r = Rig(RaceComparator.HISTORY)
        r.patch = 0.2
        r.ride(100, 6.0)
        r.routeLoaded = false
        val lead0 = r.lead
        r.ride(60, 6.0) // patch still answers 0.2 — post-route must not consult it
        assertEquals(lead0, r.lead, 0.01)
    }

    @Test fun `HISTORY with GradePace accrues at the GradePace rate`() {
        val r = Rig(RaceComparator.HISTORY)
        r.patch = 0.2
        r.ride(100, 6.0)
        r.routeLoaded = false
        r.patch = 0.1 // a patch answer would SUBTRACT 24 s here
        r.grade = 0.25 // 6 m/s * 0.25 = 1.5 s per 1 s → +0.5 s/s
        val lead0 = r.lead
        val patchCalls = r.patchCalls
        r.ride(60, 6.0)
        assertEquals(lead0 + 30.0, r.lead, 0.01)
        assertEquals("PacePatch is never consulted post-route", patchCalls, r.patchCalls)
    }

    @Test fun `HISTORY with no verdict shows --- then a number once GradePace consumes`() {
        val r = Rig(RaceComparator.HISTORY)
        r.ride(100, 6.0) // novel ground, no model: nothing compared
        assertFalse(r.shown.active)
        r.routeLoaded = false
        r.ride(5, 6.0)
        assertEquals(NoRouteAction.POST_STEP, r.lastAction)
        assertFalse("still ---, not the VP number", r.shown.active)
        r.grade = 0.2
        r.ride(1, 6.0)
        assertTrue(r.shown.active)
        assertEquals(-0.2, r.shown.gapTimeS, 1e-6) // 6 m * 0.2 = 1.2 s of ghost in 1 s
    }

    @Test fun `stopped post-route freezes the clock`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(50, 6.0)
        r.routeLoaded = false
        r.ride(10, 6.0)
        val lead0 = r.lead
        r.stop(60)
        assertEquals(lead0, r.lead, 1e-9)
        assertEquals(-lead0, r.shown.gapTimeS, 1e-9)
    }

    @Test fun `stale fix post-route is neutral - TARGET behind-metres still exact, HISTORY behind-metres NaN`() {
        val tr = Rig(RaceComparator.TARGET, target = 6.9)
        tr.ride(100, 6.0) // behind: 6/6.9 s of ghost per second
        tr.routeLoaded = false
        tr.fixOk = false
        val lead0 = tr.lead
        assertTrue(lead0 < -10.0)
        tr.ride(30, 6.0)
        assertEquals("no lead minted or lost", lead0, tr.lead, 1e-9)
        assertEquals("deficit × target speed", lead0 * 6.9, tr.shown.gapDistanceM, 1e-6)

        d = 0.0; t = 0.0
        val hr = Rig(RaceComparator.HISTORY)
        hr.patch = 0.15
        hr.ride(100, 6.0)
        hr.routeLoaded = false
        hr.fixOk = false
        hr.grade = 0.3
        val hLead = hr.lead
        hr.ride(30, 6.0)
        assertEquals(hLead, hr.lead, 1e-9)
        assertTrue(hr.shown.gapDistanceM.isNaN())
    }

    @Test fun `pending interval across the clear is settled once`() {
        // The same raw sequence with the route kept vs cleared on the tick the pending interval opens.
        fun run(clear: Boolean): Double {
            var dd = 0.0; var tt = 0.0
            val r = Rig(RaceComparator.TARGET, target = 5.0)
            repeat(50) { dd += 6.0; tt += 1.0; r.tick(dd, tt, 6.0) }
            tt += 1.0; r.tick(dd, tt, 6.0) // raw lags: pending sample opens on the last route tick
            assertTrue(r.coast.pendingHold)
            if (clear) r.routeLoaded = false
            tt += 1.0; r.tick(dd, tt, 6.0)
            dd += 18.0; tt += 1.0; r.tick(dd, tt, 6.0) // the delayed samples arrive
            repeat(10) { dd += 6.0; tt += 1.0; r.tick(dd, tt, 6.0) }
            assertFalse(r.coast.pendingHold)
            if (clear) assertEquals(NoRouteAction.POST_STEP, r.lastAction)
            return r.lead
        }
        assertEquals(run(clear = false), run(clear = true), 1e-9)
    }

    @Test fun `gate off-on post-route with a pending interval republishes the snapshot, then REANCHOR is neutral`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(50, 6.0)
        r.routeLoaded = false
        r.ride(10, 6.0)
        val published = r.shown
        assertTrue(published.active)
        val before = r.lead
        t += 1.0; r.tick(d, t, 6.0) // pending opens post-route → HOLD
        assertTrue(r.coast.pendingHold)
        r.stop(5, active = false)
        assertFalse(r.shown.active)
        r.stop(3)
        assertEquals(NoRouteAction.POST_WAIT_REPUBLISH, r.lastAction)
        assertEquals(published, r.shown)
        d += 6.0; t += 1.0; r.tick(d, t, 0.0) // the delayed sample settles while stopped
        assertEquals(NoRouteAction.POST_REANCHOR_THEN_STEP, r.lastAction)
        assertEquals("the straddling metres are excluded", before, r.lead, 1e-9)
        r.stop(10)
        assertEquals(before, r.lead, 1e-9)
    }

    @Test fun `route returns after a post-route stretch - continuous, stretch not neutralised`() {
        val r = Rig(RaceComparator.HISTORY)
        r.patch = 0.2
        r.ride(50, 6.0)
        r.routeLoaded = false
        r.grade = 0.25
        r.ride(20, 6.0)
        val lead0 = r.lead
        r.routeLoaded = true
        r.ride(1, 6.0) // first route tick: its own 6 m at the patch's 0.2 s/m → +0.2 s, nothing neutralised
        assertEquals(lead0 + 0.2, r.lead, 1e-6)
    }

    @Test fun `behind over many post-route ticks never reads 0 m`() {
        val r = Rig(RaceComparator.TARGET, target = 6.9)
        r.ride(50, 6.0)
        r.routeLoaded = false
        repeat(200) {
            r.ride(1, 6.0)
            assertTrue("tick $it: ${r.shown.gapDistanceM}", r.shown.gapDistanceM < -1.0)
        }
    }

    @Test fun `auto-pause spanning the clear - no jump on resume`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(50, 6.0)
        val lead0 = r.lead
        // Auto-pause: ELAPSED_TIME freezes, odometer static; the route clears during it.
        repeat(5) { r.tick(d, t, 0.0) }
        r.routeLoaded = false
        repeat(20) { r.tick(d, t, 0.0) }
        assertEquals(lead0, r.lead, 1e-9)
        r.ride(1, 6.0) // resume: 6 m in 1 s at 0.2 s/m → +0.2 s
        assertEquals(lead0 + 0.2, r.lead, 1e-6)
    }

    @Test fun `fixed pace mid-race ends the race - back to Your rides with a route starts at 0`() {
        val r = Rig(RaceComparator.TARGET, target = 5.0)
        r.ride(50, 6.0)
        r.routeLoaded = false
        r.ride(5, 6.0)
        r.raceEnabled = false
        r.ride(1, 6.0)
        assertEquals(NoRouteAction.END_RACE_THEN_VP, r.lastAction)
        assertNull(r.g)
        assertEquals(VP_SHOWN, r.shown)
        r.ride(1, 6.0)
        assertEquals(NoRouteAction.VP, r.lastAction)
        r.raceEnabled = true
        r.routeLoaded = true
        r.ride(1, 6.0)
        assertNotNull(r.g)
        assertEquals("a fresh race anchors at 0", 0.0, r.lead, 1e-9)
        r.ride(10, 6.0)
        assertEquals(2.0, r.lead, 1e-6)
    }

    @Test fun `ride that never had a route - noRouteAction is VP`() {
        val r = Rig(RaceComparator.TARGET)
        r.routeLoaded = false
        r.ride(30, 6.0)
        assertEquals(NoRouteAction.VP, r.lastAction)
        assertNull(r.g)
        assertEquals(VP_SHOWN, r.shown)
    }

    // --- pure decisions ---

    @Test fun `noRouteAction truth table`() {
        for (gi in listOf(false, true)) for (ph in listOf(false, true)) for (re in listOf(false, true)) {
            assertEquals(NoRouteAction.VP, noRouteAction(false, re, gi, ph))
            assertEquals("fixed pace ends the race first (gi=$gi ph=$ph)",
                NoRouteAction.END_RACE_THEN_VP, noRouteAction(true, false, gi, ph))
        }
        assertEquals(NoRouteAction.POST_WAIT_REPUBLISH, noRouteAction(true, true, true, true))
        assertEquals(NoRouteAction.POST_REANCHOR_THEN_STEP, noRouteAction(true, true, true, false))
        assertEquals(NoRouteAction.POST_STEP, noRouteAction(true, true, false, false))
        assertEquals(NoRouteAction.POST_STEP, noRouteAction(true, true, false, true))
    }

    private fun integWithGap(ghostPaceSPerM: Double): GhostIntegrator =
        GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.2).apply {
            onTick(0.0, 0.0, 0.0, 90.0, 0.0) { _, _, _ -> null }
            onTick(100.0, 0.0, 0.001, 90.0, 60.0) { _, _, _ -> ghostPaceSPerM }
        }

    @Test fun `postRouteGap - TARGET behind 60 s at 6_9 m per s is -414 m`() {
        val g = integWithGap(0.0) // ghostTime 0 at race clock 60 → 60 s behind
        assertEquals(-60.0, g.gapTimeS, 1e-9)
        val gap = postRouteGap(g, RaceComparator.TARGET, RaceStepResult("TGT", true, null), 100.0, 6.9, false)
        assertEquals(60.0, gap.gapTimeS, 1e-9)
        assertEquals(-414.0, gap.gapDistanceM, 0.5)
        assertFalse(gap.ahead)
        assertTrue(gap.active)
    }

    @Test fun `postRouteGap - HISTORY behind with no step pace is NaN, with a pace is deficit over pace`() {
        val g = integWithGap(0.0)
        assertTrue(postRouteGap(g, RaceComparator.HISTORY, RaceStepResult("GP", true, null), 100.0, 6.9, false)
            .gapDistanceM.isNaN())
        assertEquals(-240.0,
            postRouteGap(g, RaceComparator.HISTORY, RaceStepResult("GRADE", true, 0.25), 100.0, 6.9, true)
                .gapDistanceM, 1e-9)
    }

    @Test fun `postRouteGap - ahead uses the integrator's metres`() {
        val g = integWithGap(1.0) // ghostTime 100 at race clock 60 → 40 s ahead
        assertEquals(40.0, g.gapTimeS, 1e-9)
        val gap = postRouteGap(g, RaceComparator.TARGET, RaceStepResult("TGT", true, 0.2), 100.0, 6.9, true)
        assertEquals(-40.0, gap.gapTimeS, 1e-9)
        assertEquals(g.gapDistM, gap.gapDistanceM, 0.0)
        assertTrue(gap.ahead)
        assertTrue(gap.estimated)
    }

    private companion object {
        /** Stand-in for the whole-ride VP number. */
        val VP_SHOWN = GapState(-999.0, 999.0, 0.0, 0.0, true, false, true)
    }
}
