package com.enderthor.kghost.engine

import com.enderthor.kghost.data.sanitizeTargetMs
import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.PolylinePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceComparatorTest {

    /** A straight east-west path on the equator of (about) [lengthM] metres. */
    private fun straight(lengthM: Double): PolylinePath =
        PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, lengthM / 111_195.0)))

    // --- historyCoverage ---

    @Test fun `coverage is the fraction of the planned path the history answers`() {
        val p = straight(1000.0)
        val cutLng = p.sampleAt(p.totalM * 0.3).location.lng
        val cov = historyCoverage(p, { _, lng, _ -> lng < cutLng })
        assertEquals(0.3, cov, 25.0 / p.totalM)
    }

    @Test fun `a partial tail is weighted by its real length, not a whole step`() {
        // 1010 m: samples at 0, 25 .. 1000; only the last one hits and it stands for the final 10 m.
        val p = straight(1010.0)
        val tailLng = p.sampleAt(1000.0).location.lng
        val cov = historyCoverage(p, { _, lng, _ -> lng >= tailLng })
        assertEquals((p.totalM - 1000.0) / p.totalM, cov, 1e-6)
    }

    @Test fun `a zero-length path has no coverage`() {
        // PolylinePath itself refuses < 2 points; a degenerate route still arrives as two identical points.
        val always = { _: Double, _: Double, _: Double -> true }
        assertEquals(0.0, historyCoverage(PolylinePath(listOf(LatLng(1.0, 1.0), LatLng(1.0, 1.0))), always), 0.0)
    }

    @Test fun `full history covers the whole path`() {
        assertEquals(1.0, historyCoverage(straight(1010.0), { _, _, _ -> true }), 1e-9)
    }

    @Test fun `the walk is cancellable`() {
        var calls = 0
        historyCoverage(straight(1000.0), { _, _, _ -> false }, stepM = 1.0, checkCancel = { calls++ })
        assertTrue("checkCancel must run during a long walk: $calls", calls >= 4)
    }

    // --- chooseComparator ---

    @Test fun `target only below the coverage floor and with no usable grade model`() {
        assertEquals(RaceComparator.TARGET, chooseComparator(0.0999, gradeUsable = false))
        assertEquals(RaceComparator.HISTORY, chooseComparator(0.10, gradeUsable = false))
        assertEquals(RaceComparator.HISTORY, chooseComparator(0.1001, gradeUsable = false))
        // Field logs 9fe84d/0cbb96: ~3% brush. Without a grade model that is a target race...
        assertEquals(RaceComparator.TARGET, chooseComparator(0.03, gradeUsable = false))
        // ...but a usable grade model answers the rest of the route, so history races.
        assertEquals(RaceComparator.HISTORY, chooseComparator(0.0, gradeUsable = true))
    }

    @Test fun `non-finite coverage counts as none`() {
        assertEquals(RaceComparator.TARGET, chooseComparator(Double.NaN, gradeUsable = false))
        assertEquals(RaceComparator.TARGET, chooseComparator(Double.POSITIVE_INFINITY, gradeUsable = false))
    }

    // --- selectRacePace ---

    private val boom: () -> Double? = { throw AssertionError("history consulted in a TARGET race") }

    @Test fun `a target race never consults history and races the sanitized target`() {
        assertEquals(1.0 / sanitizeTargetMs(8.0), selectRacePace(RaceComparator.TARGET, true, 8.0, boom, boom)!!, 0.0)
        // A garbage target is sanitized, never divided raw.
        assertEquals(1.0 / sanitizeTargetMs(0.0), selectRacePace(RaceComparator.TARGET, true, 0.0, boom, boom)!!, 0.0)
    }

    @Test fun `no verdict means no pace for either comparator`() {
        assertNull(selectRacePace(RaceComparator.TARGET, false, 8.0, boom, boom))
        assertNull(selectRacePace(RaceComparator.HISTORY, false, 8.0, boom, boom))
    }

    @Test fun `a history race is patch then grade then neutral, never the target`() {
        assertEquals(0.2, selectRacePace(RaceComparator.HISTORY, true, 8.0, { 0.2 }, boom)!!, 0.0)
        assertEquals(0.3, selectRacePace(RaceComparator.HISTORY, true, 8.0, { null }, { 0.3 })!!, 0.0)
        assertNull(selectRacePace(RaceComparator.HISTORY, true, 8.0, { null }, { null }))
    }

    // --- routeGapPublication ---

    @Test fun `history race before its first verdict publishes nothing, even inside a hold`() {
        for (hold in listOf(false, true)) for (pub in listOf(false, true))
            assertEquals(RouteGapPublication.INACTIVE, routeGapPublication(RaceComparator.HISTORY, false, hold, pub))
    }

    @Test fun `a target race publishes from the first tick`() {
        assertEquals(RouteGapPublication.PUBLISH, routeGapPublication(RaceComparator.TARGET, false, false, false))
    }

    @Test fun `the hold freezes only a race that has already published`() {
        for (c in RaceComparator.entries) {
            assertEquals(RouteGapPublication.HOLD, routeGapPublication(c, true, pendingHold = true, publishedThisRace = true))
            // A restored lead is shown on the first tick even inside a hold.
            assertEquals(RouteGapPublication.PUBLISH, routeGapPublication(c, true, pendingHold = true, publishedThisRace = false))
            assertEquals(RouteGapPublication.PUBLISH, routeGapPublication(c, true, pendingHold = false, publishedThisRace = true))
        }
    }

    // --- consumedHistory ---

    @Test fun `only a matched-metre increase counts as a verdict`() {
        assertTrue(consumedHistory(10.0, 10.5))
        assertFalse(consumedHistory(10.0, 10.0))
        assertFalse(consumedHistory(10.0, 9.0))
    }
}
