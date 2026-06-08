package com.enderthor.kghost.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Google encoded-polyline decoder + spherical geometry helpers. */
object Polyline {
    private const val EARTH_R_M = 6_371_000.0

    /** Decodes a Google encoded polyline (precision 5) into coordinates. */
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = 10.0.pow(precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        // The `index < encoded.length` guards on each inner do/while (and the break after the lat
        // group) make this tolerant of a TRUNCATED/corrupt polyline: a well-formed varint always ends
        // on a char with b < 0x20, so on valid input these guards never trip early and behaviour is
        // unchanged — but a route polyline cut short by the host no longer walks past the string end
        // and throws StringIndexOutOfBoundsException (which would abort the whole route load).
        while (index < encoded.length) {
            var result = 0; var shift = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            if (index >= encoded.length) break // truncated mid-point: no lng group → drop the partial pair
            result = 0; shift = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            // If the lng varint exited still on a continuation byte (b >= 0x20), the loop stopped only
            // because the string ended mid-group → the longitude is incomplete. Drop the partial pair
            // instead of appending a point with a corrupted lng (symmetric with the lat break above).
            if (b >= 0x20) break
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    /** Great-circle distance in metres. */
    fun haversineM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial great-circle bearing from [a] to [b] in degrees, normalised to [0, 360). */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }
}

/** A sampled point along a [PolylinePath]: its coordinate and the heading (deg, 0..360) there. */
data class RouteSample(val location: LatLng, val bearingDeg: Double)

/** Result of projecting a point onto a [PolylinePath]. */
data class Projection(val distanceAlongM: Double, val perpDistM: Double, val vertexIndex: Int)

/** A decoded route path with precomputed cumulative distances and point→path projection. */
class PolylinePath(val points: List<LatLng>) {
    init { require(points.size >= 2) { "PolylinePath needs at least 2 points" } }

    val cumulativeM: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Polyline.haversineM(points[i - 1], points[i])
    }
    val totalM: Double get() = cumulativeM.last()

