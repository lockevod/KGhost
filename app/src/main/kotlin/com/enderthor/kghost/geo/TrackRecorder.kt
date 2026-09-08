package com.enderthor.kghost.geo

import com.enderthor.kghost.import_.sourceKeyOf

/**
 * Accumulates an in-memory ride track, decimating samples by cumulative distance.
 *
 * Pure (no Android): [onSample] is driven by the live location/distance/elapsed streams in the
 * extension (Task 10), but the buffering/decimation here is unit-tested directly. The recorder is
 * single-use per ride: call [reset] when the ride ends (which also resets the [decimator]).
 */
class TrackRecorder(
    private val decimator: TrackDecimator = TrackDecimator(),
    private val geometryDecimator: TrackDecimator = TrackDecimator(),
) {

    private val buffer = mutableListOf<TrackPoint>()

    // IDENTITY vs GEOMETRY — two jobs, two decimators, and conflating them is what makes the obvious
    // "skip stale fixes" fix dangerous in two separate ways:
    //
    //  - IDENTITY is the decimated distance tail [sourceKeyOf] buckets at 10 m, and it must keep
    //    behaving EXACTLY as it did before stale fixes were excluded. ③ (HistoryImporter's decimate
    //    over the FIT) computes its own; if ②'s moves, the same ride stored twice stops deduping and
    //    the twin pair never self-heals (selectArchivable leaves groups of <= 3 alone forever), so
    //    that route is double-counted in AVERAGE for good. So [decimator] is advanced on every sample
    //    once a fix has EVER arrived — precisely the old feeding condition — and nothing else.
    //  - GEOMETRY is the point list, and a stale fix with a live odometer writes a run of points all
    //    sharing one coordinate, with a degenerate bearing, which then feeds route matching, GradePace
    //    and CorridorSeeder on every LATER ride.
    //
    // They need SEPARATE decimators. Sharing one lets unpositioned samples claim the keep slots: with
    // 20 m spacing and positions arriving only between the anchors, every real position is dropped and
    // build() returns null — a ride with plenty of usable geometry silently lost, which is worse than
    // the duplicated coordinates this excludes.
    //
    // What this does NOT fix: ② keys off the ABSOLUTE odometer while FitDecoder rebases to its first
    // POSITIONED record, so a ride whose GPS locks late already keys differently on the two paths.
    // That gap pre-dates this change and is untouched by it.
    private var identityTailM: Double? = null
    private var everPositioned = false

    // The most recent sample that carried a usable position. The genuine ride endpoint is usually
    // < minSpacingM past the last kept point, so the decimator drops it; [build] re-appends it so the
    // track is not truncated. A stale sample must never become the endpoint.
    private var lastFed: TrackPoint? = null

    /**
     * Feeds one sample. [lat]/[lng] are null when there is no usable position (no fix yet, or one too
     * old to trust): the sample still advances the identity tail, but contributes no geometry.
     *
     * The parameter ORDER is deliberately unchanged from when lat/lng were non-null — every parameter
     * is a Double, so reordering them would leave every existing call compiling while silently meaning
     * something else.
     */
    fun onSample(lat: Double?, lng: Double?, distanceM: Double, timeS: Double) {
        val positioned = lat != null && lng != null
        if (positioned) {
            everPositioned = true
            lastFed = TrackPoint(lat, lng, distanceM, timeS)
        }
        // Identity: every sample once a fix has ever arrived, which is what the pre-gate code fed.
        if (everPositioned && decimator.shouldKeep(distanceM)) identityTailM = distanceM
        // Geometry: positioned samples only, on their OWN lattice, so a stale stretch cannot starve it.
        if (positioned && geometryDecimator.shouldKeep(distanceM)) {
            buffer.add(TrackPoint(lat, lng, distanceM, timeS))
        }
    }

    /**
     * Builds a [RecordedTrack] from the decimated buffer, or null if fewer than 2 points were kept
     * (a single point cannot form a comparable segment).
     */
    fun build(id: String, startedAtEpoch: Long): RecordedTrack? {
        // The IDENTITY tail, captured BEFORE the endpoint append. Not `buffer.last()`: the two differ
        // exactly when samples were dropped for want of a position, and it is this one that reproduces
        // what the recorder keyed before stale fixes were excluded.
        val decimatedTotalM = identityTailM ?: 0.0

        // Always include the true ride endpoint in the returned POINTS: if the last positioned sample
        // was decimated away, append it before snapshotting. The endpoint is intentionally NOT used for
        // the sourceKey — that keys off the identity tail above.
        val fed = lastFed
        if (fed != null && fed.distanceM > (buffer.lastOrNull()?.distanceM ?: Double.NEGATIVE_INFINITY)) {
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

    /** Metres the identity tail reached, for diagnostics: it separates "a stationary ride" from
     *  "a full-length ride that never got a usable fix", which both surface as a null [build]. */
    fun identityDistanceM(): Double = identityTailM ?: 0.0

    /** Clears the buffer and resets the decimator so the recorder can be reused for a new ride. */
    fun reset() {
        buffer.clear()
        lastFed = null
        identityTailM = null
        everPositioned = false
        decimator.reset()
        geometryDecimator.reset()
    }
}
