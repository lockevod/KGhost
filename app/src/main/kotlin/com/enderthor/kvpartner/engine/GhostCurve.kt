package com.enderthor.kvpartner.engine

/**
 * Monotonic (distance, time) ghost curve. Spec model C: bidirectional linear interpolation.
 * Immutable. Samples must be strictly increasing in distance and non-decreasing in time.
 */
class GhostCurve(val samples: List<GhostSample>) {
    init {
        require(samples.size >= 2) { "GhostCurve needs at least 2 samples" }
        for (i in 1 until samples.size) {
            require(samples[i].distanceM > samples[i - 1].distanceM) { "non-monotonic distance at $i" }
            require(samples[i].timeS >= samples[i - 1].timeS) { "decreasing time at $i" }
        }
    }

    val totalDistanceM: Double get() = samples.last().distanceM
    val totalTimeS: Double get() = samples.last().timeS

    /** Ghost time at [distanceM] metres. Clamps to the endpoints when out of range. */
    fun timeAt(distanceM: Double): Double {
        if (distanceM <= samples.first().distanceM) return samples.first().timeS
        if (distanceM >= samples.last().distanceM) return samples.last().timeS
        val hi = samples.indexOfFirst { it.distanceM >= distanceM }
        val a = samples[hi - 1]; val b = samples[hi]
        val f = (distanceM - a.distanceM) / (b.distanceM - a.distanceM)
        return a.timeS + f * (b.timeS - a.timeS)
    }

    /** Ghost distance at time [timeS]. Clamps to the endpoints when out of range. */
    fun distanceAt(timeS: Double): Double {
        if (timeS <= samples.first().timeS) return samples.first().distanceM
        if (timeS >= samples.last().timeS) return samples.last().distanceM
        val hi = samples.indexOfFirst { it.timeS >= timeS }
        val a = samples[hi - 1]; val b = samples[hi]
        if (b.timeS == a.timeS) return b.distanceM   // flat-time segment (stop): jump to the end
        val f = (timeS - a.timeS) / (b.timeS - a.timeS)
        return a.distanceM + f * (b.distanceM - a.distanceM)
    }
}
