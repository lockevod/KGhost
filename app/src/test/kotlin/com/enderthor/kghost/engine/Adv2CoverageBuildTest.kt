package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.import_.FitDecoder
import com.enderthor.kghost.import_.HistoryImporter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

/**
 * ADVERSARIAL MEASUREMENT (not a behaviour lock): how much of a real 211-ride library survives every
 * GradePace build-side filter, and how often tier 2 can actually answer on a novel ride.
 *
 * Reads the maintainer's own FIT library from ~/Downloads (override with ADV2_FIT_DIR). Skips cleanly
 * when absent. Prints a report; asserts only the coarse invariants so it cannot rot into a false green.
 *
 * Run:
 *   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
 *   ./gradlew :app:testDebugUnitTest --tests "*Adv2Cover*" --console=plain
 */
class Adv2CoverageBuildTest {

    // ---------- shared library loading (decode + decimate + dedup), memoised per JVM ----------

    companion object {
        private var cache: List<RecordedTrack>? = null
        private var stats: LoadStats? = null
        var rejectDetail: Map<String, Int> = emptyMap()

        /** Why did FitDecoder return null? Re-reads the file with the TYPED listeners (the generic
         *  MesgListener hands back a raw Mesg and never matches), so a non-cycling rejection is told
         *  apart from a corrupt/positionless file. */
        fun classifyReject(f: File): String = runCatching {
            var type: com.garmin.fit.File? = null
            val sports = HashSet<com.garmin.fit.Sport>()
            var gps = 0
            var records = 0
            val decode = com.garmin.fit.Decode()
            val bc = com.garmin.fit.MesgBroadcaster(decode)
            bc.addListener(com.garmin.fit.FileIdMesgListener { m -> m.type?.let { type = it } })
            bc.addListener(com.garmin.fit.SessionMesgListener { m -> m.sport?.takeIf { it != com.garmin.fit.Sport.INVALID }?.let { sports += it } })
            bc.addListener(com.garmin.fit.SportMesgListener { m -> m.sport?.takeIf { it != com.garmin.fit.Sport.INVALID }?.let { sports += it } })
            bc.addListener(com.garmin.fit.RecordMesgListener { m ->
                records++
                if (m.positionLat != null && m.positionLong != null && m.timestamp != null) gps++
            })
            java.io.FileInputStream(f).use { decode.read(it, bc, bc) }
            when {
                type != null && type != com.garmin.fit.File.ACTIVITY && type != com.garmin.fit.File.INVALID -> "not-an-ACTIVITY ($type)"
                sports.isNotEmpty() && sports.none { it == com.garmin.fit.Sport.CYCLING || it == com.garmin.fit.Sport.E_BIKING || it == com.garmin.fit.Sport.GENERIC } -> "non-cycling sport $sports"
                gps < 2 -> "no GPS at all (indoor?) sports=$sports"
                gps < 20 -> "too few GPS points ($gps)"
                else -> "decoded-but-invalid track (gps=$gps)"
            }
        }.getOrElse { "decode threw: ${it::class.simpleName}" }

        data class LoadStats(val files: Int, val decoded: Int, val rejected: Int, val deduped: Int)

        fun library(): Pair<List<RecordedTrack>, LoadStats> {
            cache?.let { return it to stats!! }
            val dir = File(System.getenv("ADV2_FIT_DIR") ?: (System.getProperty("user.home") + "/Downloads"))
            val files = ArrayList<File>()
            fun scan(d: File, depth: Int) {
                if (depth > 8) return
                d.listFiles()?.forEach { f ->
                    if (f.isDirectory) scan(f, depth + 1)
                    else if (f.name.lowercase().endsWith(".fit")) files += f
                }
            }
            scan(dir, 0)
            files.sortBy { it.name }
            assumeTrue("FIT library present under $dir", files.size >= 20)
            rejectDetail = files.parallelStream()
                .filter { f -> runCatching { FitDecoder.decode(f, Source.FIT_IMPORT) }.getOrNull() == null }
                .map { f -> classifyReject(f) }.toList().groupingBy { it }.eachCount()
            val decoded = files.parallelStream()
                .map { f -> runCatching { FitDecoder.decode(f, Source.FIT_IMPORT) }.getOrNull() }
                .filter { it != null }.map { it!! }
                .map { HistoryImporter.defaultDecimate(it) }
                .toList()
            val byKey = LinkedHashMap<String, RecordedTrack>()
            decoded.forEach { t -> byKey.putIfAbsent(t.sourceKey, t) }
            val tracks = byKey.values.sortedBy { it.startedAtEpoch }
            cache = tracks
            stats = LoadStats(files.size, decoded.size, files.size - decoded.size, decoded.size - tracks.size)
            return tracks to stats!!
        }

        /** Per-track, per-bin (seconds, metres) exactly as GradePace.Builder.add folds them, plus the
         *  metre accounting of everything it threw away. */
        fun fold(track: RecordedTrack, acc: Loss? = null): Fold {
            val pts = track.points
            val dt = HashMap<Int, Double>()
            val dd = HashMap<Int, Double>()
            if (pts.size < 2) return Fold(track.startedAtEpoch, dt, dd)
            var j = 0
            for (i in 1 until pts.size) {
                val here = pts[i]; val prev = pts[i - 1]
                while (j < i - 1 && here.distanceM - pts[j + 1].distanceM >= GRADE_WINDOW_M) j++
                val stepM = here.distanceM - prev.distanceM
                val stepT = here.timeS - prev.timeS
                acc?.let { it.rawM += maxOf(0.0, stepM) }
                if (stepM > TrackSamples.DROPOUT_GAP_M) { acc?.let { it.gapM += stepM }; j = i; continue }
                if (stepM <= 0.0 || stepT <= 0.0) { acc?.let { it.zeroSteps++ }; continue }
                val v = stepM / stepT
                if (v > AGG_MAX_SPEED_MS) { acc?.let { it.spikeM += stepM }; j = i; continue }
                if (v < AGG_MIN_SPEED_MS) { acc?.let { it.dwellM += stepM }; continue }
                val ele = here.eleM
                val back = pts[j]
                val backEle = back.eleM
                if (ele == null || backEle == null) { acc?.let { it.noEleM += stepM }; continue }
                val span = here.distanceM - back.distanceM
                if (span < GRADE_WINDOW_M) { acc?.let { it.windowM += stepM }; continue }
                val g = (ele - backEle) / span * 100.0
                if (!g.isFinite() || abs(g) > GRADE_MAX_PCT) { acc?.let { it.rangeM += stepM }; continue }
                val b = GradePace.binOf(g)
                dt[b] = (dt[b] ?: 0.0) + stepT
                dd[b] = (dd[b] ?: 0.0) + stepM
                acc?.let { it.foldedM += stepM }
            }
            return Fold(track.startedAtEpoch, dt, dd)
        }

        class Fold(val epoch: Long, val dt: Map<Int, Double>, val dd: Map<Int, Double>)

        class Loss {
            var rawM = 0.0; var gapM = 0.0; var zeroSteps = 0; var spikeM = 0.0; var dwellM = 0.0
            var noEleM = 0.0; var windowM = 0.0; var rangeM = 0.0; var foldedM = 0.0
        }

        fun pct(a: Double, b: Double) = if (b <= 0) 0.0 else 100.0 * a / b
    }