    /**
     * Point + heading at cumulative route distance [distanceAlongM] (metres), clamped to
     * `[0, totalM]`. Linearly interpolates the coordinate within the containing segment; the bearing
     * is that segment's initial bearing. Used to place a marker (e.g. a ghost) on the map at a known
     * distance along the route.
     */
    fun sampleAt(distanceAlongM: Double): RouteSample {
        val d = distanceAlongM.coerceIn(0.0, totalM)
        var i = 0
        while (i < points.size - 2 && cumulativeM[i + 1] < d) i++
        val a = points[i]; val b = points[i + 1]
        val segLen = cumulativeM[i + 1] - cumulativeM[i]
        val f = if (segLen > 0.0) ((d - cumulativeM[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        val lat = a.lat + f * (b.lat - a.lat)
        val lng = a.lng + f * (b.lng - a.lng)
        return RouteSample(LatLng(lat, lng), Polyline.bearingDeg(a, b))
    }

    /**
     * Nearest projection of [p] onto the path. Uses an equirectangular local approximation for
     * the per-segment point→segment distance (accurate at the metre scale we care about), and
     * returns the cumulative distance-along of the projected foot point.
     */
    fun nearestProjection(p: LatLng): Projection =
        nearestProjectionInRange(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

    /**
     * Allocation-free perpendicular distance (m) from the point ([pLat], [pLng]) to the nearest
     * segment of this path — the same value as `nearestProjection(LatLng(pLat, pLng)).perpDistM`,
     * but WITHOUT allocating a [LatLng] for the query or a [Projection] for the result.
     *
     * This is the hot path for the coverage scan in [SegmentMatcher]: it runs once per route
     * sample × per track (hundreds of thousands of times), and the only thing the caller needs is
     * "is the perpendicular distance below tolerance?". Returning a primitive `Double` here removes
     * the per-sample object churn that otherwise drives the GC storm.
     *
     * The arithmetic is byte-for-byte identical to [nearestProjectionInRange] over the full path
     * (infinite window), so the perpendicular distance it returns matches `nearestProjection`
     * exactly (proven by a differential test). Returns [Double.MAX_VALUE] only for the degenerate
     * empty path (cannot happen: the path requires >= 2 points).
     */
    fun nearestPerpDistM(pLat: Double, pLng: Double): Double {
        var bestPerp = Double.MAX_VALUE
        val pts = points
        val n = pts.size
        for (i in 0 until n - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
            val bx = (b.lng - a.lng) * mPerDegLng; val by = (b.lat - a.lat) * mPerDegLat
            val px = (pLng - a.lng) * mPerDegLng; val py = (pLat - a.lat) * mPerDegLat
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 == 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
            val fx = t * bx; val fy = t * by
            val perp = sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < bestPerp) bestPerp = perp
        }
        return bestPerp
    }

    /**
     * Windowed, forward-biased projection used for live progress on out-and-back / self-overlapping
     * routes. Only considers route segments whose cumulative-distance range intersects the window
     * `[aroundDistanceM - backWindowM, aroundDistanceM + fwdWindowM]`.
     *
     * On a route A→B→A a point on the shared road is geometrically valid on BOTH the outbound and
     * the return vertex ranges, so the unwindowed [nearestProjection] returns the GLOBAL minimum and
     * GPS noise flips the winning pass tick-to-tick. Constraining the search to a window centred on
     * the previously known route-distance keeps the projection on the CURRENT pass: the other pass'
     * vertex range lies outside the window and is never considered.
     *
     * The asymmetric window (small back, larger forward) reflects that the rider moves forward along
     * the route between fixes; the small back window tolerates GPS jitter without allowing a snap
     * back onto an earlier pass.
     *
     * If the window is empty (e.g. all segments fall outside it) this falls back to a global scan so
     * the caller always gets a valid projection.
     */
    fun nearestProjectionNear(
        p: LatLng,
        aroundDistanceM: Double,
        backWindowM: Double,
        fwdWindowM: Double,
    ): Projection {
        val lo = aroundDistanceM - backWindowM
        val hi = aroundDistanceM + fwdWindowM
        val windowed = nearestProjectionInRange(p, lo, hi)
        // Empty window (no segment intersected it) -> sentinel perpDist; fall back to global.
        return if (windowed.perpDistM == Double.MAX_VALUE) nearestProjection(p) else windowed
    }

    /**
     * Core projection scan restricted to segments whose `[cumulativeM[i], cumulativeM[i + 1]]`
     * range intersects `[windowLoM, windowHiM]`. With an infinite window this is the plain global
     * nearest projection.
     */
    /**
     * Index of the first segment `i` (in `0 until points.size - 1`) whose far end
     * `cumulativeM[i + 1] >= [windowLoM]` — i.e. the first segment that can intersect a window
     * starting at [windowLoM]. Lower-bound binary search over the non-decreasing `cumulativeM`
     * (searching `cumulativeM[1 .. size-1]`, the far ends). Returns `points.size - 1` (an empty loop
     * range) when every segment ends before [windowLoM]. For `windowLoM == NEGATIVE_INFINITY` this is
     * 0, leaving the global scan unchanged.
     */
    private fun firstSegmentFrom(windowLoM: Double): Int {
        // Search far ends cumulativeM[hi] for hi in 1 .. size-1 (segment index = hi - 1).
        var lo = 1
        var hi = points.size // exclusive
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulativeM[mid] >= windowLoM) hi = mid else lo = mid + 1
        }
        // lo is the smallest far-end index with cumulativeM[lo] >= windowLoM, or size if none.
        return (lo - 1).coerceAtMost(points.size - 1)
    }

    private fun nearestProjectionInRange(p: LatLng, windowLoM: Double, windowHiM: Double): Projection {
        var best = Projection(0.0, Double.MAX_VALUE, 0)
        // Window the scan: only segments whose `[cumulativeM[i], cumulativeM[i+1]]` intersects
        // `[windowLoM, windowHiM]` can win, exactly as the skip-condition below selects. Because
        // `cumulativeM` is non-decreasing, those segments form one contiguous index range, so we can
        // binary-search the first eligible index and BREAK past the last instead of touching every
        // segment. This visits the IDENTICAL set of candidate segments as the old full scan (which
        // `continue`d the out-of-window ones), so the resulting `best` is identical. For an infinite
        // window (the plain global projection) `start` is 0 and the break never fires -> unchanged.
        val start = firstSegmentFrom(windowLoM)
        for (i in start until points.size - 1) {
            val segLoM = cumulativeM[i]; val segHiM = cumulativeM[i + 1]
            // No later segment can intersect the window once its start passes windowHiM.
            if (segLoM > windowHiM) break
            // Defensive: this matches the old skip-condition exactly (always false for i >= start
            // except the infinite-window NEGATIVE_INFINITY case, where it is also false).
            if (segHiM < windowLoM) continue

            val a = points[i]; val b = points[i + 1]
            // Local metric plane (metres) centred at `a`.
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
            val ax = 0.0; val ay = 0.0
            val bx = (b.lng - a.lng) * mPerDegLng; val by = (b.lat - a.lat) * mPerDegLat
            val px = (p.lng - a.lng) * mPerDegLng; val py = (p.lat - a.lat) * mPerDegLat
            val segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (segLen2 == 0.0) 0.0 else (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2).coerceIn(0.0, 1.0)
            val fx = ax + t * (bx - ax); val fy = ay + t * (by - ay)
            val perp = sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < best.perpDistM) {
                val along = cumulativeM[i] + t * (cumulativeM[i + 1] - cumulativeM[i])
                best = Projection(along, perp, i)
            }
        }
        return best
    }
}
