package com.enderthor.kghost.map

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.PolylinePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GhostMapPresenterTest {
    private val east = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))

    @Test fun `projects the ghost route-distance to a marker`() {
        val m = GhostMapPresenter.marker(ghostRouteDistM = east.totalM / 2.0, route = east, fresh = true)
        assertNotNull(m)
        assertEquals(0.5, m!!.lng, 1e-6)
        assertEquals(0.0, m.lat, 1e-9)
        assertEquals(90.0f, m.bearingDeg, 0.5f)
    }

    @Test fun `stale returns null`() {
        assertNull(GhostMapPresenter.marker(east.totalM / 2.0, east, fresh = false))
    }

    @Test fun `non-finite distance returns null`() {
        assertNull(GhostMapPresenter.marker(Double.NaN, east, fresh = true))
    }

    @Test fun `distance past the route end clamps to the last point`() {
        val m = GhostMapPresenter.marker(east.totalM * 10, east, fresh = true)
        assertNotNull(m)
        assertEquals(1.0, m!!.lng, 1e-9)
    }
}
