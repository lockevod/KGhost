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
        assertNotSame(original.routeGhost, switched.routeGhost)
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
        assertEquals(switched.path.totalM / 4.0, switched.routeGhost!!.totalTimeS, 1e-6)
    }

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
            routeGhost = RouteGhost.build(path.totalM, segments, fillSpeedM = 4.0),
            routeDistanceM = 400.0,
            pacePatch = PacePatch.build(emptyList()),
            gradePace = GradePace.Builder().build(),
            aggregate = aggregate,
            comparator = comparator,
        )
    }
}
