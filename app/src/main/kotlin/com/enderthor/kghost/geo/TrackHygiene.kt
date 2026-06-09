package com.enderthor.kghost.geo

import kotlin.math.abs
import kotlin.math.hypot

/** Fine geohash precision for the footprint + coverage guard (~38 m ≈ the matcher's 35 m tolerance). */
const val FINE_PRECISION = 8

/** Path-distance fractions sampled for the direction fingerprint. */
val FP_FRACTIONS = listOf(0.25, 0.50, 0.75)

/**
 * Two runs are the SAME route when each one's fine path cells are at least this fraction WITHIN the
 * other's DILATED footprint (jitter-tolerant containment, not raw set-Jaccard). Raw Jaccard at this
 * precision silently fails for real repeats: ±4 m consumer-GPS noise erodes ~15–20 % of edge cells, so
 * two laps of the same road score ~0.82. Comparing against the one-cell-dilated footprint absorbs that.
 */
const val TWIN_COVER = 0.90

/** ...and their total distances differ by at most this fraction. */
const val TWIN_LENGTH_TOL = 0.10

/** Max distance between corresponding fingerprint points for "same direction" (absorbs GPS drift). */
const val FP_TOL_M = 250.0

/** A track may be chosen "fastest" only if its implied avg speed is in this band (m/s). */
val MIN_PLAUSIBLE_MS = 0.5 / 3.6     // 0.5 km/h
val MAX_PLAUSIBLE_MS = 80.0 / 3.6    // 80 km/h

/** Per-track summary the hygiene selector needs. Built once by [trackMetaOf]; pure. */
data class TrackMeta(
    val id: String,
    val fineCells: Set<String>,          // precision-8 path cells (what this track must keep covered)
    val dilatedCells: Set<String>,       // fineCells grown by one cell ring (coverage this track PROVIDES)
    val dirFingerprint: List<LatLng>,    // points at 25/50/75 % of distance (direction, drift-tolerant)
    val totalDistanceM: Double,          // max cumulative distanceM (robust to a non-monotonic glitch)
    val totalTimeS: Double?,             // last point's cumulative timeS; null when missing / ≤ 0
    val startedAtEpoch: Long,
)

/** Build a [TrackMeta] from a stored track. Pure (geohash math only, no filesystem). */
fun trackMetaOf(track: RecordedTrack): TrackMeta {
    val pts = track.points
    val latLngs = pts.map { LatLng(it.lat, it.lng) }
    val index = SpatialIndex(FINE_PRECISION)
    val fine = index.cellsForPath(latLngs)
    val dilated = index.cellsForPathDilated(latLngs)
    // Use the MAX cumulative distance, not the last point's: a GPS glitch can make the last point's
    // distanceM drop below mid-track values, which would collapse all fingerprint fractions to one point.
    val totalDist = pts.maxOfOrNull { it.distanceM } ?: 0.0
    val totalTime = pts.lastOrNull()?.timeS?.takeIf { it.isFinite() && it > 0.0 }
    return TrackMeta(track.id, fine, dilated, fingerprintOf(pts, totalDist), totalDist, totalTime, track.startedAtEpoch)
}

/** The track's lat/lng at 25/50/75 % of its distance. Empty when the path is too short. */
private fun fingerprintOf(pts: List<TrackPointDto>, totalDist: Double): List<LatLng> {
    if (pts.size < 2 || totalDist <= 0.0) return emptyList()
    return FP_FRACTIONS.map { f ->
        val target = totalDist * f
        val p = pts.firstOrNull { it.distanceM >= target } ?: pts.last()
        LatLng(p.lat, p.lng)
    }
}

/** Approximate metres between two coordinates (equirectangular; accurate to well under 1 % at ~hundreds of m). */
private fun metersBetween(a: LatLng, b: LatLng): Double {
    val mPerDeg = 111_320.0
    val midLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
    val dy = (a.lat - b.lat) * mPerDeg
    val dx = (a.lng - b.lng) * mPerDeg * Math.cos(midLatRad)
    return hypot(dx, dy)
}

