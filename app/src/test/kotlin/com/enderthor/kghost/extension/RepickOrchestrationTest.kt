package com.enderthor.kghost.extension

import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.engine.PacePatch
import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.PolylinePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repick ORCHESTRATION — which branch a settings change takes, and whether a resolved result may
 * still be published. `RouteModeTest` covers the pure `withPick` transform; this covers the decisions
 * around it, which is where the cross-thread overwrite was found.
 */
class RepickOrchestrationTest {

    private fun sig(active: Boolean = true, race: Boolean = true, pick: GhostPick = GhostPick.BEST) =
        KGhostExtension.MatchSig(active, race, pick)

    private fun mode(polyline: String) = KGhostExtension.RouteMode(
        path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.004))),
        polyline = polyline,
        routeName = "R",
        segments = emptyList(),
        routeGhost = null,
        routeDistanceM = 400.0,
        pacePatch = PacePatch.build(emptyList()),
        gradePace = null,
        aggregate = null,
    )

    // --- which branch (the cancels hang off these) ---------------------------------------------

    @Test fun `nothing claimed or stashed does nothing`() {
        assertEquals(
            RematchAction.NONE,
            rematchActionFor(sig(), sig(pick = GhostPick.LAST), null, null, hasPendingNav = false),
        )
    }

    @Test fun `a pick-only change on the claimed route repicks`() {
        assertEquals(
            RematchAction.REPICK,
            rematchActionFor(sig(), sig(pick = GhostPick.LAST), "P1", "P1", hasPendingNav = false),
        )
    }

    @Test fun `a pick change during the first match restarts it instead of repicking`() {
        // routeMode is still null (nothing published), but the polyline is claimed → the match is in
        // flight under the OLD pick and must be restarted, not left to publish stale settings.
        assertEquals(
            RematchAction.RESTART_MATCH,
            rematchActionFor(sig(), sig(pick = GhostPick.LAST), null, "P1", hasPendingNav = false),
        )
    }

    @Test fun `a pick change while a NEWER route is already claimed cannot repick`() {
        // routeMode still holds P1 while the claim moved to P2: repicking P1 would resolve against a
        // route that is already superseded.
        assertEquals(
            RematchAction.FULL_REMATCH,
            rematchActionFor(sig(), sig(pick = GhostPick.LAST), "P1", "P2", hasPendingNav = false),
        )
    }

    @Test fun `a change that also flips the gate never takes the fast path`() {
        for (next in listOf(sig(active = false, pick = GhostPick.LAST), sig(race = false, pick = GhostPick.LAST))) {
            assertEquals(
                "gate change must re-run the match, not reuse the loaded models",
                RematchAction.FULL_REMATCH,
                rematchActionFor(sig(), next, "P1", "P1", hasPendingNav = false),
            )
        }
    }

    @Test fun `no previous signature falls back to a full re-match`() {
        assertEquals(
            RematchAction.FULL_REMATCH,
            rematchActionFor(null, sig(pick = GhostPick.LAST), "P1", "P1", hasPendingNav = false),
        )
    }

    @Test fun `a stashed nav event while not recording is not a match restart`() {
        // Nothing is in flight to restart — startTick replays the stash. Clearing the claim here would
        // be the only effect, so this must take the ordinary path.
        assertEquals(
            RematchAction.FULL_REMATCH,
            rematchActionFor(sig(), sig(pick = GhostPick.LAST), null, "P1", hasPendingNav = true),
        )
    }

    // --- may the resolved repick be published? (identity + the cross-thread race) ---------------

    @Test fun `a repick publishes when its route is still the live one`() {
        val s = sig(pick = GhostPick.LAST)
        val current = mode("P1")
        assertTrue(repickStillValid(s, s, current, current, "P1"))
    }

    @Test fun `a repick drops when another mode was published under it`() {
        val s = sig(pick = GhostPick.LAST)
        val current = mode("P1")
        assertFalse(repickStillValid(s, s, mode("P1"), current, "P1"))
    }

    /**
     * REGRESSION. The match publishes routeMode on Default while this check runs on Main, so a new
     * route can claim the polyline and publish between the guard and the assignment. Identity alone
     * still holds at that instant, so without the polyline term the repick overwrites the NEW route's
     * mode with the OLD one — and the nav dedup makes that permanent for the rest of the ride.
     */
    @Test fun `a repick drops when a newer route claimed the polyline mid-flight`() {
        val s = sig(pick = GhostPick.LAST)
        val current = mode("P1")
        assertFalse(repickStillValid(s, s, current, current, "P2"))
    }

    @Test fun `a repick drops when the settings moved on again`() {
        val current = mode("P1")
        assertFalse(repickStillValid(sig(pick = GhostPick.AVERAGE), sig(pick = GhostPick.LAST), current, current, "P1"))
    }

    // --- may the finished match publish? (generation) -------------------------------------------

    @Test fun `a match publishes while it still owns its claim`() {
        assertTrue(matchStillOwns("P1", "P1", generation = 7L, liveGeneration = 7L))
    }

    /**
     * REGRESSION. The claim is the polyline STRING, so a settings-driven re-match of the SAME route
     * re-claims an equal string: a cancelled-but-surviving match would find its own claim intact and
     * publish state built under the OLD settings over its replacement's. The generation is the only
     * thing that separates the two claims.
     */
    @Test fun `a superseded match cannot publish just because the polyline matches again`() {
        assertFalse(matchStillOwns("P1", "P1", generation = 7L, liveGeneration = 8L))
    }

    @Test fun `a match drops when a different route took the claim`() {
        assertFalse(matchStillOwns("P1", "P2", generation = 7L, liveGeneration = 7L))
    }
}
