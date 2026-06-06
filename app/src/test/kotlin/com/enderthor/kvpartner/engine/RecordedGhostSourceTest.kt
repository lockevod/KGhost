package com.enderthor.kvpartner.engine

import com.enderthor.kvpartner.geo.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedGhostSourceTest {
    // A track slice from 1000 m to 1500 m over 100 s → normalize to (0..500 m, 0..100 s).
    private val slice = listOf(
        TrackPoint(0.0, 0.0, 1000.0, 200.0),
        TrackPoint(0.0, 0.0, 1250.0, 250.0),
        TrackPoint(0.0, 0.0, 1500.0, 300.0),
    )

    @Test fun `builds a segment-relative curve from a track slice`() {
        val src = RecordedGhostSource.fromTrackSlice(slice, label = "PR")
        val c = src.curve()
        assertEquals(0.0, c.timeAt(0.0), 1e-6)        // segment start → t=0
        assertEquals(50.0, c.timeAt(250.0), 1e-6)     // 250 m in → 50 s
        assertEquals(100.0, c.timeAt(500.0), 1e-6)    // end → 100 s
        assertEquals(500.0, c.totalDistanceM, 1e-6)
    }

    @Test fun `label is exposed`() {
        assertEquals("PR", RecordedGhostSource.fromTrackSlice(slice, "PR").label)
    }
}
