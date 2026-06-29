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

    @Test fun `prefers the rider's own cell over a busier parallel road one cell away`() {
        // Rider's road A at lat 0 (1 pass, 0.25 s/m). Parallel road B ~30 m north = adjacent lat cell
        // (3 passes, 0.10 s/m), same east bearing. Max-count would wrongly return B's 0.10; exact-cell
        // preference must return A's 0.25.
        fun east(id: String, lat0: Double, secPerSeg: Double) =
            RecordedTrack(id, id.hashCode().toLong(), (0..8).map { i -> TrackPointDto(lat0 * degPerM, i * 25.0 * degPerM, i * 25.0, i * secPerSeg) })
        val patch = PacePatch.build(listOf(
            east("a", 0.0, 6.25),                    // 0.25 s/m on the rider's road
            east("b1", 30.0, 2.5), east("b2", 30.0, 2.5), east("b3", 30.0, 2.5), // 0.10 s/m parallel, busier
        ))
        assertEquals(0.25, patch.pace(0.0, 100.0 * degPerM, 90.0, GhostPick.AVERAGE)!!, 1e-6)
    }

    @Test fun `rejects a neighbour-bin road beyond the real 45 deg bearing tolerance`() {
        // A road heading ~70 deg (bin 1). A rider whose own cell+bin (bin 0, heading ~1 deg) is empty would,
        // under the ±1-bin window alone, match the 70 deg road; the real-bearing filter (|70-1|=69 > 45) rejects it.
        val n = 8.5; val e = 23.5 // metres north/east per step → bearing atan2(e,n) ≈ 70 deg
        val ne = RecordedTrack("ne", 1L, (0..8).map { i -> TrackPointDto(i * n * degPerM, i * e * degPerM, i * 25.0, i * 5.0) })
        val patch = PacePatch.build(listOf(ne))
        val qLat = 4 * n * degPerM; val qLng = 4 * e * degPerM
        assertNotNull(patch.pace(qLat, qLng, 70.0, GhostPick.AVERAGE)) // matching heading → hit
        assertNull(patch.pace(qLat, qLng, 1.0, GhostPick.AVERAGE))     // 1 deg rider, 70 deg road → filtered out
    }

    @Test fun `a non-finite heading yields no pace (VP-fill), not max-count`() {
        val patch = PacePatch.build(listOf(
            RecordedTrack("a", 1L, (0..8).map { i -> TrackPointDto(0.0, i * 25.0 * degPerM, i * 25.0, i * 5.0) })))
        assertNull(patch.pace(0.0, 100.0 * degPerM, Double.NaN, GhostPick.AVERAGE))
    }
}
