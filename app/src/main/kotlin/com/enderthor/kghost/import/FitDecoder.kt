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
 * The sports that count as "a ride on my bike". `CYCLING` is the ordinary case; `E_BIKING` (21) is what
 * an e-bike profile writes and is still the rider on a bicycle; `GENERIC` (0) is the SDK's zero value
 * and is what plenty of converters/re-exporters emit when they have nothing better — dropping it would
 * silently lose a real ride, which is worse than the leak (that is the same judgement the empty-set case
 * below makes). `subSport` is deliberately ignored — an INDOOR_CYCLING trainer ride is still cycling.
 */
private val RIDING_SPORTS = setOf(Sport.CYCLING, Sport.E_BIKING, Sport.GENERIC)

/**
 * Is this FIT file a bike ride, given the activity type and the sports its session/sport messages
 * declare? Pure and total, so the rejection path is testable without a car-ride FIT file.
 *
 * Rejects only on PRESENT, KNOWN, non-riding data: a legitimate re-export can omit `sport` entirely, and
 * dropping real rides is worse than the leak this closes. `Sport.INVALID` is the SDK's "no value", so
 * the caller must not collect it. A multisport file counts as cycling if ANY session does (the brick's
 * bike leg is real history).
 *
 * The file-type half rejects a KNOWN non-ACTIVITY type — a `COURSE` carries positioned records and would
 * otherwise decode as a fake ride — but ACCEPTS [FitFileType.INVALID], which is not a known-bad type: it
 * is what `File.getByValue` returns for a value THIS SDK version does not recognise. Rejecting unknown
 * would drop a real activity written by a newer device for no gain.
 *
 * Motivation: `AGG_MAX_SPEED_MS` is 30 m/s, so an unstopped drive home over a col imports as riding
 * and permanently teaches `GradePace` that 6% is a 23 km/h gradient — the model has no recency decay.
 */
internal fun isCyclingActivity(fileType: FitFileType?, sports: Set<Sport>): Boolean =
    (fileType == null || fileType == FitFileType.ACTIVITY || fileType == FitFileType.INVALID) &&
        (sports.isEmpty() || sports.any { it in RIDING_SPORTS })

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

    /**
     * The verdict "this file decoded perfectly well and is NOT a bike ride": an EMPTY track rather than
     * null. Null is the importer's TRANSIENT bucket — never ledgered, re-decoded on every import for the
     * life of the install, and counted in the rider's "N not valid" every time — which is right for a
     * truncated or mid-write file that may become readable later, and wrong for the sport/file-type gate,
     * whose verdict is as deterministic as the sibling "decimated to <2 points" one. An empty track lands
     * in that sibling's bucket ([HistoryImporter]'s `< 2 points` branch → `Invalid`), so the file is
     * decoded ONCE and then ledgered.
     *
     * If the gate later WIDENS (a sport added to `RIDING_SPORTS`), the ledger entry would otherwise skip
     * the file forever — its size/mtime have not changed. "Rebuild history" is the recovery: it DELETES
     * `processed.json` ([resetImportDedup]), so every previously-rejected file is re-decoded under the new
     * gate. ponytail: a gate-version field in the ledger would automate that, at the cost of a schema and
     * a full re-decode on every version bump; one documented button press is cheaper on a bike computer.
     */
    internal fun notARide(source: Source): RecordedTrack =
        RecordedTrack(id = "", startedAtEpoch = 0L, points = emptyList(), source = source)

    /**
     * [decodeForImport], with the non-ride sentinel folded back into null — the "null for anything not
     * usable as a ride" contract every non-importer caller reads this by.
     */
    fun decode(file: File, source: Source): RecordedTrack? =
        decodeForImport(file, source)?.takeIf { it.points.isNotEmpty() }

    /**
     * [decode] for the import pipeline, which needs the two rejections told apart: a gate rejection comes
     * back as [notARide] (deterministic → ledgered after one decode), everything else as null (transient
     * → retried). See [notARide].
     */
    fun decodeForImport(file: File, source: Source): RecordedTrack? = runCatching {
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
            notARide(source)
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
