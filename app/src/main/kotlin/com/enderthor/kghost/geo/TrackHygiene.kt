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

/** How far apart two tracks of the SAME ride may claim to have started. The two ingest paths begin
 *  counting at different moments — the live recorder at its first trusted fix, the FIT decoder at the
 *  Karoo's first positioned record — and a late lock or an auto-pause stretches that to minutes. */
const val SAME_RIDE_START_WINDOW_MS = 600_000L

/** Relative length agreement for two tracks to be the same ride. The two paths measure slightly
 *  different distances (wheel odometer vs FIT records); observed spreads were 10-80 m over 3-80 km. */
const val SAME_RIDE_LENGTH_TOL = 0.01

/** Absolute floor for the above, so a 3 km ride is not held to 30 m. */
const val SAME_RIDE_LENGTH_FLOOR_M = 100.0

/** Bumped whenever the archiving RULE changes in a way that should re-examine an already-swept
 *  library. The one-time backlog sweep re-runs once when the stored version is behind this. */
const val TIDY_RULE_VERSION = 1

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
    /** Which ingest path produced this track. The ONLY reliable way to tell "one ride stored twice"
     *  from "two rides of the same route": a duplicate always arrives once live and once from the
     *  Karoo's own FIT, whereas two genuine outings — including two laps minutes apart — arrive by
     *  the SAME path. Without it, start time and length alone would archive a real second lap.
     *  NOT defaulted on purpose: it was, and trackMetaOf's positional call then silently dropped it,
     *  leaving every production TrackMeta as RECORDED and the whole same-ride rule dead. */
    val source: Source,
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
    return TrackMeta(
        track.id, fine, dilated, fingerprintOf(pts, totalDist), totalDist, totalTime,
        track.startedAtEpoch, track.source,
    )
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
        // ONE RIDE STORED TWICE is not history, and the group-size rule below cannot see the difference.
        // A ride enters the library by two independent paths — the live recording, and a later scan of
        // the Karoo's own FIT — and the sourceKey meant to collapse them is minute-of-start plus distance
        // bucketed to 10 m, which is finer than the jitter between the two paths. Measured on a real
        // library: of 8 rides stored by both paths, ZERO shared a key (they drifted by 10-80 m, or by
        // 1-3 minutes). Those pairs form a twin group of 2, which the `<= 3` rule then protects forever,
        // so the ride is counted twice in every average for that route. Resolve them here, at any group
        // size, BEFORE the "keep at least three" rule gets to speak.
        val duplicates = sameRideLosers(group)
        if (duplicates.isNotEmpty()) result.addAll(duplicates)
        val distinctRides = group.filterNot { it.id in duplicates }
        if (distinctRides.size <= 3) continue // every member survives → nothing to archive
        val fastest = distinctRides.filter { it.isPlausible() }.minByOrNull { it.totalTimeS!! }
        val twoLatest = distinctRides.sortedByDescending { it.startedAtEpoch }.take(2)
        val survivors = (listOfNotNull(fastest) + twoLatest).toSet()
        val survivorDilated = HashSet<String>()
        survivors.forEach { survivorDilated.addAll(it.dilatedCells) }
        for (loser in distinctRides) {
            if (loser in survivors) continue
            if (loser.fineCells.all { it in survivorDilated }) result.add(loser.id)
        }
    }
    return result
}

/**
 * Within one twin group, the ids that are the SAME RIDE as another member and lose the tie.
 *
 * Twin-ness is geometric, so it cannot tell two rides of a route from one ride stored twice; start
 * time and length can. Two members are the same ride when they start within [SAME_RIDE_START_WINDOW_MS]
 * of each other AND their lengths agree to within [SAME_RIDE_LENGTH_TOL] (with a [SAME_RIDE_LENGTH_FLOOR_M]
 * floor so a short ride is not held to a metre). The window is wide enough for the observed skew between
 * the two ingest paths (up to ~3 min when the GPS locks late or the ride auto-pauses) and far short of
 * the gap between two genuine outings.
 *
 * The survivor is the LONGEST track — the most complete record of that ride — then the earliest start,
 * then the id, so the choice is deterministic and independent of scan order. A loser is only archived
 * when the survivor already covers every one of its fine cells, the same coverage guard the size rule
 * below uses: if the two disagree about where the rider actually went, both are kept.
 */
private fun sameRideLosers(group: List<TrackMeta>): Set<String> {
    if (group.size < 2) return emptySet()
    val recorded = group.filter { it.source == Source.RECORDED }.sortedBy { it.id }
    if (recorded.isEmpty()) return emptySet()
    val losers = HashSet<String>()
    val claimed = HashSet<String>()
    for (survivor in recorded) {
        // The LIVE recording always survives. It is the half that cannot be regenerated: a file-sourced
        // track is re-created from its FIT on the next scan, and archive/ has no restore action, so
        // choosing by "whichever measured longer" could archive the only copy of a ride. It also dodges
        // a trap: totalDistanceM is the field most corrupted by the coasting/dropout failure this rule
        // cleans up, so "longest" can systematically prefer the less truthful track.
        val candidate = group
            .filter { it.id !in claimed && isSameRide(survivor, it) }
            // NEAREST START, not list order. Pairing greedily by length let a RECORDED lap claim a
            // DIFFERENT lap's FIT — hill repeats are all "the same length" under the 100 m floor — and
            // archive a genuinely distinct ride.
            .minByOrNull { kotlin.math.abs(it.startedAtEpoch - survivor.startedAtEpoch) } ?: continue
        // Coverage guard: never drop a track that reaches ground the survivor does not.
        if (candidate.fineCells.all { it in survivor.dilatedCells }) {
            losers.add(candidate.id)
            claimed.add(candidate.id)
        }
    }
    return losers
}

private fun isSameRide(a: TrackMeta, b: TrackMeta): Boolean {
    // The pair must be exactly one live recording and one scan of THIS device's own FitFiles. That is
    // the load-bearing condition, not a refinement of the other two:
    //  - same-path pairs are real history — two laps of a short circuit start minutes apart with
    //    near-identical length, and under the 100 m floor they are always "the same length";
    //  - FIT_IMPORT is excluded on purpose. A FIT the rider dropped in the import directory can be
    //    ANOTHER rider's copy of the same group ride: same route, same hour, same distance, and
    //    indistinguishable from your own by geometry alone. Archiving that destroys a distinct
    //    activity. FITFILES_SCAN comes from the Karoo's own recordings, so it cannot be someone else's.
    //  - GPX_IMPORT likewise carries no promise of being this device's own ride.
    if (setOf(a.source, b.source) != setOf(Source.RECORDED, Source.FITFILES_SCAN)) return false
    if (kotlin.math.abs(a.startedAtEpoch - b.startedAtEpoch) > SAME_RIDE_START_WINDOW_MS) return false
    val longer = maxOf(a.totalDistanceM, b.totalDistanceM)
    if (longer <= 0.0) return false
    val tol = maxOf(SAME_RIDE_LENGTH_FLOOR_M, longer * SAME_RIDE_LENGTH_TOL)
    return kotlin.math.abs(a.totalDistanceM - b.totalDistanceM) <= tol
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
