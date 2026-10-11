package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackDecimator
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** The DRIFT gate in [GradePace.foldOf]: a Karoo left on at home must not feed the model. */
class GradePaceDriftGateTest {

    private val home = 41.4 to 2.1
    private val mPerDegLat = 111_195.0
    private val mPerDegLng = mPerDegLat * cos(Math.toRadians(home.first))

    /** A local-metres point: east [x], north [y], cumulative ride distance [d], elapsed [t], altitude [e]. */
    private data class P(val x: Double, val y: Double, val d: Double, val t: Double, val e: Double = 0.0)

    private fun track(pts: List<P>, id: String = "t") = RecordedTrack(
        id, 1L,
        pts.map { TrackPointDto(home.first + it.y / mPerDegLat, home.second + it.x / mPerDegLng, it.d, it.t, it.e) },
    )

    /** Ride distance/time accumulated from the chords, at [speed] m/s. */
    private fun ridden(xy: List<Pair<Double, Double>>, speed: Double = 5.0, ele: (Int) -> Double = { 0.0 }): List<P> {
        var d = 0.0
        return xy.mapIndexed { i, (x, y) ->
            if (i > 0) d += hypot(x - xy[i - 1].first, y - xy[i - 1].second)
            P(x, y, d, d / speed, ele(i))
        }
    }

    /** GPS jitter around home: a point every 10 s, uniformly inside a 25 m disc, altitude wobbling ±0.5 m. Real
     *  stored drift has 13-18 m median chords (the 20 m decimation of accumulated jitter distance). */
    private fun drift(n: Int, rnd: Random = Random(42), offset: Pair<Double, Double> = 0.0 to 0.0): List<Pair<Double, Double>> =
        List(n) {
            val r = 25.0 * kotlin.math.sqrt(rnd.nextDouble())
            val a = rnd.nextDouble() * 2 * Math.PI
            offset.first + r * cos(a) to offset.second + r * sin(a)
        }

    /** Deterministic jitter within 25 m whose every step is a qualifying (>= 18 m) chord turning > 3.7 deg/m. */
    private fun golden(n: Int) = List(n) { i ->
        val r = (20.0 + (i * 37 % 80)) / 4.0
        val a = Math.toRadians(i * 137.5)
        r * cos(a) to r * sin(a)
    }

    private fun circuit(radiusM: Double, lengthM: Double, stepM: Double = 20.0) =
        List((lengthM / stepM).toInt() + 1) { i ->
            val a = i * stepM / radiusM
            radiusM * cos(a) to radiusM * sin(a)
        }

    /** A path of [stepM] chords whose heading changes by each of [turnsDeg] in turn (all to the left). */
    private fun turning(turnsDeg: List<Double>, stepM: Double = 20.0): List<Pair<Double, Double>> {
        var x = 0.0; var y = 0.0; var h = 0.0
        val out = arrayListOf(x to y)
        for (k in 0..turnsDeg.size) {
            if (k > 0) h += turnsDeg[k - 1]
            x += stepM * sin(Math.toRadians(h)); y += stepM * cos(Math.toRadians(h))
            out += x to y
        }
        return out
    }

    @Test fun `an even number of turns takes the true median - ten at 10 deg and ten at 40 deg is 25 deg, kept`() {
        // The upper middle value would be 40 deg and gate it.
        assertFalse(GradePace.foldOf(track(ridden(turning(List(10) { 10.0 } + List(10) { 40.0 })))).isEmpty)
    }

    @Test fun `the footprint centre is the true median - two points 2 km apart have a 1 km footprint`() {
        val f = GradePace.robustFootprintM(track(listOf(P(0.0, 0.0, 0.0, 0.0), P(0.0, 2000.0, 2000.0, 400.0))).points)
        assertEquals(1000.0, f, 1.0)
    }

    @Test fun `a Karoo left on at home for an hour - jitter within 25 m - yields an empty fold`() {
        val rnd = Random(42)
        val pts = drift(360, rnd).mapIndexed { i, xy -> i to xy }.let { l ->
            var d = 0.0
            l.map { (i, xy) ->
                if (i > 0) d += hypot(xy.first - l[i - 1].second.first, xy.second - l[i - 1].second.second)
                P(xy.first, xy.second, d, i * 10.0, 50.0 + rnd.nextDouble() - 0.5)
            }
        }
        assertTrue(GradePace.foldOf(track(pts)).isEmpty)
    }

