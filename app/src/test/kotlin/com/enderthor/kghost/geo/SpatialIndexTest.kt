package com.enderthor.kghost.geo

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

    /**
     * Regression test for the 64-sample-per-axis cap recall failure.
     *
     * With the old fixed step (0.005°) and cap (64), a bbox spanning ~0.9° lat produced an actual
     * sampling step of 0.9/64 ≈ 0.014° — larger than a precision-6 cell height (~0.0055°). Interior
     * cells between samples were never indexed, so a query pinpointing one such interior cell silently
     * returned no candidates.
     *
     * Concretely: bbox minLat=41.0, maxLat=41.9. Old samples at lat 41.0, 41.014, 41.028, …
     * A query at lat 41.007 falls between old samples 0 and 1, in a cell that was never indexed.
     *
     * With the new half-cell stepping (stepLat ≈ 0.00274°, stepLng ≈ 0.00549° at precision 6) the
     * cell at (41.007, 2.175) is always hit during indexing, so the query finds the track.
     */
    @Test fun `long route bbox interior cells are indexed and found by a point query`() {
        val idx = SpatialIndex(precision = 6)

        // ~100 km route bbox (0.9° lat ≈ 100 km). The interior point at (41.007, 2.175) is
        // deliberately chosen to fall between the old coarse samples (spaced ~0.014° apart).
        idx.add("long", BBox(minLat = 41.0, maxLat = 41.9, minLng = 2.0, maxLng = 2.5))

        // Tiny query bbox centred on the interior point (well within a single precision-6 cell).
        val queryCentreLat = 41.007
        val queryCentreLng = 2.175
        val queryBbox = BBox(
            minLat = queryCentreLat - 0.001,
            maxLat = queryCentreLat + 0.001,
            minLng = queryCentreLng - 0.001,
            maxLng = queryCentreLng + 0.001,
        )

        val cands = idx.candidates(queryBbox)
        assertTrue(
            "Interior cell of long route bbox must appear in candidates (was silently missed with old 64-cap sampling)",
            "long" in cands,
        )
    }

    @Test fun `cellsForPath returns the cells along a straight corridor`() {
        val idx = SpatialIndex(precision = 6)
        // A straight, dense path going east along a corridor (steps well under one cell width).
        val path = (0..20).map { LatLng(41.380, 2.170 + it * 0.0008) }
        val cells = idx.cellsForPath(path)

        // Every consecutive sample's cell must be present (the path passes through it).
        for (p in path) {
            assertTrue("path cell ${geohash(p.lat, p.lng, 6)} must be indexed", geohash(p.lat, p.lng, 6) in cells)
        }
    }

    @Test fun `cellsForPath of a thin diagonal line excludes a bbox cell off the path`() {
        val idx = SpatialIndex(precision = 6)
        // A thin diagonal line from SW to NE. Its bounding box is a large rectangle, but the line
        // itself is thin: the bbox CORNERS (e.g. the NW corner) lie far from the line.
        val sw = LatLng(41.000, 2.000)
        val ne = LatLng(41.090, 2.090)
        // Dense samples along the diagonal so each segment bbox ~ the segment (no gaps).
        val n = 200
        val path = (0..n).map { i ->
            val f = i.toDouble() / n
            LatLng(sw.lat + f * (ne.lat - sw.lat), sw.lng + f * (ne.lng - sw.lng))
        }
        val pathCells = idx.cellsForPath(path)

        // The full bounding box of the diagonal.
        val bbox = BBox.around(path)!!
        val bboxCells = idx.cellsFor(bbox)

        // KEY PROPERTY: path cells are a STRICT subset of the bbox cells (the line does not fill the box).
        assertTrue("path cells must be subset of bbox cells", bboxCells.containsAll(pathCells))
        assertTrue("a thin line must touch fewer cells than its full bbox", pathCells.size < bboxCells.size)

        // The NW corner of the bbox (max lat, min lng) is far from a SW→NE diagonal: its cell must be
        // in the bbox cell set but NOT on the path.
        val nwCorner = geohash(bbox.maxLat, bbox.minLng, 6)
        assertTrue("NW corner cell is inside the bbox cell set", nwCorner in bboxCells)
        assertFalse("NW corner cell is OFF the diagonal path and must be absent", nwCorner in pathCells)
    }

    @Test fun `cellsForPath handles fewer than two points`() {
        val idx = SpatialIndex(precision = 6)
        assertTrue("empty path yields no cells", idx.cellsForPath(emptyList()).isEmpty())
        val single = idx.cellsForPath(listOf(LatLng(41.38, 2.17)))
        assertEquals("single point yields its one cell", setOf(geohash(41.38, 2.17, 6)), single)
    }
}