    @Test fun `build-side coverage of the real library`() {
        val (tracks, ls) = library()
        val loss = Loss()
        val folds = tracks.map { fold(it, loss) }

        val noEleTracks = tracks.count { t -> t.points.none { it.eleM != null } }
        val noEleTrackM = tracks.filter { t -> t.points.none { it.eleM != null } }
            .sumOf { it.points.lastOrNull()?.distanceM ?: 0.0 }

        // Final fold, oldest-first (metre-weighted mean == Σdt/Σdd, so totals suffice).
        val binM = HashMap<Int, Double>(); val binT = HashMap<Int, Double>(); val binN = HashMap<Int, Int>()
        for (f in folds.sortedBy { it.epoch }) for ((b, d) in f.dd) {
            if (d <= 0.0) continue
            binM[b] = (binM[b] ?: 0.0) + d
            binT[b] = (binT[b] ?: 0.0) + (f.dt[b] ?: 0.0)
            binN[b] = (binN[b] ?: 0) + 1
        }
        val qualBins = binM.filterValues { it >= GRADE_MIN_BIN_M }
        val qualM = qualBins.values.sum()

        println("=== ADV2 §1 BUILD COVERAGE (real library) ===")
        println("files=${ls.files} decoded=${ls.decoded} rejected/failed=${ls.rejected} dup-collapsed=${ls.deduped} usable rides=${tracks.size}")
        rejectDetail.entries.sortedByDescending { it.value }.forEach { println("  reject: ${it.value} x ${it.key}") }
        println("rides with NO altitude at all: $noEleTracks (${"%.1f".format(pct(noEleTracks.toDouble(), tracks.size.toDouble()))}%), ${"%.0f".format(noEleTrackM / 1000)} km")
        println("--- metres surviving each build filter (of %.0f km ridden) ---".format(loss.rawM / 1000))
        val r = loss.rawM
        fun line(name: String, lost: Double) = println(
            "  %-26s -%8.1f km  (%5.2f%%)".format(name, lost / 1000, pct(lost, r))
        )
        line("dropout gap (>200 m)", loss.gapM)
        line("spike (>30 m/s)", loss.spikeM)
        line("dwell (<0.5 m/s)", loss.dwellM)
        line("no altitude", loss.noEleM)
        line("window <100 m (warm-up)", loss.windowM)
        line("|grade|>20% at build", loss.rangeM)
        println("  %-26s  %8.1f km  (%5.2f%%)   zero/backward steps: %d".format("FOLDED", loss.foldedM / 1000, pct(loss.foldedM, r), loss.zeroSteps))
        println("bins occupied=${binM.size}  bins >=${GRADE_MIN_BIN_M.toInt()}m=${qualBins.size}  metres in qualifying bins=${"%.1f".format(qualM / 1000)} km (${"%.2f".format(pct(qualM, r))}%% of ridden)")
        println("--- bin table (bin%, km, rides, mean km/h) ---")
        binM.keys.sorted().forEach { b ->
            val m = binM[b]!!; val t = binT[b]!!
            println("  %+3d%%  %8.2f km  n=%3d  %5.1f km/h  %s".format(b, m / 1000, binN[b], 3.6 * m / t, if (m >= GRADE_MIN_BIN_M) "" else "<-- BELOW FLOOR"))
        }
        // Head-room: what the floor and the warm-up actually cost in bins.
        val nearFloor = binM.filterValues { it < GRADE_MIN_BIN_M }
        println("bins killed by the 400 m floor: ${nearFloor.size} (${"%.0f".format(nearFloor.values.sum())} m total)")

        assertTrueMsg(tracks.size >= 20, "expected a real library")
        assertTrueMsg(qualBins.isNotEmpty(), "no bin cleared the floor")
    }

