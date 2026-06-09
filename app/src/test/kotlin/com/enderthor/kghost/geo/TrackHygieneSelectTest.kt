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
        TrackMeta(id, cells, cells, fp, totalDistanceM = 1000.0, totalTimeS = time, startedAtEpoch = epoch)

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
