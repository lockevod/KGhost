package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADVERSARIAL, now partly REGRESSION LOCKS. Histories that a normal rider produces by accident, aimed at
 * the reducer rather than at the gradient window.
 *
 * Two of these found real one-file-forever damage and are now locks on the FIXED behaviour: the
 * sub-dropout distance spike (the window restarts) and the city stop (a dwell step is dropped, not
 * clipped). A third, "the drive home", moved to `import/FitSportGateTest` — only the FIT file knows it
 * was a car. The rest either hold, or record an attempt that real-data measurement refuted.
 */
class AdvModelPoisonTest {

    /** A constant-gradient stretch at a constant speed, appended to [into] (distance/time continue). */
    private fun append(
        into: MutableList<TrackPointDto>, lengthM: Double, gradePct: Double, speedMs: Double, stepM: Double = 5.0,
    ) {
        val last = into.last()
        var d = 0.0
        while (d < lengthM) {
            d += stepM
            into.add(
                TrackPointDto(
                    lat = 41.4, lng = 2.1,
                    distanceM = last.distanceM + d,
                    timeS = last.timeS + d / speedMs,
                    eleM = last.eleM!! + d * gradePct / 100.0,
                )
            )
        }
    }

    private fun trackOf(id: String, epoch: Long, build: (MutableList<TrackPointDto>) -> Unit): RecordedTrack {
        val pts = mutableListOf(TrackPointDto(41.4, 2.1, 0.0, 0.0, 100.0))
        build(pts)
        return Rider.decimate(RecordedTrack(id, epoch, pts))
    }

    private fun report(g: GradePace, bin: Int, truthMs: Double, label: String) {
        val dto = g.toDto().bins.firstOrNull { it.bin == bin }
        println(
            "$label bin %+d metres=%.0f count=%d | AVG=%s LAST=%s BEST=%s | truth %.1f km/h".format(
                bin, dto?.metres ?: 0.0, dto?.count ?: 0,
                g.pace(bin.toDouble(), GhostPick.AVERAGE)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                g.pace(bin.toDouble(), GhostPick.LAST)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                g.pace(bin.toDouble(), GhostPick.BEST)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                truthMs * 3.6,
            )
        )
    }

    // ATTACK 4 — "the drive home" (a forgotten save turning 5 km of 6% col at 55 km/h into history,
    // +97% on the 6% bin's LAST and BEST, forever) MOVED to `import/FitSportGateTest`. GradePace has no
    // way to tell a car climbing 6% from a bicycle; the only place that knowledge exists is the FIT
    // file's own activity type and sport, so the fix and its lock live at the import boundary.

    /**
     * ATTACK 5 — REFUTED (kept as the record of the attempt).
     *
     * `minTpm` genuinely has NO per-track metre floor: `GRADE_MIN_BIN_M` gates the BIN, never the
     * per-track sample, so in principle a 20 m sliver at momentum speed could own BEST for a steep bin
     * forever. It does not, and the reason is the same 100 m window that causes ATTACK 1b: a bin of
     * +12% can only be REACHED after 100 m of trailing climb, by which time the momentum is gone. The
     * window that smears the model also protects `minTpm` from momentum slivers.
     *
     * A sliver can only enter a steep bin at speed if a NON-BICYCLE step supplies the speed — which is
     * exactly ATTACK 4.
     */
    @Test fun `REFUTED - a momentum sliver cannot own BEST in a steep bin`() {
        val truth12 = Rider.steadyMs(12.0)
        // Six honest hill-repeat sessions: 600 m of sustained 12% each.
        val honest = (1..6).map { i ->
            trackOf("repeat$i", i.toLong()) { p ->
                append(p, 400.0, 0.0, Rider.steadyMs(0.0))
                append(p, 600.0, 12.0, truth12)
            }
        }
        val base = GradePace.build(honest)
        report(base, 12, truth12, "CLEAN  ")

        // One ordinary ride that merely TOUCHES 12%: a 130 m ramp attacked out of the saddle, entered
        // at 32 km/h. Only ~30 m of it lands in bin 12 — but at momentum speed.
        val withSliver = honest + Rider.ride("sliver", 7L, 1400.0, v0 = 8.9) { d ->
            when {
                d < 900.0 -> 0.0
                d < 1030.0 -> (d - 900.0) * 0.12
                else -> 15.6
            }
        }
        val g = GradePace.build(withSliver)
        val dto = g.toDto().bins.first { it.bin == 12 }
        report(g, 12, truth12, "SLIVERED")
        println(
            "  bin 12: mean=%.1f km/h  min=%.1f km/h (the sliver)  BEST=%.1f km/h  = %.2fx the rider's real 12%% speed"
                .format(kmh(dto.meanTpm), kmh(dto.minTpm), kmh(g.pace(12.0, GhostPick.BEST)!!),
                    kmh(g.pace(12.0, GhostPick.BEST)!!) / (truth12 * 3.6))
        )
        assertTrue(
            "REFUTED: the sliver reaches bin 12 only after 100 m of climbing, at honest climbing pace",
            dto.minTpm > 0.9 * dto.meanTpm,
        )
    }

