package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.engine.GhostPick
import com.enderthor.kvpartner.engine.LiveSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Differential test for the SECONDARY optimization: bounding [SegmentMatcher]'s slice-extraction
 * seed loop to CAN-START points only must produce the EXACT SAME `match` output as the original
 * ALL-SEEDS O(trackPts²) loop.
 *
 * The contract that must be preserved is the matcher's OUTPUT: the `List<LiveSegment>` — each
 * segment's `routeStartM`, `routeEndM`, `ghostLabel`, and the full ghost curve (sampled at many
 * distances). This test runs `match` twice (ALL_SEEDS vs CAN_START) on the SAME inputs and asserts
 * the two outputs are identical, across:
 *   - the existing hand-built fixtures (single pass, blip, multi-lap, out-and-back), and
 *   - many random routes × random tracks (FIXED `Random(42)`), including out-and-back routes.
 *
 * It ALSO documents (via [`corridor-entry bound is NOT result-identical (deferred)`]) that the
 * stronger CORRIDOR_ENTRY bound the task contemplated is unsound — it changes the output on
 * out-and-back fixtures — which is why CAN_START (proven identical) is the production default and
 * CORRIDOR_ENTRY is deferred.
 */
class SegmentSliceSeedDiffTest {

    @After fun restore() {
        SegmentMatcher.seedStrategy = SegmentMatcher.SeedStrategy.CAN_START
    }

    private val params = SegmentMatcher.Params(toleranceM = 25.0, minSegmentM = 300.0, mergeGapM = 80.0)

    private fun matchWith(
        strategy: SegmentMatcher.SeedStrategy,
        route: PolylinePath,
        tracks: List<RecordedTrack>,
        pick: GhostPick,
        p: SegmentMatcher.Params,
    ): List<LiveSegment> {
        SegmentMatcher.seedStrategy = strategy
        return SegmentMatcher.match(route, tracks, pick, p)
    }

    private fun assertSameOutput(
        route: PolylinePath,
        tracks: List<RecordedTrack>,
        pick: GhostPick,
        p: SegmentMatcher.Params,
        label: String,
    ) {
        val all = matchWith(SegmentMatcher.SeedStrategy.ALL_SEEDS, route, tracks, pick, p)
        val ce = matchWith(SegmentMatcher.SeedStrategy.CAN_START, route, tracks, pick, p)
        assertEquals("$label: segment count", all.size, ce.size)
        for (i in all.indices) {
            val a = all[i]; val c = ce[i]
            assertEquals("$label[$i] routeStartM", a.routeStartM, c.routeStartM, 1e-9)
            assertEquals("$label[$i] routeEndM", a.routeEndM, c.routeEndM, 1e-9)
            assertEquals("$label[$i] ghostLabel", a.ghostLabel, c.ghostLabel)
            assertEquals("$label[$i] ghost totalDistanceM", a.ghost.totalDistanceM, c.ghost.totalDistanceM, 1e-9)
            assertEquals("$label[$i] ghost totalTimeS", a.ghost.totalTimeS, c.ghost.totalTimeS, 1e-9)
            // Sample the ghost curve densely: identical curves => identical timeAt at every distance.
            val span = a.ghost.totalDistanceM
            val steps = 50
            for (k in 0..steps) {
                val d = span * k / steps
                assertEquals("$label[$i] ghost.timeAt($d)", a.ghost.timeAt(d), c.ghost.timeAt(d), 1e-9)
            }
        }
    }

    // ---- Hand-built fixtures mirroring the existing SegmentMatcherTest scenarios. ----

