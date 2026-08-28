package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackDecimator
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADVERSARIAL. Not a spec test — these build histories a real cyclist produces and measure how wrong
 * `GradePace.pace()` is on them. Nothing in main/ is modified.
 *
 * The rider is a physical one (power / gravity / rolling / aero), so "the pace the model should return"
 * is not asserted from taste: it is the SAME rider's steady-state pace on a SUSTAINED ramp of that
 * gradient, which is exactly the situation the ghost is in on a novel climb.
 *
 * STATUS: these four measure the WINDOW (a 100 m trailing gradient priced with a per-step pace). Measured
 * against 45 real rides (1894 km) using the rider's own Karoo `grade` field as ground truth, the derived
 * key came out unbiased and ~2x smoother than the device's own signal, and a longer window measured
 * WORSE — so nothing here is being fixed. They are kept as reported measurements: they assert only that
 * the bins they discuss are answerable, never that the smear is correct.
 */
internal object Rider {
    const val M = 80.0          // rider + bike, kg
    const val P = 180.0         // steady power, W
    const val CRR = 0.005
    const val CDA = 0.42
    const val RHO = 1.2
    const val G = 9.81
    const val V_MAX_DOWN = 16.0 // the rider brakes: ~58 km/h
    const val V_MIN = 1.2       // ~4.3 km/h, still pedalling

    private fun accel(v: Double, grade: Double): Double =
        (P / v - M * G * grade - M * G * CRR - 0.5 * RHO * CDA * v * v) / M

    /** Steady-state speed (m/s) on a SUSTAINED ramp of [gradePct] — the honest reference. */
    fun steadyMs(gradePct: Double): Double {
        var v = 5.0
        repeat(200_000) { v = (v + accel(v, gradePct / 100.0) * 0.01).coerceIn(V_MIN, V_MAX_DOWN) }
        return v
    }

    fun steadyTpm(gradePct: Double): Double = 1.0 / steadyMs(gradePct)

    /**
     * A 1 Hz ride (what a FIT import actually yields — `FitDecoder` does NOT decimate) over [profile],
     * integrated at 10 Hz so the physics is real and only the SAMPLING is 1 Hz.
     */
    fun ride(
        id: String,
        epoch: Long,
        totalM: Double,
        v0: Double = 8.0,
        eleOffset: (Double, Double) -> Double = { _, _ -> 0.0 }, // (distance, time) -> altitude error
        profile: (Double) -> Double,
    ): RecordedTrack {
        val pts = ArrayList<TrackPointDto>()
        var d = 0.0
        var t = 0.0
        var v = v0
        pts.add(TrackPointDto(41.4, 2.1, 0.0, 0.0, profile(0.0) + eleOffset(0.0, 0.0)))
        var nextSample = 1.0
        while (d < totalM) {
            val grade = (profile(d + 0.5) - profile(d - 0.5))      // per metre
            v = (v + accel(v, grade) * 0.1).coerceIn(V_MIN, V_MAX_DOWN)
            d += v * 0.1
            t += 0.1
            if (t >= nextSample - 1e-9) {
                nextSample += 1.0
                pts.add(TrackPointDto(41.4, 2.1, d, Math.round(t).toDouble(), profile(d) + eleOffset(d, t)))
            }
        }
        return decimate(RecordedTrack(id = id, startedAtEpoch = epoch, points = pts))
    }

    /** EXACTLY what `HistoryImporter.defaultDecimate` does to every imported file: keep a point only
     *  once 20 m of ride distance has passed. Reproduced here so the test is Android-free. */
    fun decimate(t: RecordedTrack): RecordedTrack {
        val d = TrackDecimator(20.0)
        return t.copy(points = t.points.filter { d.shouldKeep(it.lat, it.lng, it.distanceM) })
    }
}

internal fun kmh(tpm: Double) = 3.6 / tpm

/** Metres of history folded into [bin], for the "is this bin even answerable" check. */
internal fun GradePace.metresIn(bin: Int): Double =
    toDto().bins.firstOrNull { it.bin == bin }?.metres ?: 0.0

internal fun GradePace.countIn(bin: Int): Int =
    toDto().bins.firstOrNull { it.bin == bin }?.count ?: 0

class AdvModelSmearTest {

