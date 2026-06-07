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

    // The most recent sample fed to [onSample], regardless of whether the decimator kept it. The
    // genuine ride endpoint is usually < minSpacingM past the last kept point, so the decimator
    // drops it; [build] re-appends it so the track is not truncated and the sourceKey is stable.
    private var lastFed: TrackPoint? = null

    /** Appends a [TrackPoint] iff the [decimator] decides this sample is far enough from the last. */
    fun onSample(lat: Double, lng: Double, distanceM: Double, timeS: Double) {
        lastFed = TrackPoint(lat, lng, distanceM, timeS)
        if (decimator.shouldKeep(lat, lng, distanceM)) {
            buffer.add(TrackPoint(lat, lng, distanceM, timeS))
        }
    }

    /**
     * Builds a [RecordedTrack] from the decimated buffer, or null if fewer than 2 points were kept
     * (a single point cannot form a comparable segment).
     */
    fun build(id: String, startedAtEpoch: Long): RecordedTrack? {
        // Capture the DECIMATED tail (last KEPT point) BEFORE appending the endpoint: this is what
        // drives the dedup sourceKey. ③ (HistoryImporter.defaultDecimate) keys off its own decimated
        // tail (kept.lastOrNull()?.distanceM), so ② must do the same for the SAME ride to land in the
        // SAME 10 m bucket and dedup correctly. Keying off the true endpoint instead would put ② and
        // ③ up to ~minSpacingM apart → different bucket → dedup FAILS → duplicate track.
        val decimatedTotalM = buffer.lastOrNull()?.distanceM ?: 0.0

        // Always include the true ride endpoint in the returned POINTS: if the last fed sample was
        // decimated away (it is not already the last kept point), append it before snapshotting. This
        // restores the up-to ~minSpacingM of track that would otherwise be lost (track accuracy). The
        // endpoint is intentionally NOT used for the sourceKey — that keys off the decimated tail
        // above so it stays symmetric with ③ across re-ingests of the same ride.
        val fed = lastFed
        if (fed != null && buffer.lastOrNull() != fed) {
            buffer.add(fed)
        }
        if (buffer.size < 2) return null
        return RecordedTrack(
            id = id,
            startedAtEpoch = startedAtEpoch,
            points = buffer.map { it.toDto() },
            sourceKey = sourceKeyOf(startedAtEpoch, decimatedTotalM),
            source = Source.RECORDED,
        )
    }

    /** Number of points currently buffered (mainly for tests / diagnostics). */
    fun size(): Int = buffer.size

    /** Clears the buffer and resets the decimator so the recorder can be reused for a new ride. */
    fun reset() {
        buffer.clear()
        lastFed = null
        decimator.reset()
    }
}