    private val straightRoute = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.018)))
    private fun pt(lng: Double, distanceM: Double, t: Double) =
        TrackPoint(0.0, lng, distanceM, t)

    @Test fun `single clean pass identical under both strategies`() {
        val track = RecordedTrack(
            "t1", 1_000L,
            (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0).toDto() },
        )
        assertSameOutput(straightRoute, listOf(track), GhostPick.BEST, params, "single")
    }

    @Test fun `mid-segment blip identical under both strategies`() {
        val pts = (0..18).map { i ->
            val lng = 0.004 + i * 0.0005
            if (i == 9) TrackPoint(0.0015, 0.0005, i * 55.0, i * 11.0)
            else pt(lng, i * 55.0, i * 11.0)
        }
        val track = RecordedTrack("blip", 1_000L, pts.map { it.toDto() })
        assertSameOutput(straightRoute, listOf(track), GhostPick.BEST, params, "blip")
    }

    @Test fun `multi-lap same direction identical under both strategies`() {
        val lap1 = (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0) }
        val last = lap1.last()
        val lap2 = (1..18).map { j -> pt(0.004 + j * 0.0005, last.distanceM + j * 55.0, last.timeS + j * 11.0) }
        val track = RecordedTrack("laps", 1_000L, (lap1 + lap2).map { it.toDto() })
        assertSameOutput(straightRoute, listOf(track), GhostPick.BEST, params, "laps")
    }

    @Test fun `out-and-back identical under both strategies`() {
        val outLngs = generateSequence(0.0) { it + 0.0006 }.takeWhile { it < 0.018 }.toList() + 0.018
        val backLngs = outLngs.reversed().drop(1)
        val outAndBack = PolylinePath((outLngs + backLngs).map { LatLng(0.0, it) })
        val outPts = (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0) }
        val lastOut = outPts.last()
        val returnPts = (1..18).map { j ->
            pt(0.013 - j * 0.0005, lastOut.distanceM + j * 55.0, lastOut.timeS + j * 11.0)
        }
        val track = RecordedTrack("oab", 1_000L, (outPts + returnPts).map { it.toDto() })
        assertSameOutput(outAndBack, listOf(track), GhostPick.BEST, params, "oab")
    }

    @Test fun `two overlapping tracks identical under both strategies for BEST and LAST`() {
        val trackA = RecordedTrack("A", 1_000L, (0..10).map { i -> pt(i * 0.0005, i * 55.0, i * 15.0).toDto() })
        val trackB = RecordedTrack("B", 2_000L, (0..10).map { i -> pt(0.0036 + i * 0.0005, i * 55.0, i * 10.0).toDto() })
        assertSameOutput(straightRoute, listOf(trackA, trackB), GhostPick.BEST, params, "ABbest")
        assertSameOutput(straightRoute, listOf(trackA, trackB), GhostPick.LAST, params, "ABlast")
    }

    // ---- Random fixtures (straight + out-and-back routes). ----

    private fun randomRoute(rnd: Random, n: Int, outAndBack: Boolean): PolylinePath {
        var lat = 40.0; var lng = -3.0
        val fwd = ArrayList<LatLng>()
        fwd += LatLng(lat, lng)
        repeat(n - 1) {
            lat += rnd.nextDouble(-0.00005, 0.0002)
            lng += rnd.nextDouble(-0.00005, 0.0002)
            fwd += LatLng(lat, lng)
        }
        return if (outAndBack) PolylinePath(fwd + fwd.reversed().drop(1)) else PolylinePath(fwd)
    }

    /** A track that rides a contiguous sub-range of the route's forward points, with GPS jitter. */
    private fun randomTrackOnRoute(rnd: Random, routeFwd: List<LatLng>, lapTwice: Boolean): RecordedTrack {
        val start = rnd.nextInt(0, (routeFwd.size / 2).coerceAtLeast(1))
        val len = rnd.nextInt(20, (routeFwd.size - start).coerceAtLeast(21))
        val pts = ArrayList<TrackPointDto>()
        var dist = 0.0; var t = 0.0
        val laps = if (lapTwice) 2 else 1
        repeat(laps) {
            for (k in 0 until len) {
                val rp = routeFwd[(start + k).coerceAtMost(routeFwd.size - 1)]
                dist += 20.0; t += rnd.nextDouble(3.0, 6.0)
                pts += TrackPointDto(
                    rp.lat + rnd.nextDouble(-0.00007, 0.00007),
                    rp.lng + rnd.nextDouble(-0.00007, 0.00007),
                    dist, t,
                )
            }
        }
        return RecordedTrack("r${rnd.nextInt()}", 1_000L + rnd.nextInt(0, 100000), pts)
    }

    @Test fun `random straight routes and tracks identical under both strategies`() {
        val rnd = Random(42)
        repeat(40) { iter ->
            val n = rnd.nextInt(40, 200)
            val route = randomRoute(rnd, n, outAndBack = false)
            val fwd = route.points
            val tracks = (0 until rnd.nextInt(1, 5)).map {
                randomTrackOnRoute(rnd, fwd, lapTwice = rnd.nextBoolean())
            }
            val pick = if (rnd.nextBoolean()) GhostPick.BEST else GhostPick.LAST
            assertSameOutput(route, tracks, pick, params, "rndStraight#$iter")
        }
    }

    @Test fun `random out-and-back routes and tracks identical under both strategies`() {
        val rnd = Random(42)
        repeat(40) { iter ->
            val n = rnd.nextInt(40, 150)
            val route = randomRoute(rnd, n, outAndBack = true)
            // Use only the forward half's points as the rideable corridor for the track.
            val fwd = route.points.subList(0, n)
            val tracks = (0 until rnd.nextInt(1, 4)).map {
                randomTrackOnRoute(rnd, fwd, lapTwice = rnd.nextBoolean())
            }
            val pick = if (rnd.nextBoolean()) GhostPick.BEST else GhostPick.LAST
            assertSameOutput(route, tracks, pick, params, "rndOAB#$iter")
        }
    }

    /**
     * Proof that the STRONGER corridor-entry bound the task contemplated is NOT result-identical, so
     * it is correctly DEFERRED in favour of CAN_START. On an out-and-back route ridden twice the
     * rider never leaves the corridor between the two passes, so corridor-entry keeps only ONE seed
     * and can select a different winning chain than ALL_SEEDS — changing the emitted ghost. This test
     * asserts that such a difference exists (i.e. corridor-entry would be unsound), justifying the
     * deferral. If a future change ever made corridor-entry safe this test would fail and prompt a
     * re-evaluation.
     */
    @Test fun `corridor-entry bound is NOT result-identical (deferred)`() {
        val outLngs = generateSequence(0.0) { it + 0.0006 }.takeWhile { it < 0.018 }.toList() + 0.018
        val backLngs = outLngs.reversed().drop(1)
        val outAndBack = PolylinePath((outLngs + backLngs).map { LatLng(0.0, it) })
        val outPts = (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0) }
        val lastOut = outPts.last()
        val returnPts = (1..18).map { j ->
            pt(0.013 - j * 0.0005, lastOut.distanceM + j * 55.0, lastOut.timeS + j * 11.0)
        }
        val track = RecordedTrack("oab", 1_000L, (outPts + returnPts).map { it.toDto() })

        val all = matchWith(SegmentMatcher.SeedStrategy.ALL_SEEDS, outAndBack, listOf(track), GhostPick.BEST, params)
        val ce = matchWith(SegmentMatcher.SeedStrategy.CORRIDOR_ENTRY, outAndBack, listOf(track), GhostPick.BEST, params)

        // The outputs DIFFER (segment count and/or a ghost curve), proving corridor-entry is unsound.
        val differs = all.size != ce.size || all.indices.any { i ->
            val a = all[i]; val c = ce[i]
            a.routeStartM != c.routeStartM || a.routeEndM != c.routeEndM ||
                a.ghost.totalDistanceM != c.ghost.totalDistanceM || a.ghost.totalTimeS != c.ghost.totalTimeS
        }
        org.junit.Assert.assertTrue(
            "corridor-entry unexpectedly matched ALL_SEEDS here; re-evaluate whether it can be promoted",
            differs,
        )
    }
}