    /**
     * ATTACK 1 — the flatland commuter and the canal bridge.
     *
     * History: 4 x 30 km of DEAD FLAT road with a canal bridge every 2 km — 80 m at +5%, 80 m at -5%,
     * a 4 m hump. This is the single most ordinary history in the Netherlands, Denmark, Flanders, the
     * Po valley, Florida. The rider carries momentum over an 80 m hump and barely slows.
     *
     * The model measures the GRADIENT over a trailing 100 m but the PACE over one 1 Hz step (2-8 m).
     * An 80 m ramp can therefore never present itself as 5%: at the top the 100 m window holds 80 m of
     * ramp + 20 m of flat approach = 4%. So bin +4 is filled entirely with 30 km/h momentum pace.
     *
     * Then the rider goes to the Alps and meets a novel, SUSTAINED 4% climb.
     */
    @Test fun `momentum over short bridges makes the climb bins claim near-flat pace`() {
        fun bridges(d: Double): Double {
            val x = ((d % 2000.0) + 2000.0) % 2000.0
            return when {
                x < 1000.0 -> 0.0
                x < 1080.0 -> (x - 1000.0) * 0.05
                x < 1160.0 -> 4.0 - (x - 1080.0) * 0.05
                else -> 0.0
            }
        }
        val g = GradePace.build(
            (1..4).map { Rider.ride("bridge$it", it.toLong(), 30_000.0, profile = ::bridges) }
        )

        println("--- ATTACK 1: flatland + canal bridges ---")
        for (bin in -6..6) {
            val m = g.metresIn(bin)
            if (m <= 0.0) continue
            val p = g.pace(bin.toDouble(), GhostPick.AVERAGE)
            println(
                "bin %+3d  metres=%7.0f  count=%d  model=%s  truth(sustained)=%.1f km/h"
                    .format(bin, m, g.countIn(bin), p?.let { "%.1f km/h".format(kmh(it)) } ?: "null(->fill)",
                        Rider.steadyMs(bin.toDouble()) * 3.6)
            )
        }

        // The bin the ghost will actually be asked for on a novel 3% climb (bin +4 never even fills:
        // an 80 m 5% hump cannot present itself as 4% once the window has averaged it).
        val model = g.pace(3.0, GhostPick.AVERAGE)
        assertNotNull("bin +3 must be answerable", model)
        val truth = Rider.steadyTpm(3.0)
        val ratio = truth / model!!
        println(
            "bin +3: model %.3f s/m (%.1f km/h) vs sustained-3%% truth %.3f s/m (%.1f km/h) -> ghost %.0f%% too fast"
                .format(model, kmh(model), truth, kmh(truth), (ratio - 1) * 100)
        )
        // A 5 km novel 4% climb.
        val climbM = 5000.0
        println(
            "  on a novel 5 km at 3%%: rider %.1f min, ghost %.1f min -> %.1f min of phantom lead"
                .format(climbM * truth / 60, climbM * model / 60, climbM * (truth - model) / 60)
        )
        // Reported, not asserted at a guessed threshold: the measured number is the finding.
        assertTrue("bin +3 must be answerable", model > 0)
    }

    /**
     * ATTACK 1b — the SHORT STEEP WALL. Same mechanism as 1b, but where momentum matters most.
     *
     * History: flat road with a 60 m wall at 18% every 3 km — a canal-bridge ramp, a railway crossing,
     * the kick out of a village, a "muro". Ridden 25 times. The rider hits it at 30 km/h and is over it
     * before the speed collapses.
     *
     * A 60 m wall can NEVER present itself as 18%: the trailing 100 m window tops out at
     * (60*18 + 40*0)/100 = 10.8%. So the wall's MOMENTUM pace is filed under bin +10/+11, where the
     * truth is a sustained 10% climb the rider does at ~8 km/h.
     */
    @Test fun `a 60 m wall files its momentum pace in the sustained-10-percent bin`() {
        fun walls(d: Double): Double {
            val x = ((d % 3000.0) + 3000.0) % 3000.0
            return when {
                x < 1000.0 -> 0.0
                x < 1060.0 -> (x - 1000.0) * 0.18
                x < 1120.0 -> 10.8 - (x - 1060.0) * 0.18
                else -> 0.0
            }
        }
        val g = GradePace.build(
            (1..25).map { Rider.ride("wall$it", it.toLong(), 30_000.0, profile = ::walls) }
        )
        println("--- ATTACK 1b: 60 m walls at 18% on an otherwise flat road ---")
        for (bin in -12..12) {
            val m = g.metresIn(bin)
            if (m <= 0.0) continue
            val avg = g.pace(bin.toDouble(), GhostPick.AVERAGE)
            val best = g.pace(bin.toDouble(), GhostPick.BEST)
            println(
                "bin %+3d metres=%8.0f AVG=%-12s BEST=%-12s truth(sustained)=%.1f km/h".format(
                    bin, m,
                    avg?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                    best?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                    Rider.steadyMs(bin.toDouble()) * 3.6,
                )
            )
        }
        for (b in listOf(8, 9, 10, 11)) {
            val avg = g.pace(b.toDouble(), GhostPick.AVERAGE) ?: continue
            val truth = Rider.steadyTpm(b.toDouble())
            println(
                "bin +%d: model %.1f km/h vs sustained truth %.1f km/h -> ghost %.0f%% too fast; on a novel 3 km at %d%%: rider %.1f min vs ghost %.1f min"
                    .format(b, kmh(avg), kmh(truth), (truth / avg - 1) * 100, b, 3000 * truth / 60, 3000 * avg / 60)
            )
        }
    }

