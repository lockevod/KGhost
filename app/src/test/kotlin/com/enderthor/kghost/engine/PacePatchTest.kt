package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacePatchTest {
    private val degPerM = 1.0 / 111_320.0
    private fun eastTrack(id: String, epoch: Long, lenM: Double, secPerSeg: Double) =
        RecordedTrack(id, epoch, (0..(lenM / 25.0).toInt()).map { i ->
            TrackPointDto(0.0, i * 25.0 * degPerM, i * 25.0, i * secPerSeg)
        })

    @Test fun `lookup returns the pick pace where history exists, null elsewhere`() {
        val patch = PacePatch.build(listOf(eastTrack("a", 1L, 200.0, 5.0), eastTrack("b", 2L, 200.0, 5.0)))
        assertEquals(0.2, patch.pace(0.0, 100.0 * degPerM, 90.0, GhostPick.AVERAGE)!!, 1e-6) // 5 s / 25 m
        assertNull(patch.pace(1.0, 1.0, 90.0, GhostPick.AVERAGE))                              // far away
        assertNull(patch.pace(0.0, 100.0 * degPerM, 180.0, GhostPick.AVERAGE))                 // wrong heading
    }

    @Test fun `AVERAGE falls back to LAST on a single pass`() {
        val patch = PacePatch.build(listOf(eastTrack("a", 1L, 200.0, 5.0)))
        val p = patch.pace(0.0, 100.0 * degPerM, 90.0, GhostPick.AVERAGE)
        assertNotNull(p); assertEquals(0.2, p!!, 1e-6)
    }
}
