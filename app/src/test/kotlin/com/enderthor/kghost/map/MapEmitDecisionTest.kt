package com.enderthor.kghost.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEmitDecisionTest {
    private fun marker(lng: Double) = GhostMarker(lat = 0.0, lng = lng, bearingDeg = 90.0f)

    @Test fun `first marker is shown`() {
        val d = decideMapEmit(lastShown = null, next = marker(0.0), minMoveM = 5.0)
        assertTrue(d is MapEmit.Show && (d as MapEmit.Show).marker.lng == 0.0)
    }

    @Test fun `no marker after one was shown hides it`() {
        assertEquals(MapEmit.Hide, decideMapEmit(lastShown = marker(0.0), next = null, minMoveM = 5.0))
    }

    @Test fun `no marker when none was shown emits nothing`() {
        assertEquals(MapEmit.None, decideMapEmit(lastShown = null, next = null, minMoveM = 5.0))
    }

    @Test fun `tiny move below threshold emits nothing`() {
        val d = decideMapEmit(lastShown = marker(0.0), next = marker(0.00001), minMoveM = 5.0)
        assertEquals(MapEmit.None, d)
    }

    @Test fun `move above threshold re-shows`() {
        val d = decideMapEmit(lastShown = marker(0.0), next = marker(0.0001), minMoveM = 5.0)
        assertTrue(d is MapEmit.Show)
    }

    @Test fun `forceReassert re-shows a stationary marker`() {
        // Heartbeat: a ghost that has NOT moved (below threshold) is re-shown so a host map redraw
        // (zoom/pan) can't permanently drop it.
        val d = decideMapEmit(
            lastShown = marker(0.0),
            next = marker(0.00001),
            minMoveM = 5.0,
            forceReassert = true,
        )
        assertTrue(d is MapEmit.Show && (d as MapEmit.Show).marker.lng == 0.00001)
    }

    @Test fun `forceReassert with no next still hides`() {
        // A pending removal must win over the heartbeat re-assert (don't resurrect a gone ghost).
        assertEquals(
            MapEmit.Hide,
            decideMapEmit(lastShown = marker(0.0), next = null, minMoveM = 5.0, forceReassert = true),
        )
    }

    @Test fun `forceReassert with nothing shown emits nothing`() {
        assertEquals(
            MapEmit.None,
            decideMapEmit(lastShown = null, next = null, minMoveM = 5.0, forceReassert = true),
        )
    }
}
