package com.enderthor.kghost.geo

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Uniform spatial grid over the SEGMENTS of a [PolylinePath], built once per track to make the
 * coverage scan in [SegmentMatcher.coveredRunsToIntervals] near-O(1) per route sample instead of
 * O(trackPoints).
 *
 * ## What it computes
 * [nearestPerpDistM] returns the same perpendicular distance (m) to the nearest track segment as
 * [PolylinePath.nearestPerpDistM] (the brute-force full scan) **for every query whose true nearest
 * perpendicular distance is below [SegmentMatcher]'s tolerance** — which is the only regime the
 * coverage decision cares about. For queries farther than the tolerance it returns *some* distance
 * `>= tolerance` (possibly a sentinel), which is sufficient because the caller only tests
 * `nearestPerpDistM(...) < tol`. The differential test [PointGridDiffTest] proves:
 *   - `(grid.nearestPerpDistM(p) < tol) == (brute.nearestPerpDistM(p) < tol)` for every sample, and
 *   - `grid.nearestPerpDistM(p) == brute.nearestPerpDistM(p)` exactly whenever that value `< tol`,
 * so neither the covered/uncovered boolean nor the interval boundaries change.
 *
 * ## Why it is correct
 * The grid is built with a known [queryToleranceM] (the matcher's `toleranceM`). Cells are square in
 * metres (a local equirectangular plane centred on the path's first point). Each segment is
 * registered into EVERY cell whose square intersects the segment's bounding box, expanded by
 * [queryToleranceM] on every side (a "fat" bbox). Therefore any segment whose perpendicular foot is
 * within [queryToleranceM] of a query point registers in the query's own cell. Scanning the query
 * cell alone would already be sufficient by that argument, but to be robust against the
 * equirectangular metric varying slightly across the path we scan a 3×3 neighbourhood of cells
 * around the query. The grid is therefore a SUPERSET filter: it never misses a segment within the
 * tolerance, so the minimum perpendicular distance it computes equals the brute-force minimum
 * whenever that minimum is `< tolerance`.
 *
 * Pure Kotlin (no Android). The perpendicular-distance arithmetic is byte-for-byte the same local
 * equirectangular formula as [PolylinePath.nearestPerpDistM], so the value it returns for the
 * winning segment matches brute exactly.
 */
internal class PointGrid private constructor(
    private val path: PolylinePath,
    private val queryToleranceM: Double,
    /**
     * Optional clip box (lat/lng degrees). When non-null, only the path SEGMENTS whose own segment
     * bbox INTERSECTS this box are indexed; segments fully outside it are skipped. The clip box must
     * be the area the grid will ever be queried against, FATTENED by at least [queryToleranceM] on
     * every side, so this is COVERAGE-IDENTICAL: a segment whose bbox lies entirely outside the
     * fattened clip cannot have a perpendicular foot within [queryToleranceM] of any query inside the
     * clip, so dropping it never changes a `< tol` decision. Clipping bounds memory (and the metric
     * grid extent) to the clip area instead of the path's full span — preventing OOM on a track that
     * crosses the route then continues for tens of km.
     */
    private val clip: BBox? = null,
) {
    constructor(path: PolylinePath, queryToleranceM: Double) :
        this(path, queryToleranceM, null)

    companion object {
        /**
         * Builds a grid over only the path segments whose segment-bbox intersects [clipBBox]
         * (segments fully outside are skipped). [clipBBox] must already be fattened by at least
         * [cellSizeM]'s tolerance on every side by the caller. COVERAGE-IDENTICAL for queries inside
         * the clip; see [clip].
         */
        fun forPathClippedTo(path: PolylinePath, cellSizeM: Double, clipBBox: BBox): PointGrid =
            PointGrid(path, cellSizeM, clipBBox)
    }

    // Local metric plane: metres east/north of the path's first point. Latitude scale is constant;
    // longitude scale uses the cosine at the reference latitude. This is the same per-degree scaling
    // PolylinePath uses per-segment; using a single reference latitude here only affects which CELL a
    // point lands in (a coarse bucketing decision), never the perpendicular distance returned (that
    // is recomputed with PolylinePath's own per-segment formula in nearestPerpDistM).
    private val pts = path.points
    private val refLat = pts.first().lat
    private val refLng = pts.first().lng
    private val mPerDegLat = 111_320.0
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(refLat))

    // Cell size ≈ tolerance, with a sane floor so a degenerate (tolerance≈0) build still works.
    private val cell = queryToleranceM.coerceAtLeast(1.0)

    // Grid origin and dimensions in cell units, derived from the path's metric bbox.
    private val minCx: Int
    private val minCy: Int
    private val cols: Int
    private val rows: Int

    // Buckets: cell index -> list of segment indices (segment i = points[i]..points[i+1]).
    private val buckets: Array<IntArray>

    /**
     * Number of grid cells allocated (cols × rows). Exposed for tests to assert the grid is bounded
     * to the clip area (not the full track span) — the memory-bound proof for the clip fix.
     */
    internal val cellCount: Int get() = cols * rows

    private fun xOf(lng: Double): Double = (lng - refLng) * mPerDegLng
    private fun yOf(lat: Double): Double = (lat - refLat) * mPerDegLat
    private fun cellX(x: Double): Int = floor(x / cell).toInt()
    private fun cellY(y: Double): Int = floor(y / cell).toInt()

    init {
        val n = pts.size
        // Metric bbox of the path — restricted to the KEPT segments (those passing the clip), so the
        // grid extent (and hence cell count / memory) is bounded to the clip area, not the path span.
        // Falls back to the path's full vertex bbox when no segment is kept (degenerate) so the array
        // is still valid.
        //
        // Crucially, when a kept segment BRIDGES out of the clip (one endpoint inside, the other tens
        // of km away — the on-route→tail jump), we must NOT let its far endpoint expand the grid to
        // the whole track span (that would defeat the clip / re-introduce the OOM). The grid is only
        // ever queried at points inside the clip, so we CLAMP each endpoint's metric coordinate to the
        // clip's metric rectangle before growing the extent. The bridging segment is still indexed
        // into the boundary cells it touches WITHIN the clip (forEachSegmentCellWithSeg walks its real
        // fat-bbox cells; cellIndex() returns -1 for cells outside the grid, so out-of-clip cells are
        // simply ignored) — coverage inside the clip is unchanged.
        val clipMinX: Double; val clipMaxX: Double; val clipMinY: Double; val clipMaxY: Double
        if (clip != null) {
            val cx0 = xOf(clip.minLng); val cx1 = xOf(clip.maxLng)
            val cy0 = yOf(clip.minLat); val cy1 = yOf(clip.maxLat)
            clipMinX = minOf(cx0, cx1); clipMaxX = maxOf(cx0, cx1)
            clipMinY = minOf(cy0, cy1); clipMaxY = maxOf(cy0, cy1)
        } else {
            clipMinX = Double.NEGATIVE_INFINITY; clipMaxX = Double.POSITIVE_INFINITY
            clipMinY = Double.NEGATIVE_INFINITY; clipMaxY = Double.POSITIVE_INFINITY
        }
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var anyKept = false
        for (i in 0 until n - 1) {
            if (!segmentKept(i)) continue
            anyKept = true
            val a = pts[i]; val b = pts[i + 1]
            for (p in listOf(a, b)) {
                // Clamp to the clip metric rect so a far bridging endpoint can't blow up the extent.
                val x = xOf(p.lng).coerceIn(clipMinX, clipMaxX)
                val y = yOf(p.lat).coerceIn(clipMinY, clipMaxY)
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (!anyKept) {
            // No segment intersects the clip (or degenerate single-point path): use the first point
            // so the grid is a valid 1×1 cell that returns MAX_VALUE for every far query.
            val p = pts.first()
            val x = xOf(p.lng); val y = yOf(p.lat)
            minX = x; maxX = x; minY = y; maxY = y
        }
        // Pad the grid by the tolerance so a fat bbox never indexes outside the array.
        minCx = cellX(minX - queryToleranceM)
        minCy = cellY(minY - queryToleranceM)
        val maxCx = cellX(maxX + queryToleranceM)
        val maxCy = cellY(maxY + queryToleranceM)
        cols = (maxCx - minCx + 1).coerceAtLeast(1)
        rows = (maxCy - minCy + 1).coerceAtLeast(1)

        // First pass: count segments per cell to size each IntArray exactly (no growth churn).
        val counts = IntArray(cols * rows)
        forEachSegmentCell(n) { idx -> counts[idx]++ }
        buckets = Array(cols * rows) { IntArray(counts[it]) }
        val fill = IntArray(cols * rows)
        forEachSegmentCellWithSeg(n) { idx, seg ->
            buckets[idx][fill[idx]++] = seg
        }
    }

    /** Linear cell index, or -1 if (cx, cy) is outside the grid. */
    private fun cellIndex(cx: Int, cy: Int): Int {
        val ox = cx - minCx; val oy = cy - minCy
        if (ox < 0 || oy < 0 || ox >= cols || oy >= rows) return -1
        return oy * cols + ox
    }

    /**
     * True when segment [i] (points[i]..points[i+1]) should be indexed: either there is no clip, or
     * the segment's lat/lng bbox intersects the clip box. A segment fully outside the clip is skipped
     * — coverage-identical because the caller fattened the clip by the tolerance (see [clip]).
     */
    private fun segmentKept(i: Int): Boolean {
        val c = clip ?: return true
        val a = pts[i]; val b = pts[i + 1]
        val segMinLat = minOf(a.lat, b.lat); val segMaxLat = maxOf(a.lat, b.lat)
        val segMinLng = minOf(a.lng, b.lng); val segMaxLng = maxOf(a.lng, b.lng)
        return segMinLat <= c.maxLat && segMaxLat >= c.minLat &&
            segMinLng <= c.maxLng && segMaxLng >= c.minLng
    }

    private inline fun forEachSegmentCell(n: Int, body: (Int) -> Unit) {
        forEachSegmentCellWithSeg(n) { idx, _ -> body(idx) }
    }

    /**
     * Visits every (cell, segment) pair where the segment's fat bbox (expanded by [queryToleranceM])
     * touches the cell. Registering against the FAT bbox guarantees any segment whose perpendicular
     * foot is within the tolerance of a query point is registered in the query's own cell.
     */
    private inline fun forEachSegmentCellWithSeg(n: Int, body: (Int, Int) -> Unit) {
        for (i in 0 until n - 1) {
            if (!segmentKept(i)) continue
            val a = pts[i]; val b = pts[i + 1]
            val ax = xOf(a.lng); val ay = yOf(a.lat)
            val bx = xOf(b.lng); val by = yOf(b.lat)
            val loX = minOf(ax, bx) - queryToleranceM
            val hiX = maxOf(ax, bx) + queryToleranceM
            val loY = minOf(ay, by) - queryToleranceM
            val hiY = maxOf(ay, by) + queryToleranceM
            val cx0 = cellX(loX); val cx1 = cellX(hiX)
            val cy0 = cellY(loY); val cy1 = cellY(hiY)
            for (cy in cy0..cy1) {
                for (cx in cx0..cx1) {
                    val idx = cellIndex(cx, cy)
                    if (idx >= 0) body(idx, i)
                }
            }
        }
    }

    /**
     * Perpendicular distance (m) to the nearest track segment, scanning only the 3×3 cell
     * neighbourhood around the query. Equals [PolylinePath.nearestPerpDistM] exactly whenever the
     * true nearest perpendicular distance is below [queryToleranceM]; otherwise returns some value
     * `>= queryToleranceM` (or [Double.MAX_VALUE] if no segment is registered nearby). Callers only
     * test `< tolerance`, so the looser far-field answer is harmless.
     */
    fun nearestPerpDistM(pLat: Double, pLng: Double): Double {
        val qx = xOf(pLng); val qy = yOf(pLat)
        val qcx = cellX(qx); val qcy = cellY(qy)
        var bestPerp = Double.MAX_VALUE
        // Scan 3×3 cells. Track which segments we've already evaluated to avoid recomputing a segment
        // registered in several neighbouring cells (cheap dedup via a small seen-set is unnecessary —
        // recomputation is harmless to correctness and the bucket sizes are tiny; we just recompute).
        for (cy in (qcy - 1)..(qcy + 1)) {
            for (cx in (qcx - 1)..(qcx + 1)) {
                val idx = cellIndex(cx, cy)
                if (idx < 0) continue
                val bucket = buckets[idx]
                for (seg in bucket) {
                    val perp = perpDistToSegment(pLat, pLng, seg)
                    if (perp < bestPerp) bestPerp = perp
                }
            }
        }
        return bestPerp
    }

    /**
     * Perpendicular distance from (pLat, pLng) to segment [seg] (points[seg]..points[seg+1]) using
     * the IDENTICAL local-equirectangular formula as [PolylinePath.nearestPerpDistM] (per-segment
     * scaling centred at the segment's first endpoint), so the value matches brute bit-for-bit.
     */
    private fun perpDistToSegment(pLat: Double, pLng: Double, seg: Int): Double {
        val a = pts[seg]; val b = pts[seg + 1]
        val segMPerDegLat = 111_320.0
        val segMPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * segMPerDegLng; val by = (b.lat - a.lat) * segMPerDegLat
        val px = (pLng - a.lng) * segMPerDegLng; val py = (pLat - a.lat) * segMPerDegLat
        val segLen2 = bx * bx + by * by
        val t = if (segLen2 == 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
        val fx = t * bx; val fy = t * by
        return sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
    }
}
