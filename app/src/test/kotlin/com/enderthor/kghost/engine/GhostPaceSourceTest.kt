package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhostPaceSourceTest {
    @Test fun `linear curve at 5 metres per second`() {
        val curve = GhostPaceSource(5.0).curve()
        assertEquals(200.0, curve.timeAt(1000.0), 1e-6)     // 1000 m / 5 = 200 s
        assertEquals(2000.0, curve.distanceAt(400.0), 1e-6) // 400 s * 5 = 2000 m
    }

    @Test fun `curve covers long distances`() {
        val curve = GhostPaceSource(10.0).curve()
        assertEquals(50_000.0, curve.distanceAt(5000.0), 1.0) // 5000 s * 10 = 50 km within range
    }

    @Test fun `rejects non-positive speed`() {
        assertThrows(IllegalArgumentException::class.java) { GhostPaceSource(0.0) }
        assertThrows(IllegalArgumentException::class.java) { GhostPaceSource(-3.0) }
    }
}
