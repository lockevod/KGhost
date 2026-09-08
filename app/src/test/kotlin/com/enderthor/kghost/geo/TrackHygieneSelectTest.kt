package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackHygieneSelectTest {

    // Synthetic twins: share fineCells F (dilated = fine here — we test the SELECTION logic, not the
    // geometry) + the same direction fingerprint + length; vary id/time/epoch.
    // F has 20 cells so a track with ONE extra unique cell still clears TWIN_COVER (20/21 ≈ 0.95).
    private val F = (1..20).map { "c$it" }.toSet()
    private val FP = listOf(LatLng(41.0, 2.0), LatLng(41.0, 2.05), LatLng(41.0, 2.1))
    private fun twin(id: String, epoch: Long, time: Double?, cells: Set<String> = F, fp: List<LatLng> = FP) =
        TrackMeta(id, cells, cells, fp, totalDistanceM = 1000.0, totalTimeS = time, startedAtEpoch = epoch,
            source = Source.RECORDED)

    // ---- one ride stored twice --------------------------------------------------------------
    // A ride enters the library live AND again when its own FIT is scanned. The sourceKey meant to
    // collapse the pair is minute-of-start + distance bucketed to 10 m, finer than the jitter between
    // the two paths: measured on a real library, 8 such pairs, ZERO shared a key. They form a twin
    // group of 2, which the size rule protects forever, so the ride is counted twice in every average
    // for that route.

    private fun rec(id: String, epoch: Long, dist: Double, cells: Set<String> = F) =
        TrackMeta(id, cells, cells, FP, dist, totalTimeS = 600.0, startedAtEpoch = epoch, source = Source.RECORDED)

    private fun scanned(id: String, epoch: Long, dist: Double, cells: Set<String> = F) =
        TrackMeta(id, cells, cells, FP, dist, totalTimeS = 600.0, startedAtEpoch = epoch, source = Source.FITFILES_SCAN)

    @Test fun `a live recording and its own scanned FIT collapse to one, even as a group of two`() {
        // The real 2026-08-19 pair: same start, 80.7 km, 10 m apart — a different sourceKey bucket.
        val out = selectArchivable(listOf(rec("live", 1_000_000L, 80_700.0), scanned("fit", 1_000_000L, 80_690.0)))
        assertEquals(listOf("fit"), out) // the LONGER track survives
    }

    @Test fun `a three-minute start skew and eighty metres still collapse`() {
        // The real 2026-07-12 (minute -3) and 2026-05-25 (-80 m) failures, together.
        val out = selectArchivable(listOf(rec("live", 1_000_000L, 40_400.0), scanned("fit", 1_180_000L, 40_320.0)))
        assertEquals(listOf("fit"), out) // 3 min apart and 80 m shorter — still one ride; longer survives
    }

    @Test fun `a start skew just past the window does NOT collapse`() {
        // Pins SAME_RIDE_START_WINDOW_MS. Without this the window could be widened to hours — and
        // "morning loop plus afternoon loop" becomes a false positive that archives a real ride.
        val inside = selectArchivable(listOf(rec("l", 0L, 25_000.0), scanned("f", 599_000L, 25_000.0)))
        assertEquals(listOf("f"), inside)
        val outside = selectArchivable(listOf(rec("l", 0L, 25_000.0), scanned("f", 601_000L, 25_000.0)))
        assertEquals(emptyList<String>(), outside)
    }

    @Test fun `a length gap past the tolerance does NOT collapse`() {
        // Pins SAME_RIDE_LENGTH_TOL at a length where the 100 m floor does NOT govern: 1 % of 50 km is
        // 500 m. Every other test sits under the floor, so the relative term was untested.
        val inside = selectArchivable(listOf(rec("l", 0L, 50_000.0), scanned("f", 0L, 49_600.0)))
        assertEquals(listOf("f"), inside)   // 400 m < 500 m
        val outside = selectArchivable(listOf(rec("l", 0L, 50_000.0), scanned("f", 0L, 49_400.0)))
        assertEquals(emptyList<String>(), outside) // 600 m > 500 m
    }

    @Test fun `two file-sourced tracks are never collapsed by this rule`() {
        val a = TrackMeta("scan", F, F, FP, 25_000.0, 600.0, 0L, Source.FITFILES_SCAN)
        val b = TrackMeta("imp", F, F, FP, 24_990.0, 600.0, 0L, Source.FIT_IMPORT)
        assertEquals(emptyList<String>(), selectArchivable(listOf(a, b)))
    }

    @Test fun `a FIT the rider imported is never treated as their own duplicate`() {
        // The destructive false positive: a group ride. A buddy's FIT of the SAME outing, dropped in the
        // import directory, has the same route, the same hour and near-identical length — geometry cannot
        // tell it from your own. Only FITFILES_SCAN, written by this Karoo, carries that promise.
        val mine = rec("mine", 0L, 25_000.0)
        val theirs = TrackMeta("theirs", F, F, FP, 24_950.0, 600.0, 60_000L, Source.FIT_IMPORT)
        assertEquals("another rider's ride is not mine to archive", emptyList<String>(), selectArchivable(listOf(mine, theirs)))
        // A GPX of the same route is likewise not a recording of this ride.
        val gpx = TrackMeta("gpx", F, F, FP, 24_950.0, 600.0, 60_000L, Source.GPX_IMPORT)
        assertEquals(emptyList<String>(), selectArchivable(listOf(mine, gpx)))
    }

    @Test fun `the whole chain fires from a stored RecordedTrack, not just a hand-built meta`() {
        // BOTH reviews found the rule was dead in production: trackMetaOf passed 7 positional arguments
        // to an 8-parameter constructor, so source silently defaulted and isSameRide always refused.
        // Every unit test above builds TrackMeta directly and stayed green. This one goes through the
        // ONLY path production uses.
        val pts = listOf(
            TrackPointDto(41.0, 2.0, 0.0, 0.0),
            TrackPointDto(41.0, 2.005, 400.0, 40.0),
            TrackPointDto(41.0, 2.010, 800.0, 80.0),
        )
        val live = RecordedTrack("live", 1_000_000L, pts, source = Source.RECORDED)
        val scan = RecordedTrack("scan", 1_000_000L, pts, source = Source.FITFILES_SCAN)
        val out = selectArchivable(listOf(live, scan).map { trackMetaOf(it) })
        assertEquals(listOf("scan"), out)
    }

    @Test fun `a short ride is held to the metre floor, not to one percent`() {
        // Pins SAME_RIDE_LENGTH_FLOOR_M. 1 % of 2 km is 20 m, so without the floor a 60 m spread — well
        // inside the observed drift between the two paths — would fail to collapse.
        val inside = selectArchivable(listOf(rec("l", 0L, 2_000.0), scanned("f", 0L, 1_940.0)))
        assertEquals(listOf("f"), inside)   // 60 m < 100 m floor
        val outside = selectArchivable(listOf(rec("l", 0L, 2_000.0), scanned("f", 0L, 1_880.0)))
        assertEquals(emptyList<String>(), outside) // 120 m > floor
    }

    @Test fun `a recording pairs with the NEAREST fit, not the first one it meets`() {
        // Hill repeats: every lap is "the same length" under the 100 m floor. Pairing by list order let
        // lap 1's recording claim lap 2's FIT and archive a genuinely distinct ride.
        val live1 = rec("live1", 0L, 2_010.0)
        val fit1 = scanned("fit1", 5_000L, 1_995.0)      // lap 1's own FIT, 5 s away
        val fit2 = scanned("fit2", 480_000L, 2_005.0)    // lap 2, 8 min away — longer, so sorted first
        val out = selectArchivable(listOf(live1, fit2, fit1))
        assertEquals("lap 2's FIT must survive", listOf("fit1"), out)
    }

    @Test fun `two laps of the same circuit are NOT a duplicate`() {
        // Both arrive by the SAME path, so however close their start and length, they are real history.
        // Without the ingest-path condition, start+length alone would archive a genuine second lap.
        val lap1 = scanned("lap1", 1_000_000L, 3_000.0)
        val lap2 = scanned("lap2", 1_300_000L, 3_005.0) // 5 min later, 5 m longer
        assertEquals(emptyList<String>(), selectArchivable(listOf(lap1, lap2)))
    }

    @Test fun `two rides far apart in time are NOT a duplicate`() {
        val out = selectArchivable(listOf(rec("today", 2_000_000_000L, 25_000.0), scanned("lastweek", 1_000_000_000L, 25_000.0)))
        assertEquals(emptyList<String>(), out)
    }

    @Test fun `a duplicate that covers ground the survivor does not is kept`() {
        val out = selectArchivable(
            listOf(rec("live", 1_000_000L, 25_000.0), scanned("fit", 1_000_000L, 24_990.0, cells = F + "cUNIQUE")),
        )
        assertEquals(emptyList<String>(), out)
    }

    @Test fun `group of 3 or fewer is a no-op`() {
        val out = selectArchivable(listOf(twin("a", 1, 600.0), twin("b", 2, 590.0), twin("c", 3, 580.0)))
        assertEquals(emptyList<String>(), out)
    }

    @Test fun `keeps fastest plus two latest, archives the rest`() {
        val tracks = listOf(
            twin("slowOld", 1, 800.0),   // loser
            twin("record", 2, 500.0),    // fastest → keep
            twin("mid", 3, 650.0),       // loser
            twin("latest1", 5, 700.0),   // 2nd latest → keep
            twin("latest2", 6, 690.0),   // latest → keep
        )
        assertEquals(setOf("slowOld", "mid"), selectArchivable(tracks).toSet())
    }

    @Test fun `a loser with a unique fine cell is kept by the coverage guard`() {
        val tracks = listOf(
            twin("record", 2, 500.0),
            twin("latest1", 5, 700.0),
            twin("latest2", 6, 690.0),
            twin("uniq", 1, 800.0, cells = F + "cUNIQUE"),  // loser, covers a cell no survivor has
        )
        assertEquals(emptyList<String>(), selectArchivable(tracks))
    }

    @Test fun `cross-group survivor does NOT authorize archiving a loser (per-group guard)`() {
        // Group A (4 twins). A-loser also rode SHARED, which no A-survivor covers.
        val aFast = twin("a-fast", 2, 500.0)
        val aL1 = twin("a-late1", 5, 700.0)
        val aL2 = twin("a-late2", 6, 690.0)
        val aLoser = twin("a-loser", 1, 800.0, cells = F + "SHARED")
        // Group B: a DIFFERENT route (far-away fingerprint, disjoint cells) that happens to cover SHARED.
        val bFp = listOf(LatLng(42.0, 3.0), LatLng(42.0, 3.05), LatLng(42.0, 3.1))
        val bSurv = twin("b-surv", 9, 400.0, cells = setOf("b1", "b2", "SHARED"), fp = bFp)
        val out = selectArchivable(listOf(aFast, aL1, aL2, aLoser, bSurv)).toSet()
        // The OLD global guard would archive a-loser (B globally "covers" SHARED). Per-group keeps it.
        assertTrue("a-loser kept", "a-loser" !in out)
    }

    @Test fun `a paused-time junk track is never the fastest and is archived`() {
        // junk: 1000 m in 3 s ⇒ 333 m/s, implausible ⇒ not eligible as "fastest"
        val tracks = listOf(
            twin("junk", 1, 3.0),
            twin("realRecord", 2, 500.0),
            twin("latest1", 5, 700.0),
            twin("latest2", 6, 690.0),
        )
        val out = selectArchivable(tracks).toSet()
        assertTrue("junk archived", "junk" in out)
        assertTrue("real record kept", "realRecord" !in out)
    }

    @Test fun `two disjoint routes form independent groups`() {
        // Route 1 (cells A, fingerprint FP): fastest is an early ride so it's distinct from the 2 latest.
        val A = setOf("a1", "a2", "a3")
        val g1 = listOf(
            twin("g1-fast", 1, 500.0, A), twin("g1-x", 2, 700.0, A), twin("g1-y", 3, 700.0, A),
            twin("g1-late1", 4, 700.0, A), twin("g1-late2", 5, 700.0, A),
        )
        // Route 2: disjoint cells AND a far-away fingerprint ⇒ never grouped with route 1.
        val B = setOf("b1", "b2", "b3"); val g2fp = listOf(LatLng(42.0, 3.0), LatLng(42.0, 3.05), LatLng(42.0, 3.1))
        val g2 = listOf(
            twin("g2-fast", 1, 500.0, B, g2fp), twin("g2-x", 2, 700.0, B, g2fp), twin("g2-y", 3, 700.0, B, g2fp),
            twin("g2-late1", 4, 700.0, B, g2fp), twin("g2-late2", 5, 700.0, B, g2fp),
        )
        assertEquals(setOf("g1-x", "g1-y", "g2-x", "g2-y"), selectArchivable(g1 + g2).toSet())
    }
}
