package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPoint
import com.enderthor.kghost.geo.toDto
import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import com.garmin.fit.SportMesgListener
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import kotlin.math.pow
import com.garmin.fit.File as FitFileType

/**
 * Is this FIT file a bike ride, given the activity type and the sports its session/sport messages
 * declare? Pure and total, so the rejection path is testable without a car-ride FIT file.
 *
 * Rejects only on PRESENT, non-cycling data: a legitimate re-export can omit `sport` entirely, and
 * dropping real rides is worse than the leak this closes. `Sport.INVALID` is the SDK's "no value", so
 * the caller must not collect it. `subSport` is deliberately ignored — an INDOOR_CYCLING trainer ride
 * is still cycling. A multisport file counts as cycling if ANY session does (the brick's bike leg is
 * real history).
 *
 * Motivation: `AGG_MAX_SPEED_MS` is 30 m/s, so an unstopped drive home over a col imports as riding
 * and permanently teaches `GradePace` that 6% is a 23 km/h gradient — the model has no recency decay.
 */
internal fun isCyclingActivity(fileType: FitFileType?, sports: Set<Sport>): Boolean =
    (fileType == null || fileType == FitFileType.ACTIVITY) &&
        (sports.isEmpty() || Sport.CYCLING in sports)

/**
 * Decodes a Garmin FIT activity file into a [RecordedTrack] using the official Garmin FIT Java SDK.
 *
 * Only `record` messages carrying a valid GPS position are kept. The decode is single-pass: there is
 * no separate upfront integrity check. Any failure (corrupt/truncated file, IO error, SDK runtime
 * error) surfaces as an exception from `decode.read`, which is swallowed and yields null — never throws.
 */
object FitDecoder {

    /** Semicircles -> degrees: deg = semicircles * 180 / 2^31. */
    private val SEMICIRCLES_TO_DEGREES = 180.0 / 2.0.pow(31)

    fun decode(file: File, source: Source): RecordedTrack? = runCatching {
        val decode = Decode()

        // Build lightweight TrackPoints AS records stream in, instead of buffering every heavy
        // RecordMesg first. A multi-hour FIT is ~20k records; holding them all is tens of MB of SDK
        // objects on a 1–2 GB device (OOM risk in a bulk import). The listener processes each record
        // and drops it; only the TrackPoint list (4 doubles + an optional altitude each) is retained.
        // Captured vars are safe: decode.read drives the listener sequentially on this thread.
        val points = ArrayList<TrackPoint>()
        var firstEpochMs: Long? = null
        var prev: LatLng? = null
        var cumulativeM = 0.0
        var lastDistanceM = 0.0

        // Activity-type gate, evaluated after the read: `session` sits at the END of the file, so an
        // early abort would need an exception for no real gain (a rejected file is rare).
        // TYPED listeners are mandatory: MesgBroadcaster's generic MesgListener hands back the RAW
        // unconverted Mesg, so `is FileIdMesg` / `is SessionMesg` never match there.
        var fitFileType: FitFileType? = null
        val sports = HashSet<Sport>()

        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(FileIdMesgListener { mesg -> mesg.type?.let { fitFileType = it } })
        broadcaster.addListener(
            SessionMesgListener { mesg -> mesg.sport?.takeIf { it != Sport.INVALID }?.let { sports += it } },
        )
        broadcaster.addListener(
            SportMesgListener { mesg -> mesg.sport?.takeIf { it != Sport.INVALID }?.let { sports += it } },
        )
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

                    // enhanced_altitude is the modern field (wider range, metres); altitude is the legacy fallback.
                    // Both are nullable Float in the Garmin SDK and are already metres — no scaling.
                    // NOT part of the lat/lng/timestamp gate: a record with no altitude is still a usable point.
                    val eleM = (mesg.enhancedAltitude ?: mesg.altitude)?.toDouble()

                    points.add(
                        TrackPoint(
                            lat = lat,
                            lng = lng,
                            distanceM = distanceM,
                            timeS = (epochMs - firstEpochMs!!) / 1000.0,
                            eleM = eleM,
                        ),
                    )
                    prev = here
                }
            },
        )
        FileInputStream(file).use { decode.read(it, broadcaster, broadcaster) }

        if (!isCyclingActivity(fitFileType, sports)) {
            Timber.i("FIT skipped, not a cycling activity (type=%s sports=%s): %s", fitFileType, sports, file.name)
            null
        } else {
            buildTrack(points, firstEpochMs, source)
        }
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
