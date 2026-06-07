package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.import_.sourceKeyOf

/**
 * Accumulates an in-memory ride track, decimating samples by cumulative distance.
 *
 * Pure (no Android): [onSample] is driven by the live location/distance/elapsed streams in the
 * extension (Task 10), but the buffering/decimation here is unit-tested directly. The recorder is
 * single-use per ride: call [reset] when the ride ends (which also resets the [decimator]).
 */
class TrackRecorder(private val decimator: TrackDecimator = TrackDecimator()) {

    private val buffer = mutableListOf<TrackPoint>()

    /** Appends a [TrackPoint] iff the [decimator] decides this sample is far enough from the last. */
    fun onSample(lat: Double, lng: Double, distanceM: Double, timeS: Double) {
        if (decimator.shouldKeep(lat, lng, distanceM)) {
            buffer.add(TrackPoint(lat, lng, distanceM, timeS))
        }
    }

    /**
     * Builds a [RecordedTrack] from the decimated buffer, or null if fewer than 2 points were kept
     * (a single point cannot form a comparable segment).
     */
    fun build(id: String, startedAtEpoch: Long): RecordedTrack? {
        if (buffer.size < 2) return null
        // total = the LAST kept point's cumulative ride distance. Drives the dedup sourceKey so a
        // later FitFiles scan re-ingesting the SAME ride collapses onto this track (same key).
        val totalDistanceM = buffer.lastOrNull()?.distanceM ?: 0.0
        return RecordedTrack(
            id = id,
            startedAtEpoch = startedAtEpoch,
            points = buffer.map { it.toDto() },
            sourceKey = sourceKeyOf(startedAtEpoch, totalDistanceM),
            source = Source.RECORDED,
        )
    }

    /** Number of points currently buffered (mainly for tests / diagnostics). */
    fun size(): Int = buffer.size

    /** Clears the buffer and resets the decimator so the recorder can be reused for a new ride. */
    fun reset() {
        buffer.clear()
        decimator.reset()
    }
}
