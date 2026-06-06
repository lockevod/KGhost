package com.enderthor.kvpartner.engine

import com.enderthor.kvpartner.geo.TrackPoint

/**
 * A [GhostSource] backed by recorded samples, normalized to be segment-relative
 * (first sample at distance 0, time 0). Sub-project ②'s implementation of the ① interface.
 */
class RecordedGhostSource private constructor(
    private val samples: List<GhostSample>,
    override val label: String,
) : GhostSource {
    override fun curve(): GhostCurve = GhostCurve(samples)

    companion object {
        /**
         * Builds a segment-relative source from a slice of a recorded track. The slice must be
         * ordered and strictly increasing in distance. Distances/times are shifted so the slice
         * starts at (0, 0); [GhostCurve]'s constructor enforces monotonicity.
         */
        fun fromTrackSlice(slice: List<TrackPoint>, label: String): RecordedGhostSource {
            require(slice.size >= 2) { "ghost slice needs at least 2 points" }
            val d0 = slice.first().distanceM
            val t0 = slice.first().timeS
            val samples = slice.map { GhostSample(it.distanceM - d0, it.timeS - t0) }
            return RecordedGhostSource(samples, label)
        }
    }
}
