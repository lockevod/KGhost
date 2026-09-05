package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ADVERSARIAL MEASUREMENT §2-§4: leave-one-ride-out lookup coverage of tier 2 (GradePace) on a NOVEL
 * ride, head to head against the trivial alternative (one global historical pace), and the profiles
 * where tier 2 answers essentially never.
 *
 * Every number is metre-weighted, because the gap the rider sees is an integral over metres.
 */
class Adv2CoverLookupTest {

    /** One decimated step of a held-out ride, with everything the three tiers need to decide. */
    private class Step(
        val track: Int, val m: Double, val t: Double, val bin: Int,
        val tier1Strict: Boolean, val tier1Loose: Boolean, val gradeKnown: Boolean,
    )

    private class Model(val binT: DoubleArray, val binM: DoubleArray) {
        fun tpm(bin: Int): Double? {
            val i = bin + 20
            if (binM[i] < GRADE_MIN_BIN_M) return null
            return binT[i] / binM[i]
        }
        fun global(): Double = binT.sum() / binM.sum()
    }

    private fun modelOf(folds: List<Adv2CoverageBuildTest.Companion.Fold>, exclude: Int = -1): Model {
        val t = DoubleArray(41); val m = DoubleArray(41)
        folds.forEachIndexed { i, f ->
            if (i == exclude) return@forEachIndexed
            for ((b, d) in f.dd) { t[b + 20] += (f.dt[b] ?: 0.0); m[b + 20] += d }
        }
        return Model(t, m)
    }

    private fun dataset(tracks: List<RecordedTrack>): List<Step> {
        val cells = CellMap.build(tracks)
        val (latStep, lngStep) = CellMap.steps(tracks.first().points.first().lat)
        val out = ArrayList<Step>(200_000)
        tracks.forEachIndexed { idx, tr ->
            val pts = tr.points
            var j = 0
            for (i in 1 until pts.size) {
                val here = pts[i]; val prev = pts[i - 1]
                while (j < i - 1 && here.distanceM - pts[j + 1].distanceM >= GRADE_WINDOW_M) j++
                val m = here.distanceM - prev.distanceM
                val t = here.timeS - prev.timeS
                if (m > TrackSamples.DROPOUT_GAP_M) { j = i; continue }
                if (m <= 0.0 || t <= 0.0) continue
                val v = m / t
                if (v > AGG_MAX_SPEED_MS) { j = i; continue }
                if (v < AGG_MIN_SPEED_MS) continue // a dwell is not a pace verdict either way
                val back = pts[j]
                val span = here.distanceM - back.distanceM
                val e1 = here.eleM; val e0 = back.eleM
                val gradeKnown = e1 != null && e0 != null && span >= GRADE_WINDOW_M
                // LOOKUP side CLAMPS out-of-range (binOf coerces); only the build drops.
                val bin = if (gradeKnown) GradePace.binOf((e1!! - e0!!) / span * 100.0) else 0
                val br = bearingOf(prev, here)
                out += Step(
                    idx, m, t, bin,
                    CellMap.hit(cells, here.lat, here.lng, br, idx, latStep, lngStep, neighbourhood = false),
                    CellMap.hit(cells, here.lat, here.lng, br, idx, latStep, lngStep, neighbourhood = true),
                    gradeKnown,
                )
            }
        }
        return out
    }

