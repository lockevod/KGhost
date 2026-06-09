package com.enderthor.kghost.geo

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Axis-aligned WGS84 bounding box. Latitudes/longitudes are in degrees.
 *
 * An "empty" box (one that has never been expanded) is represented by inverted bounds
 * (min > max) so the first [expand] call always replaces them.
 */
data class BBox(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
) {
    /** Returns a new box grown so it also contains ([lat], [lng]). */
    fun expand(lat: Double, lng: Double): BBox = BBox(
        minLat = minOf(minLat, lat),
        maxLat = maxOf(maxLat, lat),
        minLng = minOf(minLng, lng),
        maxLng = maxOf(maxLng, lng),
    )

    /** True if this box and [other] share any area (touching edges count as intersecting). */
    fun intersects(other: BBox): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLng <= other.maxLng && maxLng >= other.minLng

    companion object {
        /** An empty box (inverted bounds) ready to be grown with [expand]. */
        fun empty(): BBox = BBox(
            minLat = Double.POSITIVE_INFINITY,
            maxLat = Double.NEGATIVE_INFINITY,
            minLng = Double.POSITIVE_INFINITY,
            maxLng = Double.NEGATIVE_INFINITY,
        )

        /** Builds the tight box around a sequence of points. Returns null if [points] is empty. */
        fun around(points: Iterable<LatLng>): BBox? {
            var box = empty()
            var any = false
            for (p in points) {
                box = box.expand(p.lat, p.lng)
                any = true
            }
            return if (any) box else null
        }
    }
}

/** The classic base-32 geohash alphabet (note: 'a', 'i', 'l', 'o' are excluded). */
private const val GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

/**
 * Standard base-32 geohash encoder (pure, no Android).
 *
 * Interleaves longitude and latitude bits (longitude takes the first/even bit), packs every
 * 5 bits into one base-32 character, and emits [precision] characters.
 *
 * Precision 6 yields cells of roughly 1.2 km × 0.6 km.
 */
fun geohash(lat: Double, lng: Double, precision: Int): String {
    require(precision > 0) { "precision must be positive, was $precision" }
    var latMin = -90.0
    var latMax = 90.0
    var lngMin = -180.0
    var lngMax = 180.0

    val sb = StringBuilder(precision)
    var isEvenBit = true // even bit -> longitude, odd bit -> latitude
    var bit = 0
    var ch = 0

    while (sb.length < precision) {
        if (isEvenBit) {
            val mid = (lngMin + lngMax) / 2.0
            if (lng >= mid) {
                ch = (ch shl 1) or 1
                lngMin = mid
            } else {
                ch = ch shl 1
                lngMax = mid
            }
        } else {
            val mid = (latMin + latMax) / 2.0
            if (lat >= mid) {
                ch = (ch shl 1) or 1
                latMin = mid
            } else {
                ch = ch shl 1
                latMax = mid
            }
        }
        isEvenBit = !isEvenBit

        if (bit < 4) {
            bit++
        } else {
            sb.append(GEOHASH_BASE32[ch])
            bit = 0
            ch = 0
        }
    }
    return sb.toString()
}

/**
 * Coarse geohash grid used to prune the set of recorded tracks that could overlap a query box.
 *
 * Each track is registered against every geohash cell its bounding box touches. A later query box
 * is reduced to the same set of cells and the union of their track ids is returned as candidates.
 * This is a recall-oriented prefilter: it never drops a genuine overlap, but may return extra
 * candidates that a precise matcher (Task 5) then rejects.
 *
 * The backing store is exposed as a plain [Map] so it can be serialized with kotlinx.serialization
 * by [snapshot] and restored with [addAll] or the snapshot constructor.
 *
 * @param precision geohash precision; precision 6 ≈ 1.2 km cells (the default).
 */
class SpatialIndex(val precision: Int = 6) {

    private val cellToTracks: MutableMap<String, MutableSet<String>> = mutableMapOf()

    constructor(precision: Int, snapshot: Map<String, Set<String>>) : this(precision) {
        addAll(snapshot)
    }

    /**
     * Returns the geohash cells that [bbox] touches.
     *
     * The box is sampled at its 4 corners plus an interior grid. The grid step is exactly half the
     * geohash cell size at [precision], derived from the bit-interleaving geometry:
     *   - bits = 5 * precision; lngBits = ceil(bits/2); latBits = floor(bits/2)
     *   - cellHeightDeg = 180 / 2^latBits; cellWidthDeg = 360 / 2^lngBits
     *   - stepLat = cellHeightDeg / 2; stepLng = cellWidthDeg / 2
     *
     * Because the step is exactly half a cell, every cell the bbox touches contains at least one
     * sample point — recall is exact for any realistic route bbox regardless of its span. The only
     * remaining cap ([maxSamplesPerAxis] = 4096) is a runaway guard for degenerate planet-scale
     * inputs; no realistic route bbox (even 300 km × 300 km) approaches it.
     */
    fun cellsFor(bbox: BBox): Set<String> {
        val cells = HashSet<String>()

        // Derive the actual geohash cell size at this precision from the bit-interleaving geometry.
        val bits = 5 * precision
        val lngBits = ceil(bits / 2.0).toInt()
        val latBits = floor(bits / 2.0).toInt()
        val cellHeightDeg = 180.0 / 2.0.pow(latBits)
        val cellWidthDeg  = 360.0 / 2.0.pow(lngBits)

        // Step by half a cell so every touched cell contains at least one sample.
        val stepLat = cellHeightDeg / 2.0
        val stepLng = cellWidthDeg  / 2.0

        val latSpan = max(0.0, bbox.maxLat - bbox.minLat)
        val lngSpan = max(0.0, bbox.maxLng - bbox.minLng)

        val latSteps = sampleCount(latSpan, stepLat)
        val lngSteps = sampleCount(lngSpan, stepLng)

        for (i in 0..latSteps) {
            val lat = if (latSteps == 0) bbox.minLat
            else (bbox.minLat + stepLat * i).coerceAtMost(bbox.maxLat)
            for (j in 0..lngSteps) {
                val lng = if (lngSteps == 0) bbox.minLng
                else (bbox.minLng + stepLng * j).coerceAtMost(bbox.maxLng)
                cells.add(geohash(lat, lng, precision))
            }
        }

        // Always include the four corners explicitly (covers the degenerate single-point box too).
        cells.add(geohash(bbox.minLat, bbox.minLng, precision))
        cells.add(geohash(bbox.minLat, bbox.maxLng, precision))
        cells.add(geohash(bbox.maxLat, bbox.minLng, precision))
        cells.add(geohash(bbox.maxLat, bbox.maxLng, precision))

        return cells
    }

