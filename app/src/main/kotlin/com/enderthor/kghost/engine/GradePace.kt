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

/** Bins outside +-20% are clamped into the end bins: beyond that a bike is walking or braking, and the
 *  handful of samples there are almost always altitude glitches. */
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
         * For each point the gradient is measured over a TRAILING window of at least [GRADE_WINDOW_M] and
         * the pace over that same window, so both describe the same stretch of road. The reducer is weighted
         * by the metres of the step, not by the number of points, so a coarse track and a dense one over the
         * same road count the same.
         */
        fun build(tracks: List<RecordedTrack>): GradePace {
            val map = HashMap<Int, Reducer>()
            for (track in tracks.sortedBy { it.startedAtEpoch }) {
                val pts = track.points
                if (pts.size < 2) continue
                var j = 0 // trailing index of the window
                for (i in 1 until pts.size) {
                    val here = pts[i]
                    if (here.eleM == null) continue
                    // Advance the trailing edge to the newest point that is still >= GRADE_WINDOW_M behind.
                    while (j < i - 1 && here.distanceM - pts[j + 1].distanceM >= GRADE_WINDOW_M) j++
                    val back = pts[j]
                    val backEle = back.eleM ?: continue
                    val dd = here.distanceM - back.distanceM
                    val dt = here.timeS - back.timeS
                    if (dd < GRADE_WINDOW_M || dt <= 0.0) continue
                    val speed = dd / dt
                    if (speed > AGG_MAX_SPEED_MS) continue // corrupt: a GPS jump, not riding
                    var tpm = dt / dd
                    if (speed < AGG_MIN_SPEED_MS) tpm = 1.0 / AGG_MIN_SPEED_MS // clamp a dwell, don't drop it
                    if (!tpm.isFinite()) continue
                    val gradePct = (here.eleM - backEle) / dd * 100.0
                    if (!gradePct.isFinite()) continue
                    // Weight by THIS step's metres (the window overlaps between consecutive points; the step
                    // is what each point actually adds).
                    val stepM = here.distanceM - pts[i - 1].distanceM
                    if (stepM <= 0.0) continue
                    val r = map.getOrPut(binOf(gradePct)) { Reducer() }
                    r.ema = when {
                        r.count == 0 -> tpm
                        r.count < AGG_SEED_LAPS -> (r.ema * r.count + tpm) / (r.count + 1)
                        else -> AGG_ALPHA * tpm + (1.0 - AGG_ALPHA) * r.ema
                    }
                    r.minTpm = if (r.count == 0) tpm else min(r.minTpm, tpm)
                    r.lastTpm = tpm
                    r.metres += stepM
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
