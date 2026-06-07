package com.enderthor.kvpartner.import_

import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.Polyline
import com.enderthor.kvpartner.geo.RecordedTrack
import com.enderthor.kvpartner.geo.Source
import com.enderthor.kvpartner.geo.TrackPoint
import com.enderthor.kvpartner.geo.toDto
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
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

        val records = ArrayList<RecordMesg>()
        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(RecordMesgListener { records.add(it) })
        FileInputStream(file).use { decode.read(it, broadcaster, broadcaster) }

        buildTrack(records, source)
    }.getOrElse { e ->
        Timber.w(e, "FIT decode failed: %s", file.name)
        null
    }

    private fun buildTrack(records: List<RecordMesg>, source: Source): RecordedTrack? {
        val points = ArrayList<TrackPoint>(records.size)
        var firstEpochMs: Long? = null
        var prev: LatLng? = null
        var cumulativeM = 0.0
        var lastDistanceM = 0.0

        for (mesg in records) {
            val latSemi = mesg.positionLat ?: continue
            val lngSemi = mesg.positionLong ?: continue
            val ts = mesg.timestamp ?: continue
            val epochMs = ts.date?.time ?: continue

            val lat = latSemi * SEMICIRCLES_TO_DEGREES
            val lng = lngSemi * SEMICIRCLES_TO_DEGREES
            val here = LatLng(lat, lng)

            if (firstEpochMs == null) firstEpochMs = epochMs

            // Distance: prefer the recorded cumulative metres; otherwise accumulate haversine.
            // Clamp to be monotonic non-decreasing regardless of source.
            val sdkDistance = mesg.distance?.toDouble()
            val rawDistance = when {
                sdkDistance != null -> sdkDistance
                prev != null -> cumulativeM + Polyline.haversineM(prev, here)
                else -> 0.0
            }
            cumulativeM = rawDistance
            val distanceM = if (rawDistance < lastDistanceM) lastDistanceM else rawDistance
            lastDistanceM = distanceM

            val base = firstEpochMs
            points.add(
                TrackPoint(
                    lat = lat,
                    lng = lng,
                    distanceM = distanceM,
                    timeS = (epochMs - base) / 1000.0,
                ),
            )
            prev = here
        }

        if (points.size < 2 || firstEpochMs == null) return null

        // Re-base distance so the first positioned record starts at 0.0.
        val firstDistanceM = points.first().distanceM
        val rebased = points.map { it.copy(distanceM = it.distanceM - firstDistanceM) }

        val totalDistanceM = rebased.last().distanceM
        val startedAtEpoch = firstEpochMs
        return RecordedTrack(
            id = "fit-$startedAtEpoch",
            startedAtEpoch = startedAtEpoch,
            points = rebased.map { it.toDto() },
            sourceKey = sourceKeyOf(startedAtEpoch, totalDistanceM),
            source = source,
        )
    }
}
