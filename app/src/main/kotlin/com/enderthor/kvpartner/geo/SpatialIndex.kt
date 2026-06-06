package com.enderthor.kvpartner.geo

import kotlin.math.ceil
import kotlin.math.max

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
     * The box is sampled at its 4 corners plus an interior grid. The grid step is chosen so that
     * adjacent samples are never more than roughly half a cell apart (≈0.005° at precision 6),
     * which guarantees every cell the box spans is hit for boxes up to a few cells wide. Very large
     * boxes still work but cost more samples; see [maxSamplesPerAxis] for the cap.
     */
    fun cellsFor(bbox: BBox): Set<String> {
        val cells = HashSet<String>()

        // Sampling step in degrees. At precision 6 a cell is ~0.0055° tall / ~0.011° wide; using
        // ~0.005° keeps adjacent samples within half a cell so no spanned cell is skipped.
        val stepDeg = stepDegForPrecision(precision)

        val latSpan = max(0.0, bbox.maxLat - bbox.minLat)
        val lngSpan = max(0.0, bbox.maxLng - bbox.minLng)

        val latSteps = sampleCount(latSpan, stepDeg)
        val lngSteps = sampleCount(lngSpan, stepDeg)

        for (i in 0..latSteps) {
            val lat = if (latSteps == 0) bbox.minLat
            else bbox.minLat + (bbox.maxLat - bbox.minLat) * i / latSteps
            for (j in 0..lngSteps) {
                val lng = if (lngSteps == 0) bbox.minLng
                else bbox.minLng + (bbox.maxLng - bbox.minLng) * j / lngSteps
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

    /** Records [trackId] against every cell touched by [bbox]. */
    fun add(trackId: String, bbox: BBox) {
        for (cell in cellsFor(bbox)) {
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
         * Hard cap on samples per axis so a pathologically large box cannot blow up to a huge grid.
         * With the cap, boxes up to ~[maxSamplesPerAxis] × stepDeg wide are sampled densely; wider
         * boxes are sampled more coarsely but, since recall only over-includes, this stays safe for
         * candidate prefiltering (the precise matcher still rejects non-overlaps).
         */
        const val maxSamplesPerAxis: Int = 64

        /** Sampling step (degrees) sized to roughly half a geohash cell at the given precision. */
        private fun stepDegForPrecision(precision: Int): Double = when {
            precision <= 4 -> 0.1
            precision == 5 -> 0.02
            precision == 6 -> 0.005
            else -> 0.001
        }
    }
}