    @Test fun `lookup coverage and head-to-head against one global pace`() {
        val (tracks, _) = Adv2CoverageBuildTest.library()
        val folds = tracks.map { Adv2CoverageBuildTest.fold(it) }
        val steps = dataset(tracks)
        val models = tracks.indices.map { modelOf(folds, it) }

        var all = 0.0; var t1s = 0.0; var t1l = 0.0
        var t2sAns = 0.0; var t2lAns = 0.0; var noGrade = 0.0; var belowFloor = 0.0
        // head-to-head accumulators, on the metres where tier 2 ACTUALLY answers (strict tier-1 miss)
        var sseG = 0.0; var sseGlob = 0.0; var sst = 0.0; var wsum = 0.0
        var actS = 0.0; var predG = 0.0; var predGlob = 0.0
        // per-ride cumulative-seconds error over its novel metres
        val rideActual = DoubleArray(tracks.size); val rideG = DoubleArray(tracks.size); val rideGl = DoubleArray(tracks.size)
        val rideNovelM = DoubleArray(tracks.size)
        // grand mean tpm for SST (metre-weighted, over the evaluated metres)
        var meanNum = 0.0
        for (s in steps) if (!s.tier1Strict) { meanNum += s.t; }
        var meanDen = 0.0
        for (s in steps) if (!s.tier1Strict) meanDen += s.m
        val grandTpm = if (meanDen > 0) meanNum / meanDen else 0.0

        for (s in steps) {
            all += s.m
            if (s.tier1Strict) t1s += s.m
            if (s.tier1Loose) t1l += s.m
            val mdl = models[s.track]
            val binPace = if (s.gradeKnown) mdl.tpm(s.bin) else null
            if (!s.tier1Strict) {
                when {
                    !s.gradeKnown -> noGrade += s.m
                    binPace == null -> belowFloor += s.m
                    else -> t2sAns += s.m
                }
            }
            if (!s.tier1Loose && s.gradeKnown && binPace != null) t2lAns += s.m
            if (!s.tier1Strict && binPace != null) {
                val actual = s.t / s.m
                val glob = mdl.global()
                sseG += s.m * (actual - binPace) * (actual - binPace)
                sseGlob += s.m * (actual - glob) * (actual - glob)
                sst += s.m * (actual - grandTpm) * (actual - grandTpm)
                wsum += s.m
                actS += s.t; predG += binPace * s.m; predGlob += glob * s.m
                rideActual[s.track] += s.t; rideG[s.track] += binPace * s.m; rideGl[s.track] += glob * s.m
                rideNovelM[s.track] += s.m
            }
        }

        fun p(x: Double) = 100.0 * x / all
        println("=== ADV2 §2 LOOKUP COVERAGE (leave-one-ride-out, metre-weighted) ===")
        println("rides=${tracks.size}  evaluated metres=${"%.0f".format(all / 1000)} km (ridden, dwell/spike/gap excluded)")
        println("tier 1 (PacePatch) answers : strict cell %.1f%%   with 3x3x3 neighbourhood %.1f%%".format(p(t1s), p(t1l)))
        println("NOVEL metres (tier-1 miss) : strict %.1f%%   loose %.1f%%".format(100 - p(t1s), 100 - p(t1l)))
        println("tier 2 answers on those    : %.1f%% of ALL metres (strict) | %.1f%% (loose tier 1)".format(p(t2sAns), p(t2lAns)))
        println("   of the novel metres     : %.1f%% get a gradient verdict, %.1f%% lost to <400 m bin, %.1f%% lost to no gradient window"
            .format(100.0 * t2sAns / (all - t1s), 100.0 * belowFloor / (all - t1s), 100.0 * noGrade / (all - t1s)))
        println("neutral fill (gap frozen)  : %.1f%% of all metres".format(100 - p(t1s) - p(t2sAns)))

        println("=== ADV2 §3 HEAD TO HEAD on the metres tier 2 answers (%.0f km) ===".format(wsum / 1000))
        println("RMSE (s/m)      grade-bin %.5f   one-global-pace %.5f".format(sqrt(sseG / wsum), sqrt(sseGlob / wsum)))
        println("R^2 vs held-out mean:  grade-bin %+.3f   one-global-pace %+.3f".format(1 - sseG / sst, 1 - sseGlob / sst))
        println("variance explained by the gradient key OVER the global pace: %+.1f%%".format(100.0 * (1 - sseG / sseGlob)))
        println("cumulative time over those metres: actual %.0f s | grade-bin %.0f s (%+.1f%%) | global %.0f s (%+.1f%%)"
            .format(actS, predG, 100.0 * (predG - actS) / actS, predGlob, 100.0 * (predGlob - actS) / actS))
        val perRide = tracks.indices.filter { rideNovelM[it] > 1000 }
        val errG = perRide.map { abs(rideG[it] - rideActual[it]) }
        val errGl = perRide.map { abs(rideGl[it] - rideActual[it]) }
        fun med(l: List<Double>) = l.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        println("per-ride |gap error| over its novel metres (n=${perRide.size}): median grade-bin %.0f s vs global %.0f s".format(med(errG), med(errGl)))
        println("per-ride mean |gap error|: grade-bin %.0f s vs global %.0f s".format(errG.average(), errGl.average()))
        // Per-kilometre-of-novel-ground drift, the unit the rider feels.
        val kmG = perRide.sumOf { abs(rideG[it] - rideActual[it]) } / (perRide.sumOf { rideNovelM[it] } / 1000)
        val kmGl = perRide.sumOf { abs(rideGl[it] - rideActual[it]) } / (perRide.sumOf { rideNovelM[it] } / 1000)
        println("drift per km of novel ground: grade-bin %.1f s/km vs global %.1f s/km".format(kmG, kmGl))
        // How novel is a ride, ride by ride? (the spread matters more than the mean: tier 2 is worth
        // little on a repeat commute and everything on an exploring day)
        val shares = tracks.indices.map { i ->
            val tot = steps.filter { it.track == i }.sumOf { it.m }
            if (tot <= 0) 0.0 else 100.0 * steps.filter { it.track == i && !it.tier1Strict }.sumOf { it.m } / tot
        }.sorted()
        println("per-ride novel share: min %.0f%% | p25 %.0f%% | median %.0f%% | p75 %.0f%% | max %.0f%%"
            .format(shares.first(), shares[shares.size / 4], shares[shares.size / 2], shares[3 * shares.size / 4], shares.last()))

        // Pins (loose, so this stays a measurement and not a tripwire).
        if (100.0 * t2sAns / (all - t1s) < 50.0) throw AssertionError("tier 2 answered on <50% of novel metres")
        if (sqrt(sseG / wsum) >= sqrt(sseGlob / wsum)) throw AssertionError("grade-bin no better than one global pace")
    }