    @Test fun `a genuine ride with a footprint over 1 km is folded bit-identically, however its heading wobbles`() {
        // A zig-zag (heading alternating +-40 deg, median turn 80 deg) over ~4.6 km of northing: incoherent
        // heading, but a footprint no home drift has. foldOf's loop reads only distance/time/altitude, so the
        // fold must equal that of the same ride drawn as a straight line (never gated).
        val n = 300
        var x = 0.0; var y = 0.0
        val zig = List(n) { i ->
            if (i > 0) { val a = Math.toRadians(if (i % 2 == 0) 40.0 else -40.0); x += 20 * sin(a); y += 20 * cos(a) }
            x to y
        }
        val ele = { i: Int -> if (i < n / 2) i * 20 * 0.03 else (n / 2) * 20 * 0.03 }
        val zigzag = ridden(zig, ele = ele)
        val straight = zigzag.mapIndexed { i, p -> p.copy(x = 0.0, y = i * 20.0) }
        val got = GradePace.foldOf(track(zigzag))
        val want = GradePace.foldOf(track(straight))
        assertFalse(want.isEmpty)
        assertEquals(want.sumDd, got.sumDd)
        assertEquals(want.sumDt, got.sumDt)
    }

    /** [lengthM] ridden at [kmh] along [path] (arc length -> east/north metres), sampled at 1 Hz and stored
     *  through the PRODUCTION decimator, as TrackRecorder does: steps are 20-29 m depending on the speed. */
    private fun decimated(
        kmh: Double, lengthM: Double = 3000.0, ele: (Double) -> Double = { 0.0 }, path: (Double) -> Pair<Double, Double>,
    ): List<P> {
        val v = kmh / 3.6
        val dec = TrackDecimator(20.0)
        return (0..(lengthM / v).toInt()).mapNotNull { t ->
            val d = v * t
            if (!dec.shouldKeep(d)) null else path(d).let { (x, y) -> P(x, y, d, t.toDouble(), ele(d)) }
        }
    }

    private fun round(r: Double): (Double) -> Pair<Double, Double> = { s -> r * cos(s / r) to r * sin(s / r) }

    /** The 400 m athletics oval: 84.39 m straights, 36.5 m bends. */
    private val oval: (Double) -> Pair<Double, Double> = { s0 ->
        val r = 36.5; val l = 84.39
        var s = s0 % (2 * l + 2 * Math.PI * r)
        when {
            s < l -> s to -r
            s < l + Math.PI * r -> { s -= l; val a = -Math.PI / 2 + s / r; l + r * cos(a) to r * sin(a) }
            s < 2 * l + Math.PI * r -> { s -= l + Math.PI * r; l - s to r }
            else -> { s -= 2 * l + Math.PI * r; val a = Math.PI / 2 + s / r; r * cos(a) to r * sin(a) }
        }
    }

    @Test fun `circling is judged by curvature, not per-step turn - the speed of the decimated steps does not matter`() {
        for (kmh in listOf(20.0, 30.0, 35.0)) {
            assertFalse("R 45 m at $kmh km/h kept", GradePace.foldOf(track(decimated(kmh, path = round(45.0)))).isEmpty)
            assertTrue("R 30 m at $kmh km/h excluded", GradePace.foldOf(track(decimated(kmh, path = round(30.0)))).isEmpty)
        }
        assertFalse("R 55 m at 35 km/h kept", GradePace.foldOf(track(decimated(35.0, path = round(55.0)))).isEmpty)
        assertFalse("the 400 m oval at 35 km/h kept", GradePace.foldOf(track(decimated(35.0, 6000.0, path = oval))).isEmpty)
    }

    @Test fun `a 50 m radius circuit sampled every 20 m - 23 deg turns - ridden 3 km is kept`() {
        val f = GradePace.foldOf(track(ridden(circuit(50.0, 3000.0))))
        assertFalse(f.isEmpty)
        assertTrue(f.totalM > 2500.0)
    }