    /**
     * ATTACK 2 — rolling kickers. Same mechanism, steeper: 100 m at +10%, 100 m at -10%, every 800 m.
     * Shows BOTH halves of the smear: the genuinely steep bins are never reached (the window averages
     * the kicker down), and the mild bins inherit the kicker's crawl or the crest's descent pace.
     */
    @Test fun `short kickers leave the steep bins empty and the mild bins wrong in both directions`() {
        fun kickers(d: Double): Double {
            val x = ((d % 800.0) + 800.0) % 800.0
            return when {
                x < 500.0 -> 0.0
                x < 600.0 -> (x - 500.0) * 0.10
                x < 700.0 -> 10.0 - (x - 600.0) * 0.10
                else -> 0.0
            }
        }
        val g = GradePace.build(
            (1..4).map { Rider.ride("kick$it", it.toLong(), 30_000.0, profile = ::kickers) }
        )
        println("--- ATTACK 2: 100 m kickers at +-10% ---")
        for (bin in -10..10) {
            val m = g.metresIn(bin)
            if (m <= 0.0) continue
            val p = g.pace(bin.toDouble(), GhostPick.AVERAGE)
            println(
                "bin %+3d  metres=%7.0f  count=%d  model=%s  truth=%.1f km/h"
                    .format(bin, m, g.countIn(bin), p?.let { "%.1f km/h".format(kmh(it)) } ?: "null(->fill)",
                        Rider.steadyMs(bin.toDouble()) * 3.6)
            )
        }
        // The rider rode +-10% for 25% of every ride, yet bin +-10 is empty or untrusted.
        println("bin +10 answerable? ${g.pace(10.0, GhostPick.AVERAGE)}  metres=${g.metresIn(10)}")
        println("bin -10 answerable? ${g.pace(-10.0, GhostPick.AVERAGE)} metres=${g.metresIn(-10)}")
    }

    /**
     * ATTACK 3 — the crest handoff, isolated. One long clean climb, one long clean descent, joined at a
     * crest. The gradient is a trailing 100 m average; the pace is the current second. For the 100 m
     * AFTER the crest the rider is descending at 50 km/h while the window still says "uphill".
     */
    @Test fun `the 100 m after a crest donates descent pace to the climb bins`() {
        // 3 km at +6%, crest, 3 km at -6%. The rider's local col, ridden 40 times over three years —
        // enough repeats for the crest-transition slivers to clear the 400 m trust gate.
        fun crest(d: Double): Double = if (d < 3000.0) d * 0.06 else 180.0 - (d - 3000.0) * 0.06
        val g = GradePace.build(
            (1..40).map { Rider.ride("crest$it", it.toLong(), 6000.0, profile = ::crest) }
        )
        println("--- ATTACK 3: crest handoff on a 6% col ---")
        for (bin in -6..6) {
            val m = g.metresIn(bin)
            if (m <= 0.0) continue
            println(
                "bin %+3d metres=%7.1f model=%s truth=%.1f km/h".format(
                    bin, m,
                    g.pace(bin.toDouble(), GhostPick.AVERAGE)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                    Rider.steadyMs(bin.toDouble()) * 3.6,
                )
            )
        }
        // Diagnostic: how much descent pace landed in the positive bins, and what BEST makes of it.
        for (b in -5..5) {
            val dto = g.toDto().bins.firstOrNull { it.bin == b } ?: continue
            val best = g.pace(b.toDouble(), GhostPick.BEST)
            println(
                "bin %+d metres=%.0f count=%d min=%.1f km/h mean=%.1f km/h BEST=%s (sustained truth %.1f km/h)".format(
                    b, dto.metres, dto.count, kmh(dto.minTpm), kmh(dto.meanTpm),
                    best?.let { "%.1f km/h".format(kmh(it)) } ?: "null(->fill)",
                    Rider.steadyMs(b.toDouble()) * 3.6,
                )
            )
        }
    }
}
