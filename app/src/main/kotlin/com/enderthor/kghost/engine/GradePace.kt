package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/** Persisted-model schema version. Bump when the binning or the reducer layout changes so an old blob is
 *  discarded and rebuilt instead of mis-read. */
const val GRADE_SCHEMA_VERSION = 1

/** Gradient bin width (%). 1% is finer than the noise floor of a smoothed barometric profile and keeps the
 *  table at 41 entries. */
const val GRADE_BIN_PCT = 1.0

/** Bin range. A LOOKUP outside +-20% is clamped into the end bin (a live 25% reading is still a wall);
 *  a BUILD-time gradient outside it is DROPPED, because beyond +-20% it is almost always an altitude
 *  glitch and clamping would pour that road's real pace into the end bin. */
const val GRADE_MAX_PCT = 20.0

/** Distance over which the gradient is measured. A 20 m decimated step gives a gradient dominated by
 *  altitude noise (a 1 m baro wobble over 20 m reads as 5%); 100 m is the shortest window where a real
 *  ramp still survives and the noise averages out. */
const val GRADE_WINDOW_M = 100.0

/** A bin must hold at least this much history before it is allowed to answer. Below it the estimate is a
 *  single lucky stretch, and a wrong fill pace is worse than no verdict (the caller falls through to the
 *  neutral fill, which contributes 0). */
const val GRADE_MIN_BIN_M = 400.0

/**
 * Global, route-independent map of "my historical pace at gradient X", built from every imported track that
 * carries altitude. It answers the question `PacePatch` cannot: what pace is normal FOR ME on a road I have
 * never ridden, given how steep it is right now.
 *
 * The tier below `PacePatch` and above the neutral fill:
 *   `PacePatch(here) ?: GradePace(gradient now) ?: neutral`
 *
 * Pure; no Android, no IO. Built once per import and persisted by `GradePaceStore`.
 */
class GradePace private constructor(private val bins: Map<Int, Reducer>) {

    class Reducer(
        var ema: Double = 0.0,
        var minTpm: Double = 0.0,
        var lastTpm: Double = 0.0,
        var metres: Double = 0.0,
        var count: Int = 0,
    )

    /** Total metres of history folded into the table (0 when no track carried altitude). */
    val coveredM: Double = bins.values.sumOf { it.metres }

    /**
     * Historical time-per-metre at [gradePct] for [pick], or null when that gradient holds less than
     * [GRADE_MIN_BIN_M] of history. Null is the signal for the caller to fall through to the neutral fill —
     * never substitute a neighbouring bin, which is how a flat pace ends up applied to a climb.
     */
    fun pace(gradePct: Double, pick: GhostPick): Double? {
        if (!gradePct.isFinite()) return null
        val r = bins[binOf(gradePct)] ?: return null
        if (r.metres < GRADE_MIN_BIN_M) return null
        return when (pick) {
            GhostPick.AVERAGE -> if (r.count >= AGG_MIN_LAPS) r.ema else r.lastTpm
            GhostPick.LAST -> r.lastTpm
            GhostPick.BEST -> maxOf(r.minTpm, r.ema / BEST_MAX_SPEEDUP)
        }
    }

    fun toDto(): GradePaceDto = GradePaceDto(
        schemaVersion = GRADE_SCHEMA_VERSION,
        bins = bins.map { (b, r) -> GradeBinDto(b, r.ema, r.minTpm, r.lastTpm, r.metres, r.count) }
            .sortedBy { it.bin },
    )

