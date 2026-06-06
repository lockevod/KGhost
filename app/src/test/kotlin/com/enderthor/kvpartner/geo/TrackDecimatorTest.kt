package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDecimatorTest {
    private val d = TrackDecimator(minSpacingM = 20.0)

    @Test fun `keeps the first sample`() {
        assertTrue(d.shouldKeep(lat = 0.0, lng = 0.0, distanceM = 0.0))
    }

    @Test fun `drops samples closer than the spacing and keeps once spacing exceeded`() {
        d.shouldKeep(0.0, 0.0, 0.0)                 // kept (first)
        assertTrue(!d.shouldKeep(0.0, 0.0, 5.0))    // 5 m < 20 m → drop
        assertTrue(!d.shouldKeep(0.0, 0.0, 19.0))   // still < 20 m → drop
        assertTrue(d.shouldKeep(0.0, 0.0, 25.0))    // 25 m ≥ 20 m from last kept → keep
        assertTrue(!d.shouldKeep(0.0, 0.0, 30.0))   // 5 m from last kept → drop
    }
}
