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

    // Precomputed primitive arrays for O(log n) binary-search lookups. The curve is immutable,
    // so these are built once and reused on every tick.
    private val dist = DoubleArray(samples.size) { samples[it].distanceM }
    private val time = DoubleArray(samples.size) { samples[it].timeS }

    /**
     * First index i with arr[i] >= key, or arr.size if none. This is the lower-bound, identical
     * in semantics to `indexOfFirst { it >= key }` and well-defined for duplicate keys (unlike
     * java.util.Arrays.binarySearch, which may return any of several equal indices).
     */
    private fun lowerBound(arr: DoubleArray, key: Double): Int {
        var lo = 0
        var hi = arr.size // exclusive
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] >= key) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Ghost time at [distanceM] metres. Clamps to the endpoints when out of range. */
    fun timeAt(distanceM: Double): Double {
        // Guard non-finite input (NaN/±Inf): every comparison against NaN is false, so without
        // this the function would fall through to lowerBound, which returns arr.size → out of range.
        if (!distanceM.isFinite()) return samples.first().timeS
        if (distanceM <= samples.first().distanceM) return samples.first().timeS
        if (distanceM >= samples.last().distanceM) return samples.last().timeS
        // After the clamps, distanceM is strictly between the endpoints, so hi is in [1, size-1].
        val hi = lowerBound(dist, distanceM)
        val a = samples[hi - 1]; val b = samples[hi]
        val f = (distanceM - a.distanceM) / (b.distanceM - a.distanceM)
        return a.timeS + f * (b.timeS - a.timeS)
    }

    /** Ghost distance at time [timeS]. Clamps to the endpoints when out of range. */
    fun distanceAt(timeS: Double): Double {
        // Guard non-finite input (NaN/±Inf): see timeAt.
        if (!timeS.isFinite()) return samples.first().distanceM
        if (timeS <= samples.first().timeS) return samples.first().distanceM
        if (timeS >= samples.last().timeS) return samples.last().distanceM
        // After the clamps, timeS is strictly between the endpoints, so hi is in [1, size-1].
        // lowerBound (not Arrays.binarySearch) keeps the flat-time/stop semantics deterministic.
        val hi = lowerBound(time, timeS)
        val a = samples[hi - 1]; val b = samples[hi]
        if (b.timeS == a.timeS) return b.distanceM   // flat-time segment (stop): jump to the end
        val f = (timeS - a.timeS) / (b.timeS - a.timeS)
        return a.distanceM + f * (b.distanceM - a.distanceM)
    }
}