    companion object {
        fun binOf(gradePct: Double): Int =
            (gradePct.coerceIn(-GRADE_MAX_PCT, GRADE_MAX_PCT) / GRADE_BIN_PCT).roundToInt()

        fun fromDto(dto: GradePaceDto): GradePace = GradePace(
            dto.bins.associate { it.bin to Reducer(it.ema, it.minTpm, it.lastTpm, it.metres, it.count) }
        )

        /**
         * Folds every track that carries altitude into the table. Tracks are folded oldest-first so `last`
         * and the EMA carry the same "most recent wins" meaning they have in `PacePatch`/`CorridorSeeder`.
         *
         * The gradient of a step is measured over a TRAILING window of at least [GRADE_WINDOW_M] (a 20 m
         * step's own altitude delta is pure baro noise); the PACE comes from the step itself. Each track
         * contributes exactly ONE sample per bin — the `PacePatch` "one pass per track" rule — so `count`
         * still counts RIDES and a dense track still cannot outvote a decimated one over the same road.
         *
         * SANCTIONED DIVERGENCE from `PacePatch`: that sample is weighted by how many of this track's
         * metres landed in the bin, which `PacePatch` does not do. `PacePatch`'s cells are 18 m, so any
         * one track's contribution to a cell is inherently bounded; a `GradePace` bin is GLOBAL, so a
         * track's contribution ranges from 500 m to 200 km. Unweighted, a 5 km errand ride moved the flat
         * bin's average as much as a 200 km day. A track voting at least [GRADE_MIN_BIN_M] m in a bin still
         * gets full weight ([AGG_ALPHA] in the EMA phase); below that its vote scales down with its metres.
         */
        fun build(tracks: List<RecordedTrack>): GradePace {
            val map = HashMap<Int, Reducer>()
            for (track in tracks.sortedBy { it.startedAtEpoch }) {
                val pts = track.points
                if (pts.size < 2) continue
                // Per-bin totals for THIS track: seconds and metres, so the fold below emits one
                // metre-weighted sample per bin.
                val sumDt = HashMap<Int, Double>()
                val sumDd = HashMap<Int, Double>()
                var j = 0 // trailing index of the gradient window
                for (i in 1 until pts.size) {
                    val here = pts[i]
                    val prev = pts[i - 1]
                    // Advance the trailing edge FIRST so a skipped step can't strand it.
                    while (j < i - 1 && here.distanceM - pts[j + 1].distanceM >= GRADE_WINDOW_M) j++
                    val stepM = here.distanceM - prev.distanceM
                    val stepT = here.timeS - prev.timeS
                    if (stepM <= 0.0 || stepT <= 0.0) continue
                    if (stepM > TrackSamples.DROPOUT_GAP_M) continue // a device-off / tunnel jump, not riding
                    val stepSpeed = stepM / stepT
                    if (stepSpeed > AGG_MAX_SPEED_MS) continue       // a GPS spike, not riding
                    // Dwell clip over the STEP, exactly as TrackSamples does. Clipping over the 100 m window
                    // instead would let a 45 s red light inside a 100 m stretch read 1.74 m/s — above the
                    // floor, so the clip never fires and the flat bin records 0.575 s/m for a 0.125 s/m road.
                    val stepDt = if (stepSpeed < AGG_MIN_SPEED_MS) stepM / AGG_MIN_SPEED_MS else stepT
                    val ele = here.eleM ?: continue
                    val back = pts[j]
                    val backEle = back.eleM ?: continue
                    val dd = here.distanceM - back.distanceM
                    if (dd < GRADE_WINDOW_M) continue
                    // Reject a window that straddles a dropped gap: `j` is the newest point at least
                    // GRADE_WINDOW_M back, so on a contiguous track dd < GRADE_WINDOW_M + stepM, and every
                    // step longer than DROPOUT_GAP_M is already rejected above — so a legitimate dd can
                    // never exceed this. A bigger dd means the window's trailing edge is still sitting on
                    // the far side of a rejected gap, dividing the gap's altitude jump by the gap's
                    // distance and landing a bogus gradient (with the step's real pace) in the wrong bin.
                    if (dd > GRADE_WINDOW_M + TrackSamples.DROPOUT_GAP_M) continue
                    val gradePct = (ele - backEle) / dd * 100.0
                    // DROP an out-of-range gradient rather than clamping it into the end bin: a barometric
                    // reset reads as +45% and would otherwise pour real road pace into bin 20, mixing a GPS
                    // glitch with a genuine 20% wall. The lookup-side clamp in binOf stays — a live 25%
                    // reading legitimately maps to bin 20.
                    if (!gradePct.isFinite() || kotlin.math.abs(gradePct) > GRADE_MAX_PCT) continue
                    val bin = binOf(gradePct)
                    sumDt[bin] = (sumDt[bin] ?: 0.0) + stepDt
                    sumDd[bin] = (sumDd[bin] ?: 0.0) + stepM
                }
                for ((bin, dd) in sumDd) {
                    if (dd <= 0.0) continue
                    val tpm = (sumDt[bin] ?: continue) / dd
                    if (!tpm.isFinite()) continue
                    val r = map.getOrPut(bin) { Reducer() }
                    // Weight this track's vote by the metres it actually contributed to this bin — see the
                    // sanctioned-divergence note on `build` above. Full weight at/above the trust floor.
                    val w = AGG_ALPHA * min(1.0, dd / GRADE_MIN_BIN_M)
                    r.ema = when {
                        r.count == 0 -> tpm
                        r.count < AGG_SEED_LAPS -> (r.ema * r.metres + tpm * dd) / (r.metres + dd)
                        else -> w * tpm + (1.0 - w) * r.ema
                    }
                    r.minTpm = if (r.count == 0) tpm else min(r.minTpm, tpm)
                    r.lastTpm = tpm
                    r.metres += dd
                    r.count++
                }
            }
            return GradePace(map)
        }
    }
}

@Serializable
data class GradeBinDto(
    val bin: Int,
    val ema: Double,
    val minTpm: Double,
    val lastTpm: Double,
    val metres: Double,
    val count: Int,
)

@Serializable
data class GradePaceDto(val schemaVersion: Int, val bins: List<GradeBinDto>)
