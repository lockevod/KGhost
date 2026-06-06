package com.enderthor.kvpartner.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Google encoded-polyline decoder + spherical geometry helpers. */
object Polyline {
    private const val EARTH_R_M = 6_371_000.0

    /** Decodes a Google encoded polyline (precision 5) into coordinates. */
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var result = 0; var shift = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0; shift = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
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
}

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
     * Nearest projection of [p] onto the path. Uses an equirectangular local approximation for
     * the per-segment point→segment distance (accurate at the metre scale we care about), and
     * returns the cumulative distance-along of the projected foot point.
     */
    fun nearestProjection(p: LatLng): Projection =
        nearestProjectionInRange(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

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
    private fun nearestProjectionInRange(p: LatLng, windowLoM: Double, windowHiM: Double): Projection {
        var best = Projection(0.0, Double.MAX_VALUE, 0)
        for (i in 0 until points.size - 1) {
            val segLoM = cumulativeM[i]; val segHiM = cumulativeM[i + 1]
            // Skip segments fully outside the window.
            if (segHiM < windowLoM || segLoM > windowHiM) continue

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