    @Test fun `cold-start and flatlander cliffs`() {
        val (tracks, _) = Adv2CoverageBuildTest.library()
        val folds = tracks.map { Adv2CoverageBuildTest.fold(it) }
        val steps = dataset(tracks)
        println("=== ADV2 §4 COVERAGE CLIFFS ===")

        // (a) brand-new user: library = first N rides, ride N+1 is the novel one.
        println("--- cold start: model built from the first N rides, riding ride N+1 ---")
        for (n in listOf(1, 2, 3, 5, 10)) {
            if (n + 1 > tracks.size) continue
            val t = DoubleArray(41); val m = DoubleArray(41)
            for (k in 0 until n) for ((b, d) in folds[k].dd) { t[b + 20] += (folds[k].dt[b] ?: 0.0); m[b + 20] += d }
            val mdl = Model(t, m)
            val target = steps.filter { it.track == n }
            val novel = target.filter { !it.tier1Strict }
            val novelM = novel.sumOf { it.m }
            val ans = novel.filter { it.gradeKnown && mdl.tpm(it.bin) != null }.sumOf { it.m }
            val bins = (0..40).count { m[it] >= GRADE_MIN_BIN_M }
            println("  N=%2d rides: %2d/41 bins qualify, ride %d has %.1f km novel ground, tier 2 answers on %.1f%% of it"
                .format(n, bins, n, novelM / 1000, if (novelM > 0) 100.0 * ans / novelM else 0.0))
        }

        // (b) flatlander: model built only from near-flat metres (|grade| <= 2%), then hitting a real hill.
        val ft = DoubleArray(41); val fm = DoubleArray(41)
        folds.forEach { f -> for ((b, d) in f.dd) if (abs(b) <= 2) { ft[b + 20] += (f.dt[b] ?: 0.0); fm[b + 20] += d } }
        val flat = Model(ft, fm)
        val hilly = steps.filter { abs(it.bin) >= 3 && it.gradeKnown }
        val hillyM = hilly.sumOf { it.m }
        val hillyAns = hilly.filter { flat.tpm(it.bin) != null }.sumOf { it.m }
        println("--- flatlander library (only |grade|<=2%% history): %d/41 bins qualify".format((0..40).count { fm[it] >= GRADE_MIN_BIN_M }))
        println("    on %.1f km of >=3%% ground, tier 2 answers on %.1f%% of it".format(hillyM / 1000, 100.0 * hillyAns / hillyM))
        // What the flat model WOULD say if it were allowed to answer with its flat pace (the neighbour-bin
        // temptation the KDoc refuses): cost of that refusal, in seconds per km.
        val flatPace = flat.global()
        val err = hilly.sumOf { abs(flatPace * it.m - it.t) } / (hillyM / 1000)
        println("    (a flat-pace answer on that ground would be wrong by %.0f s/km — the refusal is correct)".format(err))

        // (c) altitude: how much of the library carries it at all.
        val noEle = tracks.count { t -> t.points.none { it.eleM != null } }
        val partial = tracks.count { t -> t.points.any { it.eleM == null } && t.points.any { it.eleM != null } }
        println("--- altitude: %d/%d rides carry NO altitude, %d carry it only partially".format(noEle, tracks.size, partial))

        // (d) pure commuter: model from short rides only (<15 km), riding a long one.
        val shortIdx = tracks.indices.filter { (tracks[it].points.lastOrNull()?.distanceM ?: 0.0) < 15_000 }
        val longIdx = tracks.indices.filter { (tracks[it].points.lastOrNull()?.distanceM ?: 0.0) >= 40_000 }
        if (shortIdx.isNotEmpty() && longIdx.isNotEmpty()) {
            val ct = DoubleArray(41); val cm = DoubleArray(41)
            shortIdx.forEach { k -> for ((b, d) in folds[k].dd) { ct[b + 20] += (folds[k].dt[b] ?: 0.0); cm[b + 20] += d } }
            val commuter = Model(ct, cm)
            val target = steps.filter { it.track in longIdx && !it.tier1Strict }
            val tm = target.sumOf { it.m }
            val ans = target.filter { it.gradeKnown && commuter.tpm(it.bin) != null }.sumOf { it.m }
            println("--- commuter library (%d rides <15 km, %.0f km total): %d/41 bins qualify; on %.1f km of novel ground in the %d long rides, tier 2 answers on %.1f%%"
                .format(shortIdx.size, cm.sum() / 1000, (0..40).count { cm[it] >= GRADE_MIN_BIN_M }, tm / 1000, longIdx.size, if (tm > 0) 100.0 * ans / tm else 0.0))
        } else {
            println("--- commuter profile: library has ${shortIdx.size} short and ${longIdx.size} long rides; skipped")
        }
    }