/** True when [b] is a near-identical twin of [a]: same length, same direction, same fine footprint. */
fun areTwins(a: TrackMeta, b: TrackMeta): Boolean {
    // Cheapest checks first so non-twins reject early (keeps the O(n²) sweep cheap-dominated).
    val maxLen = maxOf(a.totalDistanceM, b.totalDistanceM)
    if (maxLen <= 0.0) return false
    if (abs(a.totalDistanceM - b.totalDistanceM) / maxLen > TWIN_LENGTH_TOL) return false
    // Direction: corresponding fingerprint points must be close. A reverse ride swaps the 25/75 % points
    // (which are far apart on any real route) → those pairs blow past FP_TOL_M → rejected.
    if (a.dirFingerprint.size != 3 || b.dirFingerprint.size != 3) return false
    for (k in 0 until 3) if (metersBetween(a.dirFingerprint[k], b.dirFingerprint[k]) > FP_TOL_M) return false
    // Footprint: each track's path cells must be ~fully within the OTHER's dilated footprint (mutual
    // containment, jitter-tolerant). A detour adds cells outside the other's dilated set → coverage drops.
    if (a.fineCells.isEmpty() || b.fineCells.isEmpty()) return false
    val aCov = a.fineCells.count { it in b.dilatedCells }.toDouble() / a.fineCells.size
    val bCov = b.fineCells.count { it in a.dilatedCells }.toDouble() / b.fineCells.size
    return minOf(aCov, bCov) >= TWIN_COVER
}

/**
 * Given a set of candidate tracks (a coarse cluster, or the whole library), return the ids safe to
 * archive. Pure. (1) partition into twin-groups; (2) per group keep the fastest-plausible + the two
 * most recent; (3) archive a loser ONLY when every fine cell it covers is within the DILATED footprint
 * of this group's SURVIVORS — the coverage guard, evaluated PER GROUP (a survivor of an unrelated route
 * that merely crosses the same cell can never authorise the archive) and jitter-tolerant (dilated).
 */
fun selectArchivable(tracks: List<TrackMeta>): List<String> {
    if (tracks.size < 2) return emptyList()
    val result = ArrayList<String>()
    for (group in groupTwins(tracks)) {
        if (group.size <= 3) continue // every member survives → nothing to archive
        val fastest = group.filter { it.isPlausible() }.minByOrNull { it.totalTimeS!! }
        val twoLatest = group.sortedByDescending { it.startedAtEpoch }.take(2)
        val survivors = (listOfNotNull(fastest) + twoLatest).toSet()
        val survivorDilated = HashSet<String>()
        survivors.forEach { survivorDilated.addAll(it.dilatedCells) }
        for (loser in group) {
            if (loser in survivors) continue
            if (loser.fineCells.all { it in survivorDilated }) result.add(loser.id)
        }
    }
    return result
}

private fun TrackMeta.isPlausible(): Boolean {
    val t = totalTimeS ?: return false
    if (t <= 0.0) return false
    val v = totalDistanceM / t
    return v in MIN_PLAUSIBLE_MS..MAX_PLAUSIBLE_MS
}

/** Partition [tracks] into near-twin groups via union-find over [areTwins] (order-independent). */
private fun groupTwins(tracks: List<TrackMeta>): List<List<TrackMeta>> {
    val parent = IntArray(tracks.size) { it }
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var cur = x
        while (parent[cur] != cur) { val next = parent[cur]; parent[cur] = root; cur = next }
        return root
    }
    for (i in tracks.indices) {
        for (j in i + 1 until tracks.size) {
            if (areTwins(tracks[i], tracks[j])) parent[find(i)] = find(j)
        }
    }
    return tracks.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { tracks[it] } }
}
