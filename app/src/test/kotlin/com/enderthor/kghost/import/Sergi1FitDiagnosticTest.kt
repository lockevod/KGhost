package com.enderthor.kghost.import_

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import com.enderthor.kghost.geo.Source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.pow

/**
 * Throwaway diagnostic for the day-28 "Sergi1" ride the simulator refuses to replay.
 *
 * Pull the FIT to this path first, e.g.:
 *   adb pull /sdcard/FitFiles/<file>.fit /Users/sergi/AndroidStudioProjects/KGhost/sergi1.fit
 * or point SERGI1_FIT at any absolute path.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests "*Sergi1FitDiagnostic*" --console=plain -i
 * (skips cleanly if the file is absent).
 */
class Sergi1FitDiagnosticTest {

    private val SEMI_TO_DEG = 180.0 / 2.0.pow(31)

    private fun fitOrSkip(): File {
        val path = System.getenv("SERGI1_FIT")
            ?: "/Users/sergi/AndroidStudioProjects/KGhost/sergi1.fit"
        val f = File(path)
        assumeTrue("Sergi1 FIT present at $path", f.exists())
        return f
    }

    @Test fun `diagnose the day-28 FIT`() {
        val file = fitOrSkip()
        println("=== Sergi1 FIT diagnostic: ${file.absolutePath} (${file.length()} bytes) ===")

        // 1) Integrity check (own stream — it consumes the input).
        val integrity = runCatching {
            FileInputStream(file).use { Decode().checkFileIntegrity(it) }
        }.getOrElse { e -> println("integrity threw: $e"); false }
        println("checkFileIntegrity = $integrity")

        // 2) Walk every record message, bucket by what it carries.
        var total = 0
        var withGps = 0
        var noLat = 0
        var noLng = 0
        var noTs = 0
        var withDistance = 0
        var firstEpoch: Long? = null
        var lastEpoch: Long? = null
        var firstDist: Double? = null
        var lastDist: Double? = null

        val decode = Decode()
        val bc = MesgBroadcaster(decode)
        bc.addListener(
            RecordMesgListener { m ->
                total++
                val lat = m.positionLat
                val lng = m.positionLong
                val ts = m.timestamp?.date?.time
                if (lat == null) noLat++
                if (lng == null) noLng++
                if (ts == null) noTs++
                m.distance?.let {
                    withDistance++
                    if (firstDist == null) firstDist = it.toDouble()
                    lastDist = it.toDouble()
                }
                if (lat != null && lng != null && ts != null) {
                    withGps++
                    if (firstEpoch == null) firstEpoch = ts
                    lastEpoch = ts
                }
            },
        )
        val readOk = runCatching {
            FileInputStream(file).use { decode.read(it, bc, bc) }
            true
        }.getOrElse { e -> println("decode.read threw: $e"); false }

        println("decode.read completed = $readOk")
        println("record mesgs total          = $total")
        println("  with GPS + timestamp       = $withGps  <-- these are what the app/sim replays")
        println("  missing positionLat        = $noLat")
        println("  missing positionLong       = $noLng")
        println("  missing timestamp          = $noTs")
        println("  with distance field        = $withDistance")
        if (firstEpoch != null) {
            val durS = (lastEpoch!! - firstEpoch!!) / 1000.0
            println("first GPS epoch = $firstEpoch  last = $lastEpoch  duration = ${durS}s (${durS / 60} min)")
        }
        if (firstDist != null) {
            println("distance first = $firstDist m  last = $lastDist m  span = ${lastDist!! - firstDist!!} m")
        }

        // 3) What the real app decoder makes of it.
        val track = FitDecoder.decode(file, Source.FIT_IMPORT)
        println("FitDecoder.decode -> ${if (track == null) "NULL (app would reject this FIT)" else "OK"}")
        if (track != null) {
            println("  points=${track.points.size} startedAtEpoch=${track.startedAtEpoch}")
            println("  lastTimeS=${track.points.last().timeS} totalDistanceM=${track.points.last().distanceM}")
        }
        println("=== end diagnostic ===")
    }
}
