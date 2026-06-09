package com.enderthor.kghost.geo

import kotlin.math.abs

/** Fine geohash precision for the footprint + coverage guard (~38 m × 19 m ≈ matcher tolerance). */
const val FINE_PRECISION = 8

/** Coarse precision for the direction fingerprint (~1.2 km; absorbs GPS drift, no neighbour logic). */
const val FINGERPRINT_PRECISION = 6

/** Two rides are the SAME route only when their fine footprints overlap at least this much. */
const val TWIN_JACCARD = 0.90

/** ...and their total distances differ by at most this fraction. */
const val TWIN_LENGTH_TOL = 0.10

/** A track may be chosen "fastest" only if its implied avg speed is in this band (m/s). */
val MIN_PLAUSIBLE_MS = 0.5 / 3.6     // 0.5 km/h
val MAX_PLAUSIBLE_MS = 80.0 / 3.6    // 80 km/h

/** Per-track summary the hygiene selector needs. Built once by [trackMetaOf]; pure. */
data class TrackMeta(
    val id: String,
    val fineCells: Set<String>,         // precision-8 path cells
    val dirFingerprint: List<String>,   // precision-6 cells at distance fractions 0.25 / 0.50 / 0.75
    val totalDistanceM: Double,         // last point's cumulative distanceM
    val totalTimeS: Double?,            // last point's cumulative timeS; null when missing / ≤ 0
    val startedAtEpoch: Long,
)

/** Build a [TrackMeta] from a stored track. Pure (geohash math only, no filesystem). */
fun trackMetaOf(track: RecordedTrack): TrackMeta {
    val pts = track.points
    val latLngs = pts.map { LatLng(it.lat, it.lng) }
    val fine = SpatialIndex(FINE_PRECISION).cellsForPath(latLngs)
    val totalDist = pts.lastOrNull()?.distanceM ?: 0.0
    val totalTime = pts.lastOrNull()?.timeS?.takeIf { it.isFinite() && it > 0.0 }
    return TrackMeta(track.id, fine, fingerprintOf(pts, totalDist), totalDist, totalTime, track.startedAtEpoch)
}

/** Precision-6 cells at 25/50/75 % of the path distance. Empty when the path is too short. */
private fun fingerprintOf(pts: List<TrackPointDto>, totalDist: Double): List<String> {
    if (pts.size < 2 || totalDist <= 0.0) return emptyList()
    return listOf(0.25, 0.50, 0.75).map { f ->
        val target = totalDist * f
        val p = pts.firstOrNull { it.distanceM >= target } ?: pts.last()
        geohash(p.lat, p.lng, FINGERPRINT_PRECISION)
    }
}

/** True when [b] is a near-identical twin of [a]: same length, same direction, same fine footprint. */
fun areTwins(a: TrackMeta, b: TrackMeta): Boolean {
    // Cheapest checks first so non-twins reject in O(1) (keeps the O(n²) sweep cheap-dominated).
    val maxLen = maxOf(a.totalDistanceM, b.totalDistanceM)
    if (maxLen <= 0.0) return false
    if (abs(a.totalDistanceM - b.totalDistanceM) / maxLen > TWIN_LENGTH_TOL) return false
    if (a.dirFingerprint.size != 3 || a.dirFingerprint != b.dirFingerprint) return false
    val inter = a.fineCells.count { it in b.fineCells }
    val union = a.fineCells.size + b.fineCells.size - inter
    if (union == 0) return false
    return inter.toDouble() / union.toDouble() >= TWIN_JACCARD
}
