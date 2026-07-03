package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.PolylinePath
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorSeederTest {
    // Straight route EAST along the equator. 25 m ≈ 0.000224595° of longitude at lat 0.
    private val degPerM = 1.0 / 111_320.0
    private fun route(lenM: Double) = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, lenM * degPerM)))

    /** A track riding EAST along the route: a point every 25 m, `secPerSeg` seconds per 25 m segment. */
    private fun eastTrack(id: String, epoch: Long, lenM: Double, secPerSeg: Double, latOffsetM: Double = 0.0): RecordedTrack {
        val n = (lenM / 25.0).toInt()
        val pts = (0..n).map { i ->
            TrackPointDto(lat = latOffsetM * degPerM, lng = i * 25.0 * degPerM, distanceM = i * 25.0, timeS = i * secPerSeg)
        }
        return RecordedTrack(id = id, startedAtEpoch = epoch, points = pts)
    }

    private fun seg(agg: PerRouteAggregate, pick: GhostPick) =
        agg.toLiveSegments(pick, minSegM = 0.0)

    @Test fun `two identical passes give count 2 and the ridden pace`() {
        val r = route(200.0)
        val a = eastTrack("a", 1L, 200.0, secPerSeg = 5.0) // 5 s / 25 m
        val b = eastTrack("b", 2L, 200.0, secPerSeg = 5.0)
        val agg = CorridorSeeder.seed("k", "K", r, listOf(a, b))
        val mid = agg.nodes[4]
        assertEquals(2, mid.count)
        assertEquals(5.0, mid.dtS, 1e-6)
        assertEquals(5.0, mid.lastDtS, 1e-6)
        assertEquals(5.0, mid.minDtS, 1e-6)
    }

    @Test fun `AVERAGE is the running mean of distinct passes (oldest-first)`() {
        val r = route(200.0)
        val a = eastTrack("a", 1L, 200.0, secPerSeg = 5.0)  // 0.2 s/m
        val b = eastTrack("b", 2L, 200.0, secPerSeg = 10.0) // 0.4 s/m
        val c = eastTrack("c", 3L, 200.0, secPerSeg = 15.0) // 0.6 s/m
        val agg = CorridorSeeder.seed("k", "K", r, listOf(a, b, c))
        val mid = agg.nodes[4]
        assertEquals(3, mid.count)
        assertEquals(10.0, mid.dtS, 1e-6)
        assertEquals(5.0, mid.minDtS, 1e-6)
        assertEquals(15.0, mid.lastDtS, 1e-6)
    }

    @Test fun `a crossing track (wrong bearing) does not contribute`() {
        val r = route(200.0)
        val east = eastTrack("e", 1L, 200.0, secPerSeg = 5.0)
        val midLng = 100.0 * degPerM
        val cross = RecordedTrack(
            "x", 2L,
            (0..4).map { i -> TrackPointDto(lat = (i - 2) * 10.0 * degPerM, lng = midLng, distanceM = i * 10.0, timeS = i * 2.0) },
        )
        val agg = CorridorSeeder.seed("k", "K", r, listOf(east, cross))
        assertEquals(1, agg.nodes[4].count)
    }

    @Test fun `a parallel track beyond the match radius does not contribute`() {
        val r = route(200.0)
        val on = eastTrack("on", 1L, 200.0, secPerSeg = 5.0, latOffsetM = 0.0)
        val parallel = eastTrack("par", 2L, 200.0, secPerSeg = 5.0, latOffsetM = 33.0)
        val agg = CorridorSeeder.seed("k", "K", r, listOf(on, parallel))
        assertEquals(1, agg.nodes[4].count)
    }

    @Test fun `a stop is dwell-clipped, not crawled`() {
        val r = route(200.0)
        val pts = (0..8).map { i ->
            val t = if (i <= 4) i * 5.0 else 4 * 5.0 + 100.0 + (i - 5) * 5.0
            TrackPointDto(lat = 0.0, lng = i * 25.0 * degPerM, distanceM = i * 25.0, timeS = t)
        }
        val agg = CorridorSeeder.seed("k", "K", r, listOf(RecordedTrack("s", 1L, pts)))
        assertEquals(50.0, agg.nodes[5].dtS, 1e-6)
    }

    @Test fun `a GPS spike segment contributes no bogus fast pace`() {
        val r = route(200.0)
        // Segment 100->125 m is a teleport: 25 m in 0.1 s = 250 m/s > AGG_MAX_SPEED_MS; everything else
        // is a genuine 5 s / 25 m. The spike segment must be rejected, so its ~0.01 s/25 m pace appears
        // on NO node; covered nodes show the real 5 s pace (a node by the spike is fed by the adjacent
        // valid segment, which is spatially correct — the rider rode that spot at a real pace).
        val pts = (0..8).map { i ->
            val t = if (i <= 4) i * 5.0 else (4 * 5.0 + 0.1) + (i - 5) * 5.0
            TrackPointDto(lat = 0.0, lng = i * 25.0 * degPerM, distanceM = i * 25.0, timeS = t)
        }
        val agg = CorridorSeeder.seed("k", "K", r, listOf(RecordedTrack("sp", 1L, pts)))
        for (node in agg.nodes) {
            if (node.count >= 1) assertEquals(5.0, node.dtS, 1e-6)
        }
    }

    @Test fun `one pass is raceable and AVERAGE falls back to LAST`() {
        val r = route(200.0)
        val agg = CorridorSeeder.seed("k", "K", r, listOf(eastTrack("a", 1L, 200.0, secPerSeg = 5.0)))
        assertTrue(seg(agg, GhostPick.AVERAGE).isNotEmpty())
        assertEquals(1, agg.nodes[4].count)
        assertEquals(agg.nodes[4].lastDtS, agg.nodes[4].dtS, 1e-9)
    }

    @Test fun `a route never ridden yields an empty grid`() {
        val r = route(200.0)
        val far = RecordedTrack("far", 1L, (0..8).map { i -> TrackPointDto(1.0, 1.0 + i * 25.0 * degPerM, i * 25.0, i * 5.0) })
        val agg = CorridorSeeder.seed("k", "K", r, listOf(far))
        assertTrue(agg.nodes.all { it.count == 0 })
    }

    @Test fun `a coarse-spaced track still covers the 25 m nodes (densify)`() {
        val r = route(240.0)
        // Points every 60 m (coarse / imported). A single END anchor per 60 m would leave the 25 m nodes
        // between them > 18 m from any anchor → uncovered; densify must cover them.
        val pts = (0..4).map { i -> TrackPointDto(0.0, i * 60.0 * degPerM, i * 60.0, i * 12.0) } // 5 s / 25 m
        val agg = CorridorSeeder.seed("k", "K", r, listOf(RecordedTrack("coarse", 1L, pts)))
        // Node 4 = 100 m sits between track points 60 m and 120 m — only densified anchors reach it.
        assertTrue("node 100 m must be covered by densify", agg.nodes[4].count >= 1)
        assertEquals(5.0, agg.nodes[4].dtS, 1e-6)
    }

    @Test fun `a long GPS-dropout gap is skipped, not bridged`() {
        val r = route(400.0)
        // Ride 0..50 m, a 300 m straight jump (dropout) to 350 m, then 350..400 m.
        val pts = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.0, 25.0 * degPerM, 25.0, 5.0),
            TrackPointDto(0.0, 50.0 * degPerM, 50.0, 10.0),
            TrackPointDto(0.0, 350.0 * degPerM, 350.0, 70.0), // 300 m chord jump (> DROPOUT_GAP_M) → skipped
            TrackPointDto(0.0, 375.0 * degPerM, 375.0, 75.0),
            TrackPointDto(0.0, 400.0 * degPerM, 400.0, 80.0),
        )
        val agg = CorridorSeeder.seed("k", "K", r, listOf(RecordedTrack("gap", 1L, pts)))
        assertEquals(0, agg.nodes[8].count)  // 200 m, inside the skipped gap
        assertTrue(agg.nodes[1].count >= 1)  // 25 m, first ridden stretch
        assertTrue(agg.nodes[15].count >= 1) // 375 m, last ridden stretch
    }

    @Test fun `a curve-collapse segment (odometer much greater than chord) anchors the END only`() {
        val r = route(200.0)
        // Odometer 1500 m but straight chord ~150 m (rider looped away and returned near 150 m). The
        // interpolated chord points would be mis-located, so only the real END (150 m) is anchored — the
        // chord interior (75 m) must NOT be painted.
        val pts = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.0, 150.0 * degPerM, 1500.0, 300.0),
        )
        val agg = CorridorSeeder.seed("k", "K", r, listOf(RecordedTrack("curve", 1L, pts)))
        assertEquals(0, agg.nodes[3].count)  // 75 m (chord interior) NOT fabricated
        assertTrue(agg.nodes[6].count >= 1)  // 150 m (real END) covered
    }

    @Test fun `seed leaves seededTrackIds to the caller`() {
        val r = route(200.0)
        val agg = CorridorSeeder.seed("k", "K", r, listOf(eastTrack("a", 1L, 200.0, 5.0), eastTrack("b", 2L, 200.0, 5.0)))
        assertTrue(agg.seededTrackIds.isEmpty())
    }
}
