package com.enderthor.kghost.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGenGateTest {
    @Test fun `same generation never resets twice`() {
        val g = RouteGenGate()
        assertTrue(g.needsReset(g.current())) // first route of the process: harmless reset
        assertFalse(g.needsReset(g.current()))
        assertFalse(g.needsReset(g.current()))
    }

    @Test fun `obsolete tick then new route`() {
        val g = RouteGenGate()
        val old = g.current()
        assertTrue(g.needsReset(old))
        g.onClear()
        assertFalse(g.needsReset(old)) // in-flight tick still holding the old mode
        val fresh = g.current()
        assertTrue(g.needsReset(fresh))
        assertFalse(g.needsReset(fresh))
        assertFalse(g.needsReset(old))
    }

    @Test fun `a repick copy keeps the generation`() {
        val g = RouteGenGate()
        val stamp = g.current()
        assertTrue(g.needsReset(stamp))
        // withPick copies the mode, so it carries the same stamp: not a clear, no reset.
        assertFalse(g.needsReset(stamp))
    }

    @Test fun `identical polyline after a clear resets`() {
        val g = RouteGenGate()
        assertTrue(g.needsReset(g.current()))
        g.onClear() // reload of the same polyline goes through a clear
        assertTrue(g.needsReset(g.current()))
    }
}
