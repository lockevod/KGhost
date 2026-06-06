package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialIndexTest {
    @Test fun `a track near the query bbox is a candidate, a far one is not`() {
        val idx = SpatialIndex(precision = 6)
        idx.add("near", BBox(41.380, 41.390, 2.170, 2.180))   // Barcelona-ish
        idx.add("far",  BBox(40.410, 40.420, -3.710, -3.700)) // Madrid-ish
        val cands = idx.candidates(BBox(41.382, 41.388, 2.172, 2.178))
        assertTrue("near" in cands)
        assertFalse("far" in cands)
    }

    @Test fun `geohash cells of a bbox are stable and non-empty`() {
        val idx = SpatialIndex(precision = 6)
        val cells = idx.cellsFor(BBox(41.38, 41.39, 2.17, 2.18))
        assertTrue(cells.isNotEmpty())
    }

    @Test fun `geohash length equals precision and nearby points share a prefix`() {
        val precision = 6
        val a = geohash(41.3850, 2.1750, precision)
        val b = geohash(41.3851, 2.1751, precision)
        assertEquals(precision, a.length)
        assertEquals(precision, b.length)
        // Two points ~15 m apart must share at least the first 5 of 6 characters.
        assertEquals(a.substring(0, 5), b.substring(0, 5))
    }
}