    /**
     * ATTACK 6 — FIXED, now a REGRESSION LOCK. A GPS distance spike of 30-200 m is rejected as a pace
     * sample; it used to be RETAINED in the gradient denominator, because the window only restarted
     * above `DROPOUT_GAP_M` (200 m) and the spike guard `continue`d without touching `j`.
     *
     * A 150 m one-step jump then made the next ~100 m of real riding read its gradient over dd ~ 250 m
     * instead of ~100 m: an 8% climb filed as a 3% one, carrying the 8% crawl pace. Measured over 20
     * commutes through the same underpass, bins +1/+2/+3 answered 9.5 km/h against truths of
     * 26.0/22.2/18.8 km/h — 49% to 63% slow. The spike branch now restarts the window (`j = i`), as
     * the dropout branch always did, so the spiked history must fill exactly the bins the clean one does.
     */
    @Test fun `a sub-dropout distance spike no longer files climb pace in a mild bin`() {
        val truth8 = Rider.steadyMs(8.0)
        val spiked = (1..20).map { i ->
            trackOf("commute$i", i.toLong()) { p ->
                append(p, 600.0, 8.0, truth8)
                // The urban-canyon / short-tunnel re-acquisition: +150 m of distance in one second,
                // altitude unchanged. 150 > AGG_MAX_SPEED_MS*1s so the STEP is dropped; 150 < 200 so
                // the WINDOW is not restarted.
                val last = p.last()
                p.add(TrackPointDto(41.4, 2.1, last.distanceM + 150.0, last.timeS + 1.0, last.eleM))
                append(p, 600.0, 8.0, truth8)
            }
        }
        val g = GradePace.build(spiked)
        println("--- ATTACK 6: 150 m distance spike inside an 8% climb (x20 commutes) ---")
        for (b in 0..9) {
            val m = g.metresIn(b)
            if (m <= 0.0) continue
            println(
                "bin +%d metres=%7.0f AVG=%s (sustained truth %.1f km/h)".format(
                    b, m,
                    g.pace(b.toDouble(), GhostPick.AVERAGE)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                    Rider.steadyMs(b.toDouble()) * 3.6,
                )
            )
        }
        // Control: the same history without the spike.
        val clean = GradePace.build(
            (1..20).map { i -> trackOf("clean$i", i.toLong()) { p -> append(p, 1350.0, 8.0, truth8) } }
        )
        println("control (no spike): bins with metres = " +
            clean.toDto().bins.filter { it.metres > 0 }.joinToString { "${it.bin}:${it.metres.toInt()}m" })
        println("spiked            : bins with metres = " +
            g.toDto().bins.filter { it.metres > 0 }.joinToString { "${it.bin}:${it.metres.toInt()}m" })

        // THE LOCK: the spike may cost metres (the window restarts), but it must not invent a bin. The
        // only bin either history may hold is +8, the road the rider actually rode.
        assertEquals(listOf(8), g.toDto().bins.filter { it.metres > 0 }.map { it.bin })
        assertEquals(listOf(8), clean.toDto().bins.filter { it.metres > 0 }.map { it.bin })
        for (b in listOf(1, 2, 3)) {
            assertNull("bin +$b must stay empty: it is a spike artefact", g.pace(b.toDouble(), GhostPick.AVERAGE))
        }
        // ...and the 8% bin still answers the rider's real 8% pace.
        assertEquals(1.0 / truth8, g.pace(8.0, GhostPick.AVERAGE)!!, 0.02 / truth8)
    }