    @Test fun `a 15 m radius circuit - 76 deg turns - is excluded, the documented residual`() {
        assertTrue(GradePace.foldOf(track(ridden(circuit(15.0, 3000.0)))).isEmpty)
    }

    @Test fun `hill repeats - 10x up and down a 900 m straight 12 pct road - are kept`() {
        val pts = ArrayList<P>()
        var d = 0.0; var t = 0.0
        for (lap in 0 until 10) for (leg in 0..1) for (k in 0..45) {
            if (pts.isNotEmpty() && k == 0) continue
            val s = if (leg == 0) k * 20.0 else 900.0 - k * 20.0
            if (pts.isNotEmpty()) { d += 20.0; t += if (leg == 0) 20.0 / 3.0 else 2.0 }
            pts += P(0.0, s, d, t, s * 0.12)
        }
        val f = GradePace.foldOf(track(pts))
        assertTrue("climb kept: ${f.sumDd}", (f.sumDd[12] ?: 0.0) > 5000.0)
        assertTrue("descent kept: ${f.sumDd}", (f.sumDd[-12] ?: 0.0) > 5000.0)
    }

    @Test fun `a first point 5 km away does not hide the drift that follows - the footprint is robust`() {
        val xy = listOf(5000.0 to 0.0) + drift(360)
        // The outlier's 5 km step is a dropout: distance jumps, the drift after it folds as before.
        val pts = ridden(xy, speed = 8.0, ele = { 50.0 })
        assertTrue(GradePace.foldOf(track(pts)).isEmpty)
    }

    @Test fun `fewer than 20 qualifying heading pairs never gate - 19 keep, 20 exclude`() {
        assertFalse(GradePace.foldOf(track(ridden(golden(21)))).isEmpty) // 20 steps -> 19 pairs
        assertTrue(GradePace.foldOf(track(ridden(golden(22)))).isEmpty) // 21 steps -> 20 pairs
    }

    /** Three straight 10-step sections (27 zero-turn pairs) and 30 lone steps, each in its own direction, inside
     *  ~225 m of home, joined by a step of [joinM] metres over [joinS] seconds. Pairing headings across the joins
     *  would add ~64 big turns and flip the median; reset at every join, only the 27 straight pairs count. */
    private fun sections(joinM: Double, joinS: Double): RecordedTrack {
        val pts = ArrayList<P>()
        var d = 0.0; var t = 0.0
        fun section(i: Int, steps: Int) {
            val (sx, sy) = golden(i + 1)[i]
            val a = Math.toRadians(i * 97.0)
            if (pts.isNotEmpty()) { d += joinM; t += joinS }
            for (k in 0..steps) {
                if (k > 0) { d += 20.0; t += 4.0 }
                pts += P(sx + k * 20 * sin(a), sy + k * 20 * cos(a), d, t)
            }
        }
        for (i in 0 until 33) section(i, if (i % 11 == 0) 10 else 1)
        return track(pts)
    }

    @Test fun `a dropout between coherent sections creates no fake heading change`() {
        assertFalse(GradePace.foldOf(sections(300.0, 60.0)).isEmpty)
    }

    @Test fun `a faulty step resets the heading pairing - duplicate timestamp, no distance, spike`() {
        // On isHomeDrift itself: 32 spike joins would also trip the motorised gate in foldOf.
        assertFalse("dt = 0", GradePace.isHomeDrift(sections(20.0, 0.0).points))
        assertFalse("dd = 0", GradePace.isHomeDrift(sections(0.0, 4.0).points))
        assertFalse("spike", GradePace.isHomeDrift(sections(150.0, 4.0).points))
    }

    @Test fun `slow drift - every other step a dwell below 0_5 m s - is still excluded, dwells carry the evidence`() {
        // foldOf drops the dwell steps but folds the others. Resetting the heading pairing at each dwell would
        // leave every folded step without a partner -> no verdict -> the jitter folds.
        val xy = golden(80)
        var d = 0.0; var t = 0.0
        val pts = xy.mapIndexed { i, (x, y) ->
            if (i > 0) { val c = hypot(x - xy[i - 1].first, y - xy[i - 1].second); d += c; t += c / (if (i % 2 == 0) 0.3 else 2.0) }
            P(x, y, d, t)
        }
        assertTrue(GradePace.foldOf(track(pts)).isEmpty)
    }

