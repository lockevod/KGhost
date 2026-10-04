package com.enderthor.kghost.extension

import com.enderthor.kghost.engine.AGG_SCHEMA_VERSION
import com.enderthor.kghost.engine.AggregateNode
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.engine.PacePatch
import com.enderthor.kghost.engine.PerRouteAggregate
import com.enderthor.kghost.engine.RaceComparator
import com.enderthor.kghost.engine.RouteGhost
import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.PolylinePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteModeTest {

    @Test fun `repick rebuilds only pick-dependent route models`() {
        val original = routeMode(GhostPick.BEST)

        val switched = original.withPick(GhostPick.LAST, fillSpeedMs = 4.0)

        assertSame(original.path, switched.path)
        assertSame(original.pacePatch, switched.pacePatch)
        assertSame(original.gradePace, switched.gradePace)
        assertSame(original.aggregate, switched.aggregate)
        assertEquals(original.polyline, switched.polyline)
        assertEquals(original.routeName, switched.routeName)
        assertEquals(original.routeDistanceM, switched.routeDistanceM, 0.0)
        assertEquals(80.0, switched.segments.single().ghost.totalTimeS, 1e-6)
        assertNotSame(original.historyGhost, switched.historyGhost)
    }

    @Test fun `rapid repicks leave the models for the latest pick`() {
        val original = routeMode(GhostPick.BEST)

        val latest = original
            .withPick(GhostPick.LAST, fillSpeedMs = 4.0)
            .withPick(GhostPick.AVERAGE, fillSpeedMs = 4.0)

        assertEquals(64.0, latest.segments.single().ghost.totalTimeS, 1e-6)
        assertSame(original.path, latest.path)
        assertSame(original.aggregate, latest.aggregate)
    }

    // A TARGET race ignores history, so the marker curve must too: a repick must not bring the aggregate's
    // recorded stretches back into a route ghost the number never races.
    @Test fun `repick on a TARGET route keeps it segment-free and all target fill`() {
        val original = routeMode(GhostPick.BEST, RaceComparator.TARGET)

        val switched = original.withPick(GhostPick.LAST, fillSpeedMs = 4.0)

        assertTrue(switched.segments.isEmpty())
        assertEquals(RaceComparator.TARGET, switched.comparator)
        // The whole path at the 4 m/s fill, no recorded stretch (those would race at 5 s per 25 m).
        assertEquals(switched.path.totalM / 4.0, switched.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
    }

    // The marker follows the race's LATCHED comparator, not the route's: one RouteMode answers both.
    @Test fun `ghostFor gives all target fill for TARGET and recorded stretches for HISTORY on the same mode`() {
        val mode = routeMode(GhostPick.BEST)

        assertEquals(mode.path.totalM / 4.0, mode.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
        assertEquals(historyTotal(mode, GhostPick.BEST), mode.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1e-6)
        assertNotEquals(mode.ghostFor(RaceComparator.TARGET)!!.totalTimeS, mode.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1.0)
    }

    // A TARGET race rerouted onto a HISTORY-classified route still races the target on the map.
    @Test fun `a TARGET-latched race on a HISTORY-classified route gets the target curve`() {
        val historyRoute = routeMode(GhostPick.BEST, RaceComparator.HISTORY)

        assertEquals(historyRoute.path.totalM / 4.0, historyRoute.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
    }

    // ...and a HISTORY race rerouted onto a TARGET-classified route keeps its recorded stretches, repick included.
    @Test fun `a HISTORY-latched race on a TARGET-classified route keeps its recorded stretches across a repick`() {
        val targetRoute = routeMode(GhostPick.BEST, RaceComparator.TARGET)
        assertEquals(historyTotal(targetRoute, GhostPick.BEST), targetRoute.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1e-6)

        val switched = targetRoute.withPick(GhostPick.LAST, fillSpeedMs = 4.0)

        assertEquals(historyTotal(switched, GhostPick.LAST), switched.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1e-6)
        assertEquals(switched.path.totalM / 4.0, switched.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
    }

    @Test fun `repick keeps both curves consistent with the new pick`() {
        val switched = routeMode(GhostPick.BEST).withPick(GhostPick.LAST, fillSpeedMs = 4.0)

        assertEquals(historyTotal(switched, GhostPick.LAST), switched.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1e-6)
        assertEquals(switched.path.totalM / 4.0, switched.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
    }

    // A repick carries the CURRENT Ghost-Pace target: both curves must be refilled at it, or the TARGET marker
    // stays at the old target while the number charges the new one.
    @Test fun `repick refills both curves at the current target`() {
        val switched = routeMode(GhostPick.BEST, RaceComparator.TARGET).withPick(GhostPick.LAST, fillSpeedMs = 5.0)

        assertEquals(switched.path.totalM / 5.0, switched.ghostFor(RaceComparator.TARGET)!!.totalTimeS, 1e-6)
        assertEquals(historyTotal(switched, GhostPick.LAST, fill = 5.0), switched.ghostFor(RaceComparator.HISTORY)!!.totalTimeS, 1e-6)
    }

    private fun historyTotal(mode: KGhostExtension.RouteMode, pick: GhostPick, fill: Double = 4.0): Double =
        RouteGhost.build(mode.path.totalM, mode.aggregate!!.toLiveSegments(pick), fillSpeedM = fill)!!.totalTimeS

    private fun routeMode(pick: GhostPick, comparator: RaceComparator = RaceComparator.HISTORY): KGhostExtension.RouteMode {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.004)))
        val aggregate = PerRouteAggregate(
            routeKey = "loop:100",
            routeName = "Loop",
            routeLenM = 400.0,
            stepM = 25.0,
            schemaVersion = AGG_SCHEMA_VERSION,
            nodes = listOf(
                AggregateNode(),
                *List(16) { AggregateNode(dtS = 4.0, count = 2, minDtS = 3.0, lastDtS = 5.0) }.toTypedArray(),
            ),
        )
        val segments = if (comparator == RaceComparator.TARGET) emptyList() else aggregate.toLiveSegments(pick)
        return KGhostExtension.RouteMode(
            path = path,
            polyline = "encoded-loop",
            routeName = "Loop",
            segments = segments,
            historyGhost = RouteGhost.build(path.totalM, aggregate.toLiveSegments(pick), fillSpeedM = 4.0),
            targetGhost = RouteGhost.build(path.totalM, emptyList(), fillSpeedM = 4.0),
            routeDistanceM = 400.0,
            pacePatch = PacePatch.build(emptyList()),
            gradePace = GradePace.Builder().build(),
            aggregate = aggregate,
            comparator = comparator,
        )
    }
}