    /**
     * ATTACK 7 — FIXED, now a REGRESSION LOCK. The global flat bin and the city.
     *
     * Bin 0 is where 80-90% of every rider's metres live, and it is GLOBAL: a red light in town used to
     * move the pace the ghost uses on a country lane it has never seen. (`PacePatch` keeps the same dwell
     * CLIP, and may: its cells are 18 m, so the poison stays AT the junction. A gradient bin is global.)
     *
     * Measured with the clip: 200 urban commutes with a 55 s light every 700 m answered 21.0 km/h against
     * a real moving pace of 30.1 (-30%); blended with 50 stop-free weekend rides, still -16%, i.e. 11 min
     * lost over 30 km of novel flat road. A stop says nothing about pace-at-gradient, so the step is now
     * DROPPED, not clipped: the answer is the rider's MOVING pace, at the cost of the stopped metres.
     */
    @Test fun `city stops no longer drag the global flat bin below the rider's real flat pace`() {
        val flat = Rider.steadyMs(0.0)
        fun commute(id: String, epoch: Long, lights: Int, stopS: Double): RecordedTrack {
            val pts = mutableListOf(TrackPointDto(41.4, 2.1, 0.0, 0.0, 100.0))
            repeat(lights) {
                append(pts, 700.0, 0.0, flat)
                val last = pts.last()          // the light: distance frozen for stopS seconds
                pts.add(TrackPointDto(41.4, 2.1, last.distanceM, last.timeS + stopS, last.eleM))
            }
            append(pts, 700.0, 0.0, flat)
            return Rider.decimate(RecordedTrack(id, epoch, pts))
        }
        // 200 commutes, 15 km each, a light every 700 m held 55 s.
        val city = (1..200).map { commute("commute$it", it.toLong(), lights = 21, stopS = 55.0) }
        // The same history with the lights removed: the metres the drop costs are exactly these minus
        // those, so the coverage cost is measured, not guessed.
        val noLights = (1..200).map { i ->
            trackOf("nolights$i", i.toLong()) { p -> append(p, 22 * 700.0, 0.0, flat) }
        }
        val cityOnly = GradePace.build(city)
        val cityPace = cityOnly.pace(0.0, GhostPick.AVERAGE)!!
        println("--- ATTACK 7: the global flat bin ---")
        println(
            "city-only  bin 0: %.1f km/h   (the rider's real MOVING flat pace is %.1f km/h)"
                .format(kmh(cityPace), flat * 3.6)
        )
        val stopFreeM = GradePace.build(noLights).coveredM
        println(
            "coverage cost of DROPPING dwell steps: %.0f m of %.0f m = %.2f%% (200 urban commutes, a 55 s light every 700 m)"
                .format(stopFreeM - cityOnly.coveredM, stopFreeM, 100 * (1 - cityOnly.coveredM / stopFreeM))
        )
        // Plus 50 weekend rides, 80 km each, no stops at all.
        val weekend = (201..250).map { i ->
            trackOf("weekend$i", i.toLong()) { p -> append(p, 80_000.0, 0.0, flat, stepM = 20.0) }
        }
        val mixed = GradePace.build(city + weekend)
        val m = mixed.pace(0.0, GhostPick.AVERAGE)!!
        println(
            "mixed      bin 0: %.1f km/h -> ghost is %.0f%% slow on a novel FLAT road; over 30 km that is %.1f min"
                .format(kmh(m), (1 - (1 / m) / flat) * 100, 30_000 * (m - 1 / flat) / 60)
        )
        // THE LOCK: with the stops dropped, both the city-only and the mixed history answer the rider's
        // MOVING flat pace. 1% covers the window-edge metres a stop straddles, not a laundered stop
        // (the clip put these at 1.43x and 1.19x of the truth).
        assertEquals("city-only bin 0 must be the moving pace", 1.0 / flat, cityPace, 0.01 / flat)
        assertEquals("mixed bin 0 must be the moving pace", 1.0 / flat, m, 0.01 / flat)
        // ...and the city history is still MOSTLY there: dropping a stop costs one step, not the ride.
        assertTrue(
            "dropping dwell steps must cost only a few percent of the metres: ${cityOnly.coveredM} of $stopFreeM",
            cityOnly.coveredM > 0.9 * stopFreeM,
        )
    }