    // --- climbing corroboration: drift is (nearly) flat per metre, climbing is not ------------------------

    /** Tight switchbacks: [legs] legs of [legM] m, hairpins [pitchM] m apart, climbed and descended at [gradePct]
     *  along the path, [laps] times. Arc length -> east/north metres; [climbM] is one way. */
    private val legM = 35.0; private val pitchM = 8.0; private val legs = 12
    private val climbM = legs * (legM + pitchM)
    private val switchbacks: (Double) -> Pair<Double, Double> = { s0 ->
        val m = s0 % (2 * climbM)
        val s = if (m < climbM) m else 2 * climbM - m
        val k = (s / (legM + pitchM)).toInt().coerceAtMost(legs - 1)
        val r = s - k * (legM + pitchM)
        if (r < legM) (if (k % 2 == 0) r else legM - r) to k * pitchM
        else (if (k % 2 == 0) legM else 0.0) to k * pitchM + (r - legM)
    }
    private fun alongClimb(s0: Double): Double { val m = s0 % (2 * climbM); return if (m < climbM) m else 2 * climbM - m }

    @Test fun `tight switchback hill repeats - 35 m legs at 10 pct, footprint under 1 km - are kept`() {
        val pts = decimated(10.0, 8 * 2 * climbM, ele = { 100.0 + alongClimb(it) * 0.10 }, path = switchbacks)
        assertTrue("footprint", GradePace.robustFootprintM(track(pts).points) < DRIFT_FOOTPRINT_M)
        // Without altitude it circles like drift: only the climbing corroboration keeps it.
        assertTrue("the same path flat is drift", GradePace.isHomeDrift(track(pts.map { it.copy(e = 100.0) }).points))
        val f = GradePace.foldOf(track(pts))
        assertFalse("kept: ${f.sumDd}", f.isEmpty)
        assertTrue("climb folded: ${f.sumDd}", (f.sumDd[10] ?: 0.0) > 2000.0)
    }

    @Test fun `home drift with a slow baro drift - 146 m over 42 h - is still excluded`() {
        val n = 42 * 360
        val xy = drift(n, Random(7))
        val rnd = Random(8)
        var d = 0.0
        val pts = xy.mapIndexed { i, (x, y) ->
            if (i > 0) d += hypot(x - xy[i - 1].first, y - xy[i - 1].second)
            P(x, y, d, i * 10.0, 50.0 + 146.0 * i / n + rnd.nextDouble() - 0.5)
        }
        assertTrue(GradePace.foldOf(track(pts)).isEmpty)
    }

    @Test fun `a 30 m radius flat circuit with altitude is still excluded - the documented residual`() {
        assertTrue(GradePace.isHomeDrift(track(decimated(20.0, ele = { 100.0 }, path = round(30.0))).points))
    }

    @Test fun `a few climbing steps while the barometer settles do not veto drift - fewer than DRIFT_MIN_PAIRS`() {
        // Field fit-1735819644000: power-on, 11 graded steps at ~4 % while the baro settles, then hours parked.
        val xy = drift(400, Random(11))
        var d = 0.0
        var t = 0.0
        val pts = xy.mapIndexed { i, (x, y) ->
            if (i > 0) {
                d += hypot(x - xy[i - 1].first, y - xy[i - 1].second)
                t += if (i <= 14) 10.0 else 120.0 // moving while it settles, then dwell-speed jitter
            }
            P(x, y, d, t, if (i <= 14) 50.0 + 0.04 * d else 58.0)
        }
        assertTrue(GradePace.isHomeDrift(track(pts).points))
    }

    @Test fun `drift with no altitude at all is still drift - no gradient never blocks the verdict`() {
        val pts = track(ridden(golden(80))).points.map { it.copy(eleM = null) }
        assertTrue(GradePace.isHomeDrift(pts))
    }
}
