package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.GhostPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMatcherTest {
    // Route: 2 km straight east along the equator (0,0)→(0,~0.018).
    private val route = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.018)))
    private fun pt(lng: Double, distanceM: Double, t: Double) =
        TrackPoint(0.0, lng, distanceM, t)

    private val params = SegmentMatcher.Params(toleranceM = 25.0, minSegmentM = 300.0, mergeGapM = 80.0)

    @Test fun `production default toleranceM is pinned`() {
        // Guards against an accidental future edit of the production default.
        assertEquals(35.0, SegmentMatcher.Params().toleranceM, 0.0)
    }

    @Test fun `finds the overlapping middle stretch as one segment`() {
        // Track rides exactly along the route's middle ~1 km (lng 0.004..0.013), at 5 m/s.
        val track = RecordedTrack(
            id = "t1", startedAtEpoch = 1_000L,
            points = (0..18).map { i ->
                val lng = 0.004 + i * 0.0005
                pt(lng, distanceM = i * 55.0, t = i * 11.0).toDto()
            },
        )
        val segs = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        assertEquals(1, segs.size)
        val s = segs.first()
        assertTrue(s.routeStartM > 300.0 && s.routeEndM < route.totalM)
        assertTrue(s.routeEndM - s.routeStartM >= params.minSegmentM)
        // Ghost curve is segment-relative and monotonic.
        assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
    }

    @Test fun `discards overlaps shorter than minSegmentM`() {
        val track = RecordedTrack(
            "short", 1_000L,
            (0..3).map { i -> pt(0.006 + i * 0.0003, i * 33.0, i * 6.0).toDto() }, // ~100 m overlap
        )
        assertTrue(SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params).isEmpty())
    }

    @Test fun `coveredRanges skips the slice for fully-covered intervals`() {
        val track = RecordedTrack(
            id = "t1", startedAtEpoch = 1_000L,
            points = (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0).toDto() },
        )
        // Baseline: one segment over the middle stretch.
        val base = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        assertEquals(1, base.size)
        val seg = base.first()

        // Whole route covered → the interval is fully inside → skipped → no segments produced.
        val whole = SegmentMatcher.match(
            route, listOf(track), GhostPick.BEST, params, coveredRanges = listOf(0.0 to route.totalM),
        )
        assertTrue("whole-route coverage skips everything", whole.isEmpty())

        // A covered range that fully contains the interval → skipped.
        val containing = SegmentMatcher.match(
            route, listOf(track), GhostPick.BEST, params,
            coveredRanges = listOf((seg.routeStartM - 1.0) to (seg.routeEndM + 1.0)),
        )
        assertTrue("covering the interval skips it", containing.isEmpty())

        // A covered range elsewhere (does not contain the interval) → unchanged.
        val elsewhere = SegmentMatcher.match(
            route, listOf(track), GhostPick.BEST, params, coveredRanges = listOf(0.0 to 1.0),
        )
        assertEquals("unrelated coverage leaves the segment", 1, elsewhere.size)
        assertEquals(seg.routeStartM, elsewhere.first().routeStartM, 1e-9)
        assertEquals(seg.routeEndM, elsewhere.first().routeEndM, 1e-9)
    }

    @Test fun `coveredRanges keeps a beyond-coverage track's tail (coverage-monotone across grouping)`() {
        // A: fully inside the covered range, FAST — without the skip it would win the merged group and
        // then be trimmed 100% by overlay, losing B's tail. B: overlaps A but extends BEYOND the
        // covered range, slower. With the skip, A is dropped → B is raced → its beyond-coverage tail
        // survives (the coverage-monotone property the adversarial exhaustive search confirmed).
        val a = RecordedTrack("A", 2_000L, (0..22).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 6.0).toDto() })
        val b = RecordedTrack("B", 1_000L, (0..22).map { i -> pt(0.005 + i * 0.0005, i * 55.0, i * 12.0).toDto() })
        val covered = listOf(0.0 to 1675.0) // contains A's stretch, not B's far tail

        val cov = SegmentMatcher.match(route, listOf(a, b), GhostPick.BEST, params, coveredRanges = covered)
        assertTrue("a segment survives", cov.isNotEmpty())
        assertTrue(
            "the beyond-coverage tail is still raced (A skipped, B contributes its tail)",
            cov.maxOf { it.routeEndM } > 1675.0,
        )
    }

    @Test fun `BEST picks the faster of two tracks over the same stretch`() {
        fun track(id: String, secPerStep: Double) = RecordedTrack(
            id, 1_000L, (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * secPerStep).toDto() })
        val slow = track("slow", 12.0); val fast = track("fast", 8.0)
        val segs = SegmentMatcher.match(route, listOf(slow, fast), GhostPick.BEST, params)
        assertEquals(1, segs.size)
        // The chosen ghost's total time over the segment equals the fast track's.
        assertTrue(segs.first().ghost.totalTimeS < 18 * 10.0)
    }

    /**
     * Regression for the union-span bug: two tracks covering partially-overlapping but DIFFERENT
     * route ranges (A ≈ [0,600 m], B ≈ [400,1000 m]) were previously grouped into a single segment
     * whose [routeStartM,routeEndM] was the greedy union [0,1000 m].  The chosen ghost (say B)
     * only covered ~600 m, so GhostCurve.timeAt clamped and the gap reading froze for the last
     * ~400 m of the union span.  After the fix the emitted segment's span must match the winner's
     * own ghost coverage (within 15 %).
     */
    @Test fun `ghost coverage matches declared segment span after overlapping-range resolution`() {
        // Route is 2 km east (lng 0..0.018). Each degree of longitude ≈ 111 320 m at equator,
        // so 0.018° ≈ 2004 m.  One sample step of 0.0005° ≈ 55.7 m along the route.

        // Track A: covers roughly route [0, ~600 m] — lng 0.000..0.0054 (10 steps × 55 m = 550 m track).
        // Made deliberately SLOWER so BEST will prefer track B.
        val trackA = RecordedTrack(
            id = "A", startedAtEpoch = 1_000L,
            points = (0..10).map { i ->
                pt(lng = i * 0.0005, distanceM = i * 55.0, t = i * 15.0).toDto()
            },
        )

        // Track B: covers roughly route [~400 m, ~1000 m] — lng 0.0036..0.0090
        // (shifted by ~7 steps = ~385 m; 11 steps × 55 m = 605 m track). Made FASTER.
        val trackB = RecordedTrack(
            id = "B", startedAtEpoch = 2_000L,
            points = (0..10).map { i ->
                pt(lng = 0.0036 + i * 0.0005, distanceM = i * 55.0, t = i * 10.0).toDto()
            },
        )

        val segs = SegmentMatcher.match(route, listOf(trackA, trackB), GhostPick.BEST, params)

        // There must be at least one segment (the two tracks overlap, so they form a group).
        assertTrue("Expected at least one segment, got ${segs.size}", segs.size >= 1)

        // Find the segment that the winner (B, faster) produced.  On the fixed code there is
        // exactly one segment whose span matches B's coverage.  On the old union-span code there
        // would be a single segment with routeEndM ≈ 1000 m but ghost.totalDistanceM ≈ 550 m —
        // a ~80 % mismatch that this assertion catches.
        val winning = segs.first()
        val spanM = winning.routeEndM - winning.routeStartM
        val ghostM = winning.ghost.totalDistanceM

        // Ghost coverage must be within 15 % of the declared segment span.
        val ratio = ghostM / spanM
        assertTrue(
            "ghost.totalDistanceM ($ghostM) should be within 15% of span ($spanM), ratio=$ratio",
            ratio in 0.85..1.15,
        )
    }

    /**
     * Out-and-back regression. The ROUTE goes east A→B then back west B→A on the same road, and the
     * TRACK rides the shared middle stretch TWICE (outbound then return).
     *
     * On the OLD code [extractTrackSlice] projected every track point by GLOBAL nearest and bucketed
     * by `[startM, endM]`. Outbound track points are geometrically valid on BOTH the outbound and
     * the return vertex range of the route, so they smeared across both intervals: the per-interval
     * slice became non-monotonic in route-distance, the dedup dropped points, and the ghost shrank
     * (frozen tail). The pass-segregated extraction walks the track in recorded order and keeps the
     * longest contiguous monotonic pass, so the ghost again covers the full segment width.
     */
    /**
     * F1 regression: a CLEAN single-pass overlap that is split into two contiguous runs by ONE
     * disruptive point (a GPS spike / roundabout / brief off-route blip) in the middle.
     *
     * The coverage interval stays ONE wide span because neighbouring track points still cover the
     * route around the blip (so the coverage gap is < mergeGapM and the interval is not split). But
     * the blip breaks the monotonic walk inside [extractTrackSlice] into two runs. The longest-run
     * code kept only the longer half, so ghost.totalDistanceM ≈ half the span and the frozen tail
     * returned for the uncovered remainder. The stitch fix keeps the monotonic SUBSEQUENCE across
     * the whole interval (skipping the blip but not terminating the run), so the ghost again covers
     * the full span.
     */
    @Test fun `single-pass overlap split by a mid-segment blip still yields full-span ghost`() {
        // Track rides the middle ~1 km east (lng 0.004..0.013), 19 points at 5 m/s, EXCEPT point #9
        // which spikes off-route (a GPS glitch: it jumps back west AND ~167 m north). The single
        // spike does NOT uncover the surrounding route samples, so the coverage interval stays ONE
        // wide span (~1025 m); but it breaks the forward-biased monotonic walk into two contiguous
        // runs. Distances/times stay cumulative (a real ride never rewinds the odometer).
        val pts = (0..18).map { i ->
            val lng = 0.004 + i * 0.0005
            if (i == 9) {
                // Off-route spike: west of the run start (lng 0.0005) and ~167 m north of the route.
                TrackPoint(
                    lat = 0.0015, lng = 0.0005, distanceM = i * 55.0, timeS = i * 11.0,
                )
            } else {
                pt(lng, distanceM = i * 55.0, t = i * 11.0)
            }
        }
        val track = RecordedTrack(
            id = "blip", startedAtEpoch = 1_000L,
            points = pts.map { it.toDto() },
        )

        val segs = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        assertTrue("expected at least one segment, got ${segs.size}", segs.isNotEmpty())

        val s = segs.first()
        val span = s.routeEndM - s.routeStartM
        val ghostM = s.ghost.totalDistanceM
        val ratio = ghostM / span
        // On the longest-run code this is ~0.5 (the blip halves the kept run). After the stitch fix
        // the monotonic subsequence spans the whole interval, so the ghost is within ~15 %.
        assertTrue(
            "ghost.totalDistanceM ($ghostM) should be within 15% of span ($span), ratio=$ratio",
            ratio in 0.85..1.15,
        )
        assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
    }

    /**
     * BUG 1 regression: multi-lap SAME-direction smear. On a route where a stretch is ridden two or
     * more times in the SAME direction (criterium / repeated loop), the coverage scan yields ONE
     * interval. On the buggy code the lap-2 points (same road, perpDist≈0, odometer jumped a full
     * lap so strictly greater, route "not backward" within the back window) appended at the chain
     * head with a FROZEN route position, so ghost.totalDistanceM became ~2× the segment span. The
     * strict route-forward rule plus the odometer-vs-route-span backstop keep the emitted ghost to
     * ONE lap's span.
     */
    @Test fun `multi-lap same-direction stretch yields one lap span not multiple`() {
        // Track rides the middle ~1 km east (lng 0.004..0.013) at 5 m/s, then rides the SAME stretch
        // again in the SAME direction (lap 2). Distances and times are cumulative across both laps
        // (a real ride never rewinds the odometer): lap 2 picks up where lap 1 left off.
        val lap1 = (0..18).map { i ->
            val lng = 0.004 + i * 0.0005
            pt(lng, distanceM = i * 55.0, t = i * 11.0)
        }
        val lastLap1 = lap1.last()
        // Lap 2: same longitudes, same direction; odometer + time continue from lap 1's end.
        val lap2 = (1..18).map { j ->
            val lng = 0.004 + j * 0.0005
            pt(lng, distanceM = lastLap1.distanceM + j * 55.0, t = lastLap1.timeS + j * 11.0)
        }
        val track = RecordedTrack(
            id = "laps", startedAtEpoch = 1_000L,
            points = (lap1 + lap2).map { it.toDto() },
        )

        val segs = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        // The stretch is real, so we expect a segment (the backstop only rejects smeared chains).
        assertTrue("expected at least one segment, got ${segs.size}", segs.isNotEmpty())

        val s = segs.first()
        val span = s.routeEndM - s.routeStartM
        val ghostM = s.ghost.totalDistanceM
        val ratio = ghostM / span
        // On the buggy code the ghost smears across both laps (ratio ~2.0). After the fix the ghost
        // is ONE lap, within ~15 % of the declared span.
        assertTrue(
            "ghost.totalDistanceM ($ghostM) should be within 15% of ONE lap's span ($span), ratio=$ratio",
            ratio in 0.85..1.15,
        )
        assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
    }

    /**
     * BUG 2 regression: a recorded track with a backward TIME glitch (odometer increasing but timeS
     * decreasing at one point) flowed into RecordedGhostSource.fromTrackSlice -> GhostCurve, which
     * threw IllegalArgumentException("decreasing time") and crashed match() at route load. The chain
     * walk now also requires strictly increasing time for a kept point, so the emitted slice is
     * monotonic in BOTH distance and time and GhostCurve never throws.
     */
    @Test fun `non-monotonic time track does not crash match and yields a sane segment`() {
        // Track rides the middle ~1 km east (lng 0.004..0.013) at 5 m/s, EXCEPT point #9 whose time
        // jumps BACKWARD (odometer still increasing). On the buggy code this reaches GhostCurve and
        // throws; the time-monotonic guard skips the bad point so the slice stays monotonic.
        val pts = (0..18).map { i ->
            val lng = 0.004 + i * 0.0005
            if (i == 9) {
                // Time glitch: distance keeps climbing, but time goes backward by 50 s.
                pt(lng, distanceM = i * 55.0, t = i * 11.0 - 50.0)
            } else {
                pt(lng, distanceM = i * 55.0, t = i * 11.0)
            }
        }
        val track = RecordedTrack(
            id = "timeglitch", startedAtEpoch = 1_000L,
            points = pts.map { it.toDto() },
        )

        // Must NOT throw.
        val segs = SegmentMatcher.match(route, listOf(track), GhostPick.BEST, params)
        // A sane segment is produced (the stretch is otherwise clean) with a monotonic ghost.
        assertTrue("expected at least one segment, got ${segs.size}", segs.isNotEmpty())
        val s = segs.first()
        assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
        assertTrue("ghost time must be positive", s.ghost.totalTimeS > 0.0)
    }

    /**
     * Candidate cap (safety budget, perf fix B): when more than `maxTracks` candidates are supplied
     * the matcher must process EXACTLY the `maxTracks` most-RECENT (largest startedAtEpoch) tracks
     * and ignore the rest — deterministically. We give the 3 OLDEST tracks a much FASTER ghost than
     * the 2 NEWEST. With `maxTracks = 2` keeping only the two newest, a `BEST` pick can only choose
     * the faster of the two newest; if the cap leaked an old (faster) track, BEST would pick it and
     * the chosen time would be far smaller. So the chosen ghost time pins which tracks were processed.
     */
    @Test fun `match caps to the maxTracks most-recent candidates and ignores older ones`() {
        // All five tracks ride the SAME middle stretch (lng 0.004..0.013) so they group together.
        // secPerStep encodes the ghost speed; epoch encodes recency.
        fun track(id: String, epoch: Long, secPerStep: Double) =
            RecordedTrack(
                id, epoch,
                (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * secPerStep).toDto() },
            )

        // Oldest three are FAST (5..7 s/step); newest two are SLOW (15, 18 s/step).
        val old1 = track("old1", epoch = 1_000L, secPerStep = 5.0)
        val old2 = track("old2", epoch = 2_000L, secPerStep = 6.0)
        val old3 = track("old3", epoch = 3_000L, secPerStep = 7.0)
        val new1 = track("new1", epoch = 10_000L, secPerStep = 15.0)
        val new2 = track("new2", epoch = 11_000L, secPerStep = 18.0)
        val all = listOf(old1, new2, old3, new1, old2) // deliberately unsorted input

        val capped = params.copy(maxTracks = 2)
        val segs = SegmentMatcher.match(route, all, GhostPick.BEST, capped)
        assertEquals(1, segs.size)

        // Only new1 (15 s/step) and new2 (18 s/step) were processed; BEST picks new1's ghost.
        // new1 covers ~18 steps; its total time is ~18 * 15 = 270 s. An old (fast) track, if leaked,
        // would yield ~90..126 s. Assert the chosen time is the slow new1, proving the cap held.
        val chosenTime = segs.first().ghost.totalTimeS
        assertTrue(
            "expected new1's slow ghost (~270 s), got $chosenTime — cap must exclude faster old tracks",
            chosenTime > 200.0,
        )
    }

    @Test fun `out-and-back track rides the shared stretch twice and yields one clean pass`() {
        // Route: A(0,0) -> B(0,0.018) east (~2 km) -> back to A. Total ~4 km.
        // Densely vertexed (~67 m) like a real decimated route, so the per-segment window of the
        // forward-biased slice projection can segregate the outbound and return passes.
        val outLngs = generateSequence(0.0) { it + 0.0006 }.takeWhile { it < 0.018 }.toList() + 0.018
        val backLngs = outLngs.reversed().drop(1)
        val outAndBack = PolylinePath((outLngs + backLngs).map { LatLng(0.0, it) })

        // Track: ride the middle ~1 km east (out), then back west over the SAME longitudes (return).
        // Distances and times are cumulative across both passes (a real ride never rewinds).
        val outPts = (0..18).map { i ->
            val lng = 0.004 + i * 0.0005
            pt(lng, distanceM = i * 55.0, t = i * 11.0)
        }
        val lastOut = outPts.last()
        val returnPts = (1..18).map { j ->
            val lng = 0.013 - j * 0.0005 // walk back west over the same span
            pt(lng, distanceM = lastOut.distanceM + j * 55.0, t = lastOut.timeS + j * 11.0)
        }
        val track = RecordedTrack(
            id = "oab", startedAtEpoch = 1_000L,
            points = (outPts + returnPts).map { it.toDto() },
        )

        val segs = SegmentMatcher.match(outAndBack, listOf(track), GhostPick.BEST, params)
        assertTrue("expected at least one segment, got ${segs.size}", segs.isNotEmpty())

        // Every emitted segment must be built from ONE clean pass: the ghost covers the full
        // declared span (no smeared/shrunk curve), within ~15 %.
        for (s in segs) {
            val span = s.routeEndM - s.routeStartM
            val ghostM = s.ghost.totalDistanceM
            val ratio = ghostM / span
            assertTrue(
                "ghost.totalDistanceM ($ghostM) must be within 15% of span ($span) for one pass, ratio=$ratio",
                ratio in 0.85..1.15,
            )
            // Sanity: the ghost is monotonic and starts at zero (single pass, not a smear).
            assertEquals(0.0, s.ghost.timeAt(0.0), 1e-6)
        }
    }

    @Test fun `routeLaps returns each track's route-distance time series`() {
        val track = RecordedTrack(
            id = "t1", startedAtEpoch = 1_000L,
            points = (0..18).map { i -> pt(0.004 + i * 0.0005, i * 55.0, i * 11.0).toDto() },
        )
        val laps = SegmentMatcher.routeLaps(route, listOf(track), params)
        assertEquals(1, laps.size)                 // one track → one lap (single covered interval)
        val lap = laps.first()
        assertTrue(lap.size >= 2)
        // Ascending in routeDist; each sample is [routeDistM, timeS].
        assertTrue(lap.zipWithNext().all { (a, b) -> b[0] >= a[0] })
        assertTrue(lap.first()[1] <= lap.last()[1]) // time non-decreasing
    }

    @Test fun `routeLaps decimates a dense track to ~50 m for the seed`() {
        // A DENSE track: ~11 m between points (distanceM = i*11) over the route's middle ~1 km.
        val dense = RecordedTrack(
            id = "dense", startedAtEpoch = 1_000L,
            points = (0..90).map { i -> pt(0.004 + i * 0.0001, i * 11.0, i * 2.2).toDto() },
        )
        val lap = SegmentMatcher.routeLaps(route, listOf(dense), params).first()
        // 91 raw points (~11 m apart), but the seed keeps ~one per SEED_DECIMATE_M (30 m) → the average
        // routeDist gap is ~30 m, well above the raw ~11 m, proving decimation happened.
        val avgGapM = (lap.last()[0] - lap.first()[0]) / (lap.size - 1)
        assertTrue("decimated to ~30 m spacing (avg gap ${avgGapM}m, ${lap.size} pts)", avgGapM >= 22.0)
        assertTrue("still ascending in routeDist", lap.zipWithNext().all { (a, b) -> b[0] >= a[0] })
    }
}