    /**
     * ATTACK 8 — a steep bin owned by ONE ride, and `AVERAGE` silently becoming `LAST`.
     *
     * `pace()` falls back to `lastTpm` below `AGG_MIN_LAPS` (2). A bin needs only 400 m of history to
     * answer — one small hill. So the very first time a rider drags a loaded touring bike up a 15%
     * lane, that single ride becomes the ALL-TIME AVERAGE for every 15% the ghost ever meets.
     *
     * NOT a bug being left open: the count==1 fallback is DELIBERATE and documented in `pace()` (the
     * same rule as PacePatch/RouteAggregate — one honest ride beats no verdict at all). This is a lock
     * on that documented behaviour, not an assertion of an unfixed distortion.
     */
    @Test fun `one loaded-touring ride becomes the all-time AVERAGE for 15 percent`() {
        val onRoadBike = Rider.steadyMs(15.0)
        // 105 kg loaded, same legs: ~30% slower up a wall.
        val loaded = onRoadBike * 80.0 / 105.0
        val g = GradePace.build(
            listOf(trackOf("tour", 1L) { p ->
                append(p, 200.0, 15.0, loaded)      // fills the gradient window
                append(p, 500.0, 15.0, loaded)
            })
        )
        val dto = g.toDto().bins.first { it.bin == 15 }
        println("--- ATTACK 8: one ride owns bin 15 ---")
        println(
            "bin 15 metres=%.0f count=%d  AVERAGE returns %.1f km/h (the touring ride) while the rider's road-bike 15%% is %.1f km/h"
                .format(dto.metres, dto.count, kmh(g.pace(15.0, GhostPick.AVERAGE)!!), onRoadBike * 3.6)
        )
        assertEquals("count is 1, yet AVERAGE answers", 1, dto.count)
        assertEquals(
            "AVERAGE is literally LAST here",
            g.pace(15.0, GhostPick.LAST)!!, g.pace(15.0, GhostPick.AVERAGE)!!, 1e-12,
        )
    }

    // ATTACKS 9, 9b, 9c (altimeter settle / GPS-derived altitude noise manufacturing climb bins at
    // flat pace) and ATTACK 10 (build key vs the device's live `ELEVATION_GRADE` key) are DELETED:
    // measured against 45 real rides (1894 km) with the rider's own Karoo `grade` field as ground
    // truth, the derived key came out UNBIASED and about 2x smoother than the device's own signal, and
    // only 0.0-3.5% of flat metres were mis-filed into a non-zero bin. A roughness gate, a longer
    // window and keying on the device grade all measured WORSE. The premise was synthetic; the real
    // files refuted it, so there is no distortion left here to lock.

    // ---------------------------------------------------------------- refutation checks

    /** Same road ridden both ways: the bins are signed, so nothing mixes. Expected to HOLD. */
    @Test fun `riding a road in both directions does not mix the two bins`() {
        val up = Rider.steadyMs(7.0)
        val down = Rider.steadyMs(-7.0)
        val g = GradePace.build(
            (1..3).map { i ->
                trackOf("outback$i", i.toLong()) { p ->
                    append(p, 2000.0, 7.0, up)
                    append(p, 2000.0, -7.0, down)
                }
            }
        )
        println("both-ways: +7 -> %.1f km/h (truth %.1f), -7 -> %.1f km/h (truth %.1f)".format(
            kmh(g.pace(7.0, GhostPick.AVERAGE)!!), up * 3.6,
            kmh(g.pace(-7.0, GhostPick.AVERAGE)!!), down * 3.6,
        ))
        assertEquals(1.0 / up, g.pace(7.0, GhostPick.AVERAGE)!!, 0.02 / up)
        assertEquals(1.0 / down, g.pace(-7.0, GhostPick.AVERAGE)!!, 0.02 / down)
    }

