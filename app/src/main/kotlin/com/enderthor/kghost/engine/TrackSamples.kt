package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import kotlin.math.ceil
import kotlin.math.max

/** One historical pace sample at a point: where (lat/lng), heading (deg), and pace (s per metre). */
data class PaceSample(
    val lat: Double, val lng: Double,
    val bearingDeg: Double, val timePerM: Double,
    val trackId: String, val epoch: Long,
)

/**
 * Pure per-segment sample generator shared by [CorridorSeeder] (1D route grid) and PacePatch (2D map):
 * applies the spike/dwell guards, skips GPS-dropout gaps and curve-collapse segments, and DENSIFIES each
 * segment into anchors at <= [ANCHOR_SPACING_M] along the geodesic chord, carrying per-metre time + bearing.
 */
object TrackSamples {
    const val MATCH_RADIUS_M = 18.0
    const val BEARING_TOL_DEG = 45.0
    const val ANCHOR_SPACING_M = 12.0
    const val DROPOUT_GAP_M = 200.0
    const val CURVE_RATIO_MAX = 1.5

    inline fun forEach(track: RecordedTrack, emit: (PaceSample) -> Unit) {
        val pts = track.points
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val d = b.distanceM - a.distanceM
            val dt = b.timeS - a.timeS
            if (d <= 0.0 || dt <= 0.0) continue
            val speed = d / dt
            if (speed > AGG_MAX_SPEED_MS) continue
            var timePerM = dt / d
            if (speed < AGG_MIN_SPEED_MS) timePerM = 1.0 / AGG_MIN_SPEED_MS
            if (!timePerM.isFinite()) continue
            val chord = Polyline.haversineM(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
            if (chord > DROPOUT_GAP_M) continue
            val bearing = Polyline.bearingDeg(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
            val subAnchors = if (d > chord * CURVE_RATIO_MAX) 1 else max(1, ceil(chord / ANCHOR_SPACING_M).toInt())
            for (sIdx in 1..subAnchors) {
                val f = sIdx.toDouble() / subAnchors
                emit(PaceSample(a.lat + f * (b.lat - a.lat), a.lng + f * (b.lng - a.lng), bearing, timePerM, track.id, track.startedAtEpoch))
            }
        }
    }
}
