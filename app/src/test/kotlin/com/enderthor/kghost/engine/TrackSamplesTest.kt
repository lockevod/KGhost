package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSamplesTest {
    private val degPerM = 1.0 / 111_320.0
    private fun track(vararg p: Triple<Double, Double, Double>) = // (lngAsMetres, distanceM, timeS)
        RecordedTrack("t", 1L, p.map { TrackPointDto(0.0, it.first * degPerM, it.second, it.third) })

    @Test fun `emits one sample per densified sub-anchor carrying segment pace and bearing`() {
        var n = 0; var lastTpm = 0.0; var lastBrg = -1.0
        TrackSamples.forEach(track(Triple(0.0, 0.0, 0.0), Triple(50.0, 50.0, 10.0))) { s ->
            n++; lastTpm = s.timePerM; lastBrg = s.bearingDeg
        }
        assertEquals(5, n)               // ceil(50/12)
        assertEquals(0.2, lastTpm, 1e-9) // 10 s / 50 m
        assertTrue(kotlin.math.abs(lastBrg - 90.0) < 1.0) // east
    }

    @Test fun `skips a GPS-dropout gap (chord over 200 m)`() {
        var n = 0
        TrackSamples.forEach(track(Triple(0.0, 0.0, 0.0), Triple(300.0, 300.0, 60.0))) { n++ }
        assertEquals(0, n)
    }

    @Test fun `END-only when odometer far exceeds chord (curve collapse)`() {
        var n = 0
        TrackSamples.forEach(track(Triple(0.0, 0.0, 0.0), Triple(150.0, 1500.0, 300.0))) { n++ }
        assertEquals(1, n)
    }
}
