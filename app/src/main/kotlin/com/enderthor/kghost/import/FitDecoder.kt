package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPoint
import com.enderthor.kghost.geo.toDto
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import kotlin.math.pow

/**
 * Decodes a Garmin FIT activity file into a [RecordedTrack] using the official Garmin FIT Java SDK.
 *
 * Only `record` messages carrying a valid GPS position are kept. The integrity check consumes the
 * input stream, so it runs on a SEPARATE [FileInputStream] from the actual decode. Any failure
 * (corrupt/truncated file, IO error, SDK runtime error) is swallowed and yields null — never throws.
 */
object FitDecoder {

    /** Semicircles -> degrees: deg = semicircles * 180 / 2^31. */
    private val SEMICIRCLES_TO_DEGREES = 180.0 / 2.0.pow(31)

    fun decode(file: File, source: Source): RecordedTrack? = runCatching {
        val decode = Decode()
        // checkFileIntegrity consumes the stream, so use a throwaway stream here.
        FileInputStream(file).use { if (!decode.checkFileIntegrity(it)) return null }

        // Build lightweight TrackPoints AS records stream in, instead of buffering every heavy
        // RecordMesg first. A multi-hour FIT is ~20k records; holding them all is tens of MB of SDK
        // objects on a 1–2 GB device (OOM risk in a bulk import). The listener processes each record
        // and drops it; only the TrackPoint list (4 doubles each) is retained. Captured vars are safe:
        // decode.read drives the listener sequentially on this thread.
        val points = ArrayList<TrackPoint>()
        var firstEpochMs: Long? = null
        var prev: LatLng? = null
        var cumulativeM = 0.0
        var lastDistanceM = 0.0

        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(
            RecordMesgListener { mesg ->
                val latSemi = mesg.positionLat
                val lngSemi = mesg.positionLong
                val epochMs = mesg.timestamp?.date?.time
                if (latSemi != null && lngSemi != null && epochMs != null) {
                    val lat = latSemi * SEMICIRCLES_TO_DEGREES
                    val lng = lngSemi * SEMICIRCLES_TO_DEGREES
                    val here = LatLng(lat, lng)
                    if (firstEpochMs == null) firstEpochMs = epochMs

                    // Distance: prefer the recorded cumulative metres; otherwise accumulate haversine.
                    // Clamp to be monotonic non-decreasing regardless of source.
                    val sdkDistance = mesg.distance?.toDouble()
                    val rawDistance = when {
                        sdkDistance != null -> sdkDistance
                        prev != null -> cumulativeM + Polyline.haversineM(prev!!, here)
                        else -> 0.0
                    }
                    cumulativeM = rawDistance
                    val distanceM = if (rawDistance < lastDistanceM) lastDistanceM else rawDistance
                    lastDistanceM = distanceM

                    points.add(
                        TrackPoint(
                            lat = lat,
                            lng = lng,
                            distanceM = distanceM,
                            timeS = (epochMs - firstEpochMs!!) / 1000.0,
                        ),
                    )
                    prev = here
                }
            },
        )
        FileInputStream(file).use { decode.read(it, broadcaster, broadcaster) }

        buildTrack(points, firstEpochMs, source)
    }.getOrElse { e ->
        Timber.w(e, "FIT decode failed: %s", file.name)
        null
    }

    private fun buildTrack(points: List<TrackPoint>, firstEpochMs: Long?, source: Source): RecordedTrack? {
        if (points.size < 2 || firstEpochMs == null) return null

        // Re-base distance so the first positioned record starts at 0.0.
        val firstDistanceM = points.first().distanceM
        val rebased = points.map { it.copy(distanceM = it.distanceM - firstDistanceM) }

        val totalDistanceM = rebased.last().distanceM
        return RecordedTrack(
            id = "fit-$firstEpochMs",
            startedAtEpoch = firstEpochMs,
            points = rebased.map { it.toDto() },
            sourceKey = sourceKeyOf(firstEpochMs, totalDistanceM),
            source = source,
        )
    }
}
