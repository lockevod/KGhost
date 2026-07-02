package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/**
 * 2D historical-pace map keyed by ground cell + heading bin, built from the route area's tracks via the
 * shared [TrackSamples] generator. [pace] returns the chosen pick's per-metre time at a position+heading,
 * or null when there is no matching history (caller uses VP-fill). Pure; in-memory; rebuilt per route load.
 */
class PacePatch private constructor(
    private val refLat: Double,
    private val reducers: Map<Long, Reducer>,
) {
    private class Reducer {
        var ema = 0.0; var min = 0.0; var last = 0.0; var count = 0
        var sinB = 0.0; var cosB = 0.0
        fun meanBearingDeg(): Double = (Math.toDegrees(kotlin.math.atan2(sinB, cosB)) + 360.0) % 360.0
    }

    private val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
    private val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * cos(Math.toRadians(refLat)))

    fun pace(lat: Double, lng: Double, bearingDeg: Double, pick: GhostPick): Double? {
        if (!bearingDeg.isFinite()) return null // #7: no trustworthy direction → VP-fill, not max-count
        val ci = floor(lat / latStep).toInt(); val cj = floor(lng / lngStep).toInt(); val bb = bearingBin(bearingDeg)
        // Prefer the rider's OWN cell+bin (no distance loss); fall to the 3×3×3 neighbourhood only when it is
        // empty, and there reject any reducer whose real bearing is > BEARING_TOL_DEG from the rider's heading
        // (the ±1 bin window alone is ~135° wide — too loose; the 1D model gated at a precise 45°).
        //
        // The exact cell wins EVEN when it is statistically thin (count=1): a busier neighbour could be a
        // PARALLEL road (same bearing, one cell over), whose pace must NOT bleed onto the rider's actual road
        // — cell+bearing cannot tell a rich same-road neighbour from a rich parallel-road one, so the thin
        // exact cell (the RIGHT road) is the safer estimate. (This is the deferred "(C)" — left as-is by
        // design; "fixing" it regresses the parallel-road guard, see PacePatchTest.)
        val r = reducers[pack(ci, cj, bb)] ?: richestNeighbour(ci, cj, bb, bearingDeg) ?: return null
        return when (pick) {
            GhostPick.AVERAGE -> if (r.count >= AGG_MIN_LAPS) r.ema else r.last
            GhostPick.LAST -> r.last
            GhostPick.BEST -> maxOf(r.min, r.ema / BEST_MAX_SPEEDUP)
        }
    }

    /** Richest (highest-count) reducer in the 3×3×3 cell/bin box around ([ci],[cj],[bb]), excluding the
     *  centre, gated to ±BEARING_TOL_DEG of [bearingDeg] so a crossing/opposite road never matches. */
    private fun richestNeighbour(ci: Int, cj: Int, bb: Int, bearingDeg: Double): Reducer? {
        var best: Reducer? = null; var bestCount = 0
        for (di in -1..1) for (dj in -1..1) for (db in -1..1) {
            if (di == 0 && dj == 0 && db == 0) continue
            val rr = reducers[pack(ci + di, cj + dj, ((bb + db) % BEARING_BINS + BEARING_BINS) % BEARING_BINS)] ?: continue
            if (Polyline.bearingDiffDeg(rr.meanBearingDeg(), bearingDeg) > TrackSamples.BEARING_TOL_DEG) continue
            if (rr.count > bestCount) { best = rr; bestCount = rr.count }
        }
        return best
    }

    companion object {
        private const val BEARING_BINS = 8 // 45 deg each
        private fun bearingBin(deg: Double) = (((deg % 360 + 360) % 360) / 45.0).toInt().coerceIn(0, 7)
        // Bit budget: realistic cell indices |i|,|j| <= ~6e5 fit well within 29 bits; bin in 6 bits. No
        // collision in that range. Widen if a route could exceed ~2^28 cells (it can't on Earth at 18 m).
        private fun pack(i: Int, j: Int, b: Int): Long =
            ((i.toLong() and 0x1FFFFFFF) shl 35) or ((j.toLong() and 0x1FFFFFFF) shl 6) or (b.toLong() and 0x3F)

        fun build(tracks: List<RecordedTrack>): PacePatch {
            val refLat = tracks.firstOrNull()?.points?.firstOrNull()?.lat ?: 0.0
            val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
            val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * cos(Math.toRadians(refLat)))
            val map = HashMap<Long, Reducer>()
            val seen = HashMap<Long, MutableSet<String>>() // build-local; not retained
            for (track in tracks.sortedBy { it.startedAtEpoch }) {
                TrackSamples.forEach(track) { s ->
                    val key = pack(floor(s.lat / latStep).toInt(), floor(s.lng / lngStep).toInt(), bearingBin(s.bearingDeg))
                    if (!seen.getOrPut(key) { HashSet() }.add(s.trackId)) return@forEach // one pass per track per (cell,bin)
                    val r = map.getOrPut(key) { Reducer() }
                    val tpm = s.timePerM
                    r.ema = when {
                        r.count == 0 -> tpm
                        r.count < AGG_SEED_LAPS -> (r.ema * r.count + tpm) / (r.count + 1)
                        else -> AGG_ALPHA * tpm + (1.0 - AGG_ALPHA) * r.ema
                    }
                    r.min = if (r.count == 0) tpm else min(r.min, tpm)
                    r.last = tpm
                    r.sinB += kotlin.math.sin(Math.toRadians(s.bearingDeg))
                    r.cosB += kotlin.math.cos(Math.toRadians(s.bearingDeg))
                    r.count++
                }
            }
            return PacePatch(refLat, map)
        }
    }
}
