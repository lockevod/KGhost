package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/** Persisted-model schema version. Bump when the binning, the reducer layout, the INPUT SET or any constant
 *  [GradePace.foldOf] reads changes, so the next import run owes a rebuild (`GradePaceStore.isCurrent`); an
 *  older model keeps being served meanwhile only down to `GradePaceStore.MIN_SERVABLE_SCHEMA` (raise that
 *  when the DTO or a field's meaning changes). The per-file fold cache carries this version AND
 *  [GRADE_FOLD_SIGNATURE], so a changed fold constant invalidates cached folds even without a bump.
 *  2: the model counts the archive too, one contribution per physical ride — the bump makes the first run
 *  after the upgrade rebuild once over the whole archive (the one-time history recovery).
 *  3: the home-drift gate ([GradePace.isHomeDrift]) empties drift sessions; the DTO is unchanged. */
const val GRADE_SCHEMA_VERSION = 3

/** Every constant [GradePace.foldOf] reads, stamped into the fold cache: change one and every cached fold is
 *  recomputed. Add any new foldOf input here. */
internal val GRADE_FOLD_SIGNATURE: String =
    "bin=$GRADE_BIN_PCT max=$GRADE_MAX_PCT win=$GRADE_WINDOW_M gap=${TrackSamples.DROPOUT_GAP_M} " +
        "v=$AGG_MIN_SPEED_MS..$AGG_MAX_SPEED_MS " +
        "drift=$DRIFT_FOOTPRINT_M/$DRIFT_MAX_TURN_DEG/$DRIFT_MIN_CHORD_M/$DRIFT_MIN_PAIRS"

/** Home-drift gate ([GradePace.isHomeDrift]): a track whose robust footprint (95th-percentile distance of its
 *  points from their per-axis median point) is under this many metres may be drift... */
const val DRIFT_FOOTPRINT_M = 1000.0

/** ...and IS drift when the median turn between consecutive qualifying steps exceeds this. Genuine rides
 *  measured on the real library: median turn p50 8 deg, max 12 deg; drift sessions p50 63 deg. */
const val DRIFT_MAX_TURN_DEG = 30.0

/** A step only carries a heading when its GEOGRAPHIC chord is at least this long (shorter is GPS noise). */
const val DRIFT_MIN_CHORD_M = 5.0