    private fun assertTrueMsg(c: Boolean, m: String) { if (!c) throw AssertionError(m) }
}

/** PacePatch's exact keying, replicated so the harness can ask "would tier 1 have answered for a rider
 *  whose OWN ride is not in the map" (leave-one-ride-out) without rebuilding the map 200 times. */
internal object CellMap {
    const val BINS = 8
    fun bearingBin(deg: Double) = (((deg % 360 + 360) % 360) / 45.0).toInt().coerceIn(0, 7)
    fun pack(i: Int, j: Int, b: Int): Long =
        ((i.toLong() and 0x1FFFFFFF) shl 35) or ((j.toLong() and 0x1FFFFFFF) shl 6) or (b.toLong() and 0x3F)

    /** key -> (firstTrackIdx shl 1) | multiFlag. Enough to answer LORO membership without storing sets. */
    fun build(tracks: List<RecordedTrack>): HashMap<Long, Long> {
        val refLat = tracks.firstOrNull()?.points?.firstOrNull()?.lat ?: 0.0
        val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
        val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * kotlin.math.cos(Math.toRadians(refLat)))
        val map = HashMap<Long, Long>(1 shl 20)
        tracks.forEachIndexed { idx, t ->
            TrackSamples.forEach(t) { s ->
                val k = pack(floor(s.lat / latStep).toInt(), floor(s.lng / lngStep).toInt(), bearingBin(s.bearingDeg))
                val cur = map[k]
                if (cur == null) map[k] = idx.toLong() shl 1
                else if ((cur and 1L) == 0L && (cur shr 1).toInt() != idx) map[k] = cur or 1L
            }
        }
        return map
    }

    fun steps(refLat: Double): Pair<Double, Double> {
        val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
        val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * kotlin.math.cos(Math.toRadians(refLat)))
        return latStep to lngStep
    }

    /** Would PacePatch answer at this point for a rider whose own ride (selfIdx) is excluded? */
    fun hit(map: Map<Long, Long>, lat: Double, lng: Double, bearing: Double, selfIdx: Int,
            latStep: Double, lngStep: Double, neighbourhood: Boolean): Boolean {
        val ci = floor(lat / latStep).toInt(); val cj = floor(lng / lngStep).toInt(); val bb = bearingBin(bearing)
        fun other(k: Long): Boolean {
            val v = map[k] ?: return false
            return (v and 1L) == 1L || (v shr 1).toInt() != selfIdx
        }
        if (other(pack(ci, cj, bb))) return true
        if (!neighbourhood) return false
        for (di in -1..1) for (dj in -1..1) for (db in -1..1) {
            if (di == 0 && dj == 0 && db == 0) continue
            if (other(pack(ci + di, cj + dj, ((bb + db) % BINS + BINS) % BINS))) return true
        }
        return false
    }
}

/** Straight-line bearing between two decimated points (same helper PacePatch's samples use). */
internal fun bearingOf(a: com.enderthor.kghost.geo.TrackPointDto, b: com.enderthor.kghost.geo.TrackPointDto): Double =
    Polyline.bearingDeg(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
