package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RouteKeyTest {

    @Test fun `same name and same 100m bucket give the same key`() {
        assertEquals(routeKeyOf("Loop A", 12300.0), routeKeyOf("Loop A", 12340.0))
    }

    @Test fun `different name gives a different key`() {
        assertNotEquals(routeKeyOf("Loop A", 12300.0), routeKeyOf("Loop B", 12300.0))
    }

    @Test fun `a clearly different length gives a different key`() {
        assertNotEquals(routeKeyOf("Loop A", 12300.0), routeKeyOf("Loop A", 12500.0))
    }

    @Test fun `empty name falls back to route`() {
        assertEquals("route_5000", routeKeyOf("", 5000.0))
    }

    @Test fun `different non-ASCII names of same length do not collide`() {
        // Both sanitize to empty, so without the name-hash fallback they'd share "route_5000".
        assertNotEquals(routeKeyOf("山岳ルート", 5000.0), routeKeyOf("горный", 5000.0))
    }

    @Test fun `name is sanitized to a safe lowercase stem`() {
        assertEquals("caf-ride_1000", routeKeyOf("Café Ride!!", 1000.0))
    }

    @Test fun `length rounds to the nearest 100m`() {
        assertEquals("r_12300", routeKeyOf("r", 12345.0))
        assertEquals("r_12400", routeKeyOf("r", 12360.0))
    }
}
