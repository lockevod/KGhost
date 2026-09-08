package com.enderthor.kghost.geo

import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDecimatorTest {
    private val d = TrackDecimator(minSpacingM = 20.0)

    @Test fun `keeps the first sample`() {
        assertTrue(d.shouldKeep(distanceM = 0.0))
    }

    @Test fun `drops samples closer than the spacing and keeps once spacing exceeded`() {
        d.shouldKeep(0.0)                 // kept (first)
        assertTrue(!d.shouldKeep(5.0))    // 5 m < 20 m → drop
        assertTrue(!d.shouldKeep(19.0))   // still < 20 m → drop
        assertTrue(d.shouldKeep(25.0))    // 25 m ≥ 20 m from last kept → keep
        assertTrue(!d.shouldKeep(30.0))   // 5 m from last kept → drop
    }
}