    /** The metre-weighted running mean must equal total-time / total-distance exactly. Expected to HOLD. */
    @Test fun `the running mean is exactly total time over total distance`() {
        val g = GradePace.build(
            listOf(
                trackOf("a", 1L) { p -> append(p, 1000.0, 5.0, 4.0) },
                trackOf("b", 2L) { p -> append(p, 5000.0, 5.0, 2.0) },
            )
        )
        val dto = g.toDto().bins.first { it.bin == 5 }
        // Whatever the exact metres per track are, the mean must be the metre-weighted blend, which for
        // 1 km at 4 m/s and 5 km at 2 m/s sits far closer to 0.5 s/m than to 0.25 s/m.
        println("blend: mean=%.4f s/m over %.0f m from %d tracks".format(dto.meanTpm, dto.metres, dto.count))
        assertTrue(dto.meanTpm > 0.4 && dto.meanTpm < 0.5)
    }

    /** Bin arithmetic at the edges. Expected to HOLD (documented clamp-at-lookup / drop-at-build). */
    @Test fun `bin edges behave as documented`() {
        assertEquals(0, GradePace.binOf(0.0))
        assertEquals(0, GradePace.binOf(0.49))
        assertEquals(1, GradePace.binOf(0.5))       // roundToInt is half-UP
        assertEquals(0, GradePace.binOf(-0.5))      // ...so bin 0 is [-0.5, 0.5), 1% wide
        assertEquals(-1, GradePace.binOf(-0.51))
        assertEquals(20, GradePace.binOf(20.0))
        assertEquals(20, GradePace.binOf(45.0))     // clamped at lookup
        assertEquals(-20, GradePace.binOf(-45.0))
        // Build-side: a 19.5-20.0% road is the ONLY thing bin 20 can hold, so bin 20 is half-width and
        // is what a live 45% wall is answered with.
        val g = GradePace.build(
            (1..3).map { i -> trackOf("wall$i", i.toLong()) { p -> append(p, 900.0, 19.8, 1.5) } }
        )
        println("bin 20 answers a live 45%% reading with %.1f km/h".format(kmh(g.pace(45.0, GhostPick.AVERAGE)!!)))
        assertNotNull(g.pace(45.0, GhostPick.AVERAGE))
        // ...while a genuine 22% wall contributes NOTHING (dropped at build).
        val dropped = GradePace.build(
            (1..3).map { i -> trackOf("w$i", i.toLong()) { p -> append(p, 900.0, 22.0, 1.3) } }
        )
        assertNull("a 22% wall is dropped entirely", dropped.pace(45.0, GhostPick.AVERAGE))
        assertEquals(0.0, dropped.coveredM, 1e-9)
    }

    /** Altitude holes: a track where only some points carry `eleM`. Expected: data loss, not distortion. */
    @Test fun `altitude holes drop samples rather than distorting them`() {
        val truth = Rider.steadyMs(6.0)
        val full = trackOf("full", 1L) { p -> append(p, 3000.0, 6.0, truth) }
        // Every 3rd point loses its altitude (a sensor that drops out, a merged/edited file).
        val holey = full.copy(
            id = "holey",
            points = full.points.mapIndexed { i, p -> if (i % 3 == 1) p.copy(eleM = null) else p },
        )
        val a = GradePace.build(listOf(full, full.copy(id = "full2", startedAtEpoch = 2L)))
        val b = GradePace.build(listOf(holey, holey.copy(id = "holey2", startedAtEpoch = 2L)))
        println(
            "altitude holes: full=%.1f km/h over %.0f m | holey=%s over %.0f m".format(
                kmh(a.pace(6.0, GhostPick.AVERAGE)!!), a.coveredM,
                b.pace(6.0, GhostPick.AVERAGE)?.let { "%.1f km/h".format(kmh(it)) } ?: "null",
                b.coveredM,
            )
        )
    }
}