/** Below this many qualifying heading pairs there is no verdict, and the track is kept. */
const val DRIFT_MIN_PAIRS = 20

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
 * "ALL-TIME" means every track file with altitude in the live dir AND `archive/`, one contribution per
 * physical ride (see `GradePaceStore.rebuild` for the duplicate suppression and its accepted residuals).
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

    /** Can [pace] answer ANY gradient? `coveredM > 0` is not enough: a model of sub-[GRADE_MIN_BIN_M]
     *  slivers answers nothing, yet would keep a sparse route off the target race. */
    val hasUsableBin: Boolean = bins.values.any { it.metres >= GRADE_MIN_BIN_M }

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

        /** One track's per-bin (seconds, metres) totals; empty maps when it carries no usable altitude or is
         *  home drift ([isHomeDrift]) — gated HERE so the per-file fold cache stores the empty result. */
        fun foldOf(track: RecordedTrack): Fold {
            val pts = track.points
            if (isHomeDrift(pts)) return Fold(track.startedAtEpoch, emptyMap(), emptyMap())
            // Per-bin totals for THIS track: seconds and metres, so [reduce] emits one metre-weighted
            // sample per bin.
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
                if (stepSpeed > AGG_MAX_SPEED_MS) {
                    // A GPS spike (30-200 m in one step): not riding, so it is no pace sample — and, like a
                    // dropout, it must RESTART the window. Left alone the spike's metres stay in `dd` for the
                    // next ~100 m and dilute the gradient ~2.5x, filing an 8% climb's crawl in bin +3.
                    j = i
                    continue
                }
                // A DWELL (red light, cafe, a photo) is DROPPED, not clipped to the floor: a stop says
                // nothing about pace-at-gradient, and a GradePace bin is GLOBAL — bin 0 holds 80-90% of a
                // rider's metres, so a clipped city stop moves the pace used on a country lane. (`PacePatch`
                // can afford the clip: its 18 m cells keep the poison at the junction it happened in.)
                if (stepSpeed < AGG_MIN_SPEED_MS) continue
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
                sumDt[bin] = (sumDt[bin] ?: 0.0) + stepT
                sumDd[bin] = (sumDd[bin] ?: 0.0) + stepM
            }
            return Fold(track.startedAtEpoch, sumDt, sumDd)
        }

        /**
         * HOME DRIFT: a Karoo left on at home (or on a café table) records GPS jitter that the distance
         * stream turns into kilometres of "riding" at plausible speeds, flat — it inflated the flat bin's
         * metres and could become LAST. Speed, footprint or altitude alone cannot tell it from slow riding;
         * HEADING COHERENCE can: riding — a small circuit, hill repeats, a velodrome — turns a little from
         * step to step, jitter points anywhere. A track is drift iff BOTH:
         *  1. its robust footprint (95th-percentile haversine distance of its points from the per-axis
         *     MEDIAN point) is under [DRIFT_FOOTPRINT_M] — robust, so one far-off fix cannot hide it; and
         *  2. the median |Δbearing| between consecutive QUALIFYING steps (geographic chord >=
         *     [DRIFT_MIN_CHORD_M]) exceeds [DRIFT_MAX_TURN_DEG]. Headings are never paired across a step
         *     [foldOf] rejects as a dropout or a speed spike; the pairing restarts after it. Fewer than
         *     [DRIFT_MIN_PAIRS] pairs: no verdict, kept.
         * Accepted residuals (user decision 2026-10-11): a session circling a loop smaller than ~80 m across
         * (radius < ~40 m at the 20 m stored decimation -> a > 30 deg turn per chord), e.g. a very compact
         * pump track, is excluded. Coherent back-and-forth drift along a line is NOT caught (no worse than
         * before the gate).
         */
        internal fun isHomeDrift(pts: List<TrackPointDto>): Boolean {
            val turns = ArrayList<Double>()
            var lastBearing = Double.NaN
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val stepM = b.distanceM - a.distanceM
                val stepT = b.timeS - a.timeS
                if (stepM > TrackSamples.DROPOUT_GAP_M || (stepT > 0.0 && stepM / stepT > AGG_MAX_SPEED_MS)) {
                    lastBearing = Double.NaN
                    continue
                }
                val la = LatLng(a.lat, a.lng)
                val lb = LatLng(b.lat, b.lng)
                if (Polyline.haversineM(la, lb) < DRIFT_MIN_CHORD_M) continue
                val bearing = Polyline.bearingDeg(la, lb)
                if (!lastBearing.isNaN()) turns += Polyline.bearingDiffDeg(lastBearing, bearing)
                lastBearing = bearing
            }
            if (turns.size < DRIFT_MIN_PAIRS || turns.sorted()[turns.size / 2] <= DRIFT_MAX_TURN_DEG) return false
            return robustFootprintM(pts) < DRIFT_FOOTPRINT_M
        }

        /** 95th-percentile haversine distance of [pts] from their per-axis median point. */
        internal fun robustFootprintM(pts: List<TrackPointDto>): Double {
            if (pts.isEmpty()) return 0.0
            val centre = LatLng(pts.map { it.lat }.sorted()[pts.size / 2], pts.map { it.lng }.sorted()[pts.size / 2])
            val d = pts.map { Polyline.haversineM(centre, LatLng(it.lat, it.lng)) }.sorted()
            return d[ceil(0.95 * d.size).toInt() - 1]
        }

        /**
         * THE reduce — the only one: [Builder.build] and the cached whole-history rebuild
         * (`GradePaceStore.rebuild`) both end here, so the two paths cannot drift. Folds run OLDEST-FIRST
         * for `lastTpm` to mean the most recent ride; `sortedBy` is stable, so folds sharing an epoch keep
         * the caller's order.
         */
        fun reduce(folds: List<Fold>): GradePace {
            val map = HashMap<Int, Reducer>()
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

    /** One ride's contribution: its epoch plus its per-bin (seconds, metres) totals (at most 41 bins). */
    class Fold(val epoch: Long, val sumDt: Map<Int, Double>, val sumDd: Map<Int, Double>) {
        val isEmpty: Boolean get() = sumDd.isEmpty()
        val totalM: Double get() = sumDd.values.sum()
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
        private val folds = ArrayList<Fold>()
        private var built = false

        fun add(track: RecordedTrack) {
            check(!built) { "GradePace.Builder is single-use; add() after build() would be ignored" }
            val fold = foldOf(track)
            if (!fold.isEmpty) folds.add(fold)
        }

        /**
         * SINGLE-USE: the folds are not consumed by the fold below, so a second call on the same builder
         * would count every track twice (doubling `count`, halving nothing else) and silently produce a
         * model that claims twice the rides it has. Build a fresh [Builder] instead.
         */
        fun build(): GradePace {
            check(!built) { "GradePace.Builder is single-use; create a new Builder for another model" }
            built = true
            return reduce(folds)
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