    @Test fun `what each filter would return if loosened`() {
        val (tracks, _) = Adv2CoverageBuildTest.library()
        val folds = tracks.map { Adv2CoverageBuildTest.fold(it) }
        val steps = dataset(tracks)
        val models = tracks.indices.map { modelOf(folds, it) }
        val novel = steps.filter { !it.tier1Strict }
        val novelM = novel.sumOf { it.m }
        println("=== ADV2 §5 WHAT A LOOSER FLOOR BUYS (novel metres = %.1f km) ===".format(novelM / 1000))
        for (floor in listOf(0.0, 100.0, 400.0, 1000.0, 4000.0)) {
            var ans = 0.0; var sse = 0.0; var w = 0.0
            for (s in novel) {
                if (!s.gradeKnown) continue
                val mdl = models[s.track]
                val i = s.bin + 20
                if (mdl.binM[i] < floor) continue
                ans += s.m
                val pace = mdl.binT[i] / mdl.binM[i]
                val a = s.t / s.m
                sse += s.m * (a - pace) * (a - pace); w += s.m
            }
            println("  floor %5.0f m: tier 2 answers on %.2f%% of novel metres, RMSE %.5f s/m".format(floor, 100.0 * ans / novelM, sqrt(sse / w)))
        }
    }

    @Test fun `bias, within-ride vs between-ride, and the freshness gates`() {
        val (tracks, _) = Adv2CoverageBuildTest.library()
        val folds = tracks.map { Adv2CoverageBuildTest.fold(it) }
        val steps = dataset(tracks)
        val models = tracks.indices.map { modelOf(folds, it) }

        // Per-ride accumulators over the metres tier 2 actually answers on.
        val n = tracks.size
        val m = DoubleArray(n); val tAct = DoubleArray(n); val tGrade = DoubleArray(n); val tGlob = DoubleArray(n)
        val rows = ArrayList<Triple<Int, DoubleArray, Double>>() // track, [m, actualT, gradeT], unused
        for (s in steps) {
            if (s.tier1Strict || !s.gradeKnown) continue
            val pace = models[s.track].tpm(s.bin) ?: continue
            m[s.track] += s.m; tAct[s.track] += s.t
            tGrade[s.track] += pace * s.m; tGlob[s.track] += models[s.track].global() * s.m
            rows += Triple(s.track, doubleArrayOf(s.m, s.t, pace * s.m), 0.0)
        }
        val use = (0 until n).filter { m[it] > 1000 }
        val totM = use.sumOf { m[it] }; val totA = use.sumOf { tAct[it] }; val totG = use.sumOf { tGrade[it] }
        val k = totA / totG
        println("=== ADV2 §6 BIAS / VARIANCE ===")
        println("novel-ground metres evaluated: %.1f km over %d rides".format(totM / 1000, use.size))
        println("systematic bias: the rider is %.1f%% SLOWER on novel ground than his gradient-matched history (calibration k=%.3f)"
            .format(100.0 * (k - 1), k))
        // Drift per km before/after a single global calibration scalar.
        val driftRaw = use.sumOf { abs(tGrade[it] - tAct[it]) } / (totM / 1000)
        val driftCal = use.sumOf { abs(k * tGrade[it] - tAct[it]) } / (totM / 1000)
        val driftGlob = use.sumOf { abs(tGlob[it] - tAct[it]) } / (totM / 1000)
        println("per-km drift on novel ground: grade %.1f s/km | grade x k %.1f s/km | global pace %.1f s/km".format(driftRaw, driftCal, driftGlob))

        // Between-ride vs within-ride variance: this is where the "gradient explains only 3.5%%" claim lives.
        var betweenSse = 0.0; var betweenSst = 0.0
        val grand = totA / totM
        for (i in use) {
            val a = tAct[i] / m[i]; val p = tGrade[i] / m[i]
            betweenSse += m[i] * (a - p) * (a - p)
            betweenSst += m[i] * (a - grand) * (a - grand)
        }
        var withinSse = 0.0; var withinSst = 0.0
        for ((tr, v, _) in rows) {
            if (m[tr] <= 1000) continue
            val sm = v[0]; val act = v[1] / sm; val pred = v[2] / sm
            val aDev = act - tAct[tr] / m[tr]
            val pDev = pred - tGrade[tr] / m[tr]
            withinSse += sm * (aDev - pDev) * (aDev - pDev)
            withinSst += sm * aDev * aDev
        }
        println("BETWEEN-ride pace (ride means): R^2 = %+.3f  <- what a per-ride LORO R^2 measures".format(1 - betweenSse / betweenSst))
        println("WITHIN-ride pace (step deviations from the ride mean): R^2 = %+.3f  <- what the ghost integrates".format(1 - withinSse / withinSst))
        println("variance split: between-ride %.0f%% of total, within-ride %.0f%%"
            .format(100.0 * betweenSst / (betweenSst + withinSst), 100.0 * withinSst / (betweenSst + withinSst)))

        // Freshness-gate exposure, as far as a recorded FIT can show it: metres in steps whose dt is long
        // (a stop or a GPS/recording gap) and metres in >200 m distance jumps.
        var longDt = 0.0; var jump = 0.0; var allM = 0.0
        tracks.forEach { tr ->
            val pts = tr.points
            for (i in 1 until pts.size) {
                val dm = pts[i].distanceM - pts[i - 1].distanceM
                val dt = pts[i].timeS - pts[i - 1].timeS
                if (dm <= 0) continue
                allM += dm
                if (dm > TrackSamples.DROPOUT_GAP_M) jump += dm
                else if (dt >= 10.0) longDt += dm
            }
        }
        println("=== ADV2 §7 FRESHNESS-GATE EXPOSURE (FIT-visible proxy) ===")
        println("metres in >200 m position jumps: %.3f%%   metres in steps taking >=10 s: %.2f%%".format(100 * jump / allM, 100 * longDt / allM))
        println("(the coast!=LIVE and stale-fix gates fire on exactly those metres; unmeasurable exactly off-device)")
    }
}
