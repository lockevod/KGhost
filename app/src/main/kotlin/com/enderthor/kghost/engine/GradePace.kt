package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/** Persisted-model schema version. Bump when the binning or the reducer layout changes so an old blob is
 *  discarded and rebuilt instead of mis-read. Still 1 after the `ema` -> `meanTpm` rename: nothing has ever
 *  been persisted (the store lands in a later task), so there is no old blob in the field to discard. */
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
 * AVERAGE here is the rider's ALL-TIME, METRE-WEIGHTED pace at that gradient — every metre ever ridden in
 * the bin counts the same, no matter how old. There is deliberately NO recency weighting: recency is what
 * LAST exists for, and a pick that quietly drifts toward the last few rides is not an average.
 *
 * SANCTIONED DIVERGENCE from `PacePatch`/`CorridorSeeder`, which both use the EMA ladder: their cells are
 * 18 m, so a single track's contribution to a cell is inherently bounded and an EMA over per-track samples
 * is comparing like with like. A `GradePace` bin is GLOBAL — one track contributes anywhere from 500 m to
 * 200 km to the same bin — so an EMA over per-track samples lets a 5 km errand outweigh 400 km of history.
 * Do NOT "restore" the EMA here for consistency; it is wrong at this scale.
 *
 * Pure; no Android, no IO. Built once per import and persisted by `GradePaceStore`.
 */
class GradePace private constructor(private val bins: Map<Int, Reducer>) {

    class Reducer(
        /** Metre-weighted mean pace (s/m) over all history in this bin. */
        var meanTpm: Double = 0.0,
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
            // Below AGG_MIN_LAPS rides there is no average to speak of, so fall back to the lone ride —
            // same rule as PacePatch/RouteAggregate.
            GhostPick.AVERAGE -> if (r.count >= AGG_MIN_LAPS) r.meanTpm else r.lastTpm
            GhostPick.LAST -> r.lastTpm
            GhostPick.BEST -> maxOf(r.minTpm, r.meanTpm / BEST_MAX_SPEEDUP)
        }
    }

    fun toDto(): GradePaceDto = GradePaceDto(
        schemaVersion = GRADE_SCHEMA_VERSION,
        bins = bins.map { (b, r) -> GradeBinDto(b, r.meanTpm, r.minTpm, r.lastTpm, r.metres, r.count) }
            .sortedBy { it.bin },
    )

    companion object {
        fun binOf(gradePct: Double): Int =
            (gradePct.coerceIn(-GRADE_MAX_PCT, GRADE_MAX_PCT) / GRADE_BIN_PCT).roundToInt()

        fun fromDto(dto: GradePaceDto): GradePace = GradePace(
            dto.bins.associate { it.bin to Reducer(it.meanTpm, it.minTpm, it.lastTpm, it.metres, it.count) }
        )

        /**
         * Folds every track that carries altitude into the table. Tracks are folded oldest-first only so
         * that `lastTpm` means the most recent ride; the mean itself is order-independent.
         *
         * The gradient of a step is measured over a TRAILING window of at least [GRADE_WINDOW_M] (a 20 m
         * step's own altitude delta is pure baro noise); the PACE comes from the step itself. Each track
         * contributes exactly ONE sample per bin — the `PacePatch` "one pass per track" rule — so `count`
         * still counts RIDES and a dense track still cannot outvote a decimated one over the same road.
         *
         * That per-track sample updates a running METRE-WEIGHTED MEAN, weighted by the metres this track
         * actually contributed to the bin. See the class KDoc for why this diverges from `PacePatch`'s EMA
         * ladder on purpose: 18 m cells bound a track's contribution, a global gradient bin does not.
         */
        fun build(tracks: List<RecordedTrack>): GradePace =
            Builder().apply { tracks.forEach { add(it) } }.build()
    }

    /**
     * STREAMING builder: [add] one track at a time and [build] at the end, so a whole-library rebuild
     * never has to hold the library in heap (1500 rides x ~3000 points is hundreds of MB — an OOM on a
     * Karoo). All [add] keeps per track is its epoch plus its per-bin (seconds, metres) totals — at most
     * 41 bins, i.e. kilobytes for the whole library — because the final fold must run OLDEST-FIRST for
     * `lastTpm` to mean the most recent ride, and files are not listed in epoch order.
     *
     * Not thread-safe; drive it from one coroutine (the import job).
     */
    class Builder {
        private class Fold(val epoch: Long, val sumDt: Map<Int, Double>, val sumDd: Map<Int, Double>)

        private val folds = ArrayList<Fold>()

        fun add(track: RecordedTrack) {
            val pts = track.points
            if (pts.size < 2) return
            // Per-bin totals for THIS track: seconds and metres, so [build]'s fold emits one
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
                if (stepM > TrackSamples.DROPOUT_GAP_M) {
                    // A device-off / tunnel jump, not riding. RESTART the gradient window here: left
                    // alone, the trailing edge stays on the far side of the gap for the next ~100 m and
                    // divides the gap's whole altitude jump by (gap + a few steps), landing a bogus
                    // gradient — carrying the step's real, flat pace — in a steep bin. Costs the first
                    // GRADE_WINDOW_M after every gap, which is the right trade. Checked BEFORE the
                    // stepT guard below: a dropout's landing point can carry a duplicate/backward
                    // timestamp (stepT <= 0), and that must still restart the window — a timestamp
                    // fault must never suppress a distance-based dropout restart.
                    j = i
                    continue
                }
                if (stepM <= 0.0 || stepT <= 0.0) continue
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
                // No dd UPPER bound is needed: `j` never sits on the far side of a gap (the restart
                // above moves it past every one), and `j` is the newest point at least GRADE_WINDOW_M
                // back, so dd < GRADE_WINDOW_M + (the j->j+1 step), which is a non-gap step and hence
                // <= DROPOUT_GAP_M. The old `dd > GRADE_WINDOW_M + DROPOUT_GAP_M` guard was both
                // unreachable now and, before the restart, leaky for gaps of 200-300 m.
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
            if (sumDd.isNotEmpty()) folds.add(Fold(track.startedAtEpoch, sumDt, sumDd))
        }

        fun build(): GradePace {
            val map = HashMap<Int, Reducer>()
            // `sortedBy` is stable, so tracks sharing an epoch keep their add order — same as the old
            // list-based build, which sorted the caller's list the same way.
            for (fold in folds.sortedBy { it.epoch }) {
                for ((bin, dd) in fold.sumDd) {
                    if (dd <= 0.0) continue
                    val tpm = (fold.sumDt[bin] ?: continue) / dd
                    if (!tpm.isFinite()) continue
                    val r = map.getOrPut(bin) { Reducer() }
                    // Running metre-weighted mean: this track's vote counts exactly the metres it rode in
                    // the bin, against every metre already there. `r.metres` is read BEFORE the add below.
                    r.meanTpm =
                        if (r.count == 0) tpm else (r.meanTpm * r.metres + tpm * dd) / (r.metres + dd)
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
    val meanTpm: Double,
    val minTpm: Double,
    val lastTpm: Double,
    val metres: Double,
    val count: Int,
)

@Serializable
data class GradePaceDto(val schemaVersion: Int, val bins: List<GradeBinDto>)
