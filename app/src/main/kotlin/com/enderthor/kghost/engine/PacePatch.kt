package com.enderthor.kghost.engine

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
    private class Reducer { var ema = 0.0; var min = 0.0; var last = 0.0; var count = 0; val seen = HashSet<String>() }

    private val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
    private val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * cos(Math.toRadians(refLat)))

    fun pace(lat: Double, lng: Double, bearingDeg: Double, pick: GhostPick): Double? {
        val ci = floor(lat / latStep).toInt(); val cj = floor(lng / lngStep).toInt(); val bb = bearingBin(bearingDeg)
        var best: Reducer? = null; var bestCount = 0
        for (di in -1..1) for (dj in -1..1) for (db in -1..1) {
            val r = reducers[pack(ci + di, cj + dj, ((bb + db) % BEARING_BINS + BEARING_BINS) % BEARING_BINS)] ?: continue
            if (r.count > bestCount) { best = r; bestCount = r.count }
        }
        val r = best ?: return null
        return when (pick) {
            GhostPick.AVERAGE -> if (r.count >= AGG_MIN_LAPS) r.ema else r.last
            GhostPick.LAST -> r.last
            GhostPick.BEST -> maxOf(r.min, r.ema / BEST_MAX_SPEEDUP)
        }
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
            for (track in tracks.sortedBy { it.startedAtEpoch }) {
                TrackSamples.forEach(track) { s ->
                    val key = pack(floor(s.lat / latStep).toInt(), floor(s.lng / lngStep).toInt(), bearingBin(s.bearingDeg))
                    val r = map.getOrPut(key) { Reducer() }
                    if (s.trackId in r.seen) return@forEach // one pass per track per (cell,bin)
                    r.seen.add(s.trackId)
                    val tpm = s.timePerM
                    r.ema = when {
                        r.count == 0 -> tpm
                        r.count < AGG_SEED_LAPS -> (r.ema * r.count + tpm) / (r.count + 1)
                        else -> AGG_ALPHA * tpm + (1.0 - AGG_ALPHA) * r.ema
                    }
                    r.min = if (r.count == 0) tpm else min(r.min, tpm)
                    r.last = tpm
                    r.count++
                }
            }
            return PacePatch(refLat, map)
        }
    }
}