    /**
     * Returns the geohash cells a PATH actually passes through: the union, over each consecutive
     * point pair (segment), of [cellsFor] applied to that segment's tight bounding box.
     *
     * For decimated tracks (~20 m segments) each segment's bbox ≈ the segment itself, so this is a
     * TIGHT set of the cells the path crosses — unlike `cellsFor(BBox.around(points))`, which returns
     * the whole rectangular hull and over-counts cells the path never rode (e.g. an L-shaped commute
     * whose bbox blankets cells between its two legs). Indexing tracks by these path cells makes both
     * candidate pruning and overlap ranking reflect REAL route overlap.
     *
     * Degenerate inputs: an empty list yields no cells (the track is never a candidate, mirroring the
     * old null-bbox case); a single point yields exactly its one cell.
     */
    fun cellsForPath(points: List<LatLng>): Set<String> {
        if (points.isEmpty()) return emptySet()
        if (points.size == 1) return setOf(geohash(points[0].lat, points[0].lng, precision))
        val cells = HashSet<String>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            BBox.around(listOf(a, b))?.let { cells.addAll(cellsFor(it)) }
        }
        return cells
    }

    /**
     * Like [cellsForPath] but each segment's bounding box is grown by ONE cell in every direction
     * before sampling — a Minkowski dilation by ~one cell. The result is a SUPERSET of [cellsForPath]
     * (the un-grown bbox is contained in the grown one) that absorbs ~one cell of lateral GPS jitter:
     * a point that wobbles into a neighbouring cell still lands inside the dilated footprint. Used for
     * jitter-tolerant overlap (twin grouping) and the coverage guard — the raw [cellsForPath] of two
     * runs of the SAME road differ by ~15–20 % of edge cells under ±4 m consumer-GPS noise, so a raw
     * set-equality / subset test silently fails to match real repeats.
     */
    fun cellsForPathDilated(points: List<LatLng>): Set<String> {
        if (points.isEmpty()) return emptySet()
        val (dLat, dLng) = cellDims()
        if (points.size == 1) {
            val p = points[0]
            return cellsFor(BBox(p.lat - dLat, p.lat + dLat, p.lng - dLng, p.lng + dLng))
        }
        val cells = HashSet<String>()
        for (i in 0 until points.size - 1) {
            val box = BBox.around(listOf(points[i], points[i + 1])) ?: continue
            cells.addAll(cellsFor(BBox(box.minLat - dLat, box.maxLat + dLat, box.minLng - dLng, box.maxLng + dLng)))
        }
        return cells
    }

    /** This precision's geohash cell size in degrees as (heightDeg, widthDeg). */
    private fun cellDims(): Pair<Double, Double> {
        val bits = 5 * precision
        val lngBits = ceil(bits / 2.0).toInt()
        val latBits = floor(bits / 2.0).toInt()
        return (180.0 / 2.0.pow(latBits)) to (360.0 / 2.0.pow(lngBits))
    }

    /** Records [trackId] against every cell touched by [bbox]. */
    fun add(trackId: String, bbox: BBox) {
        add(trackId, cellsFor(bbox))
    }

    /** Records [trackId] against the given precomputed [cells] (e.g. from [cellsForPath]). */
    fun add(trackId: String, cells: Set<String>) {
        for (cell in cells) {
            cellToTracks.getOrPut(cell) { mutableSetOf() }.add(trackId)
        }
    }

    /** Returns the union of track ids registered in any cell that [bbox] touches. */
    fun candidates(bbox: BBox): Set<String> {
        val result = HashSet<String>()
        for (cell in cellsFor(bbox)) {
            cellToTracks[cell]?.let { result.addAll(it) }
        }
        return result
    }

    /** Immutable, serializable snapshot of the cell → track-ids store. */
    fun snapshot(): Map<String, Set<String>> =
        cellToTracks.mapValues { (_, v) -> v.toSet() }

    /** Merges a previously taken [snapshot] into this index. */
    fun addAll(snapshot: Map<String, Set<String>>) {
        for ((cell, ids) in snapshot) {
            cellToTracks.getOrPut(cell) { mutableSetOf() }.addAll(ids)
        }
    }

    private fun sampleCount(spanDeg: Double, stepDeg: Double): Int {
        if (spanDeg <= 0.0) return 0
        val count = ceil(spanDeg / stepDeg).toInt()
        return count.coerceIn(1, maxSamplesPerAxis)
    }

    companion object {
        /**
         * Runaway guard: maximum samples per axis. At half-cell stepping this is never reached by
         * any realistic route bbox — a 300 km × 300 km box at precision 6 produces ≈ 800 × 400
         * samples, well below this limit. The cap exists solely to protect against degenerate
         * planet-scale inputs (e.g. minLat = -90, maxLat = 90).
         */
        const val maxSamplesPerAxis: Int = 4096
    }
}
