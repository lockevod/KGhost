package com.enderthor.kghost.engine

/**
 * Diagnostics-only histogram of inter-arrival gaps on a stream, plus the raw emission count.
 *
 * WHY THIS EXISTS. A ride log once "proved" the Karoo's LOCATION stream ran at 5 s by measuring the
 * inter-arrival of the `KVP loc:` log line — which is itself throttled to >= 5000 ms, so it measured
 * the throttle. The conclusion was wrong and cost a reverted commit. Worse, the number it produced
 * was *unfalsifiable from the log*: a 5 s throttle aliases the source period, and whether a 1 Hz
 * source is even distinguishable from a 5 s one depends on delivery jitter (>= ~10 ms: yes; sub-ms
 * after the 1 ms quantisation of `elapsedRealtime()`: no). So: never infer a cadence from a
 * throttled line again. Measure the thing directly, unthrottled, and report the DISTRIBUTION —
 * a mean would have hidden exactly the tail that matters here.
 *
 * Records every gap into fixed buckets and renders only the non-empty ones, so one compact line
 * carries the whole shape. Cheap enough to sit on a hot stream: one subtraction, one branch chain,
 * one array increment.
 *
 * THREADING: each probe is marked from exactly ONE collector coroutine, but [render] is called from
 * the tick. That read is deliberately unsynchronised — the worst case is a log line reporting a
 * count one emission stale, which cannot mislead the way a lock's cost on a 5 Hz stream would.
 * ponytail: unsynchronised by design, diagnostics only; if this ever gates behaviour, it needs real
 * memory visibility (an AtomicIntegerArray or a snapshot handoff), not a volatile sprinkle.
 */
class CadenceProbe(private val label: String) {

    /** Upper bounds in ms, exclusive. Chosen to straddle every cadence in play: sub-100 ms (a fast
     *  host stream), 200-350 (~3-5 Hz), 1000 (the tick), and out to 8 s so a real dropout lands in
     *  the tail instead of being clipped into the last bucket. */
    private val bounds = longArrayOf(25, 50, 100, 200, 350, 500, 750, 1000, 1500, 2000, 3000, 5000, 8000)
    private val counts = IntArray(bounds.size + 1)

    private var lastMs = Long.MIN_VALUE

    /** Raw emissions seen (marks), NOT gaps — there is one fewer gap than emission. */
    var emissions: Long = 0L
        private set

    var maxGapMs: Long = 0L
        private set

    /** Record one emission at [nowMs] (an `elapsedRealtime()` reading). */
    fun mark(nowMs: Long) {
        emissions++
        val prev = lastMs
        lastMs = nowMs
        if (prev == Long.MIN_VALUE) return // first mark establishes the origin; no gap yet
        add(nowMs - prev)
    }

    /** Record an already-computed interval (used for "fix age at tick", which is not an inter-arrival). */
    fun add(deltaMs: Long) {
        if (deltaMs < 0) return // a clock that went backwards is not a measurement
        if (deltaMs > maxGapMs) maxGapMs = deltaMs
        var i = 0
        while (i < bounds.size && deltaMs >= bounds[i]) i++
        counts[i]++
    }

    /** Reset for a new ride. */
    fun reset() {
        counts.fill(0)
        lastMs = Long.MIN_VALUE
        emissions = 0L
        maxGapMs = 0L
    }

    /** Values actually recorded into buckets. For a [mark]-driven probe this is one fewer than
     *  [emissions] (the first mark only sets the origin); for an [add]-driven one it IS the count. */
    val samples: Int get() = counts.sum()

    /** `loc n=8399 max=1102ms {<200:8100 <350:250 <500:40 >=8000:1}` — non-empty buckets only.
     *  `n` is the number of RECORDED VALUES, not emissions: an add-only probe (fix age) would
     *  otherwise render `n=0` and read as "no samples". Callers that need the raw arrival count
     *  print [emissions] alongside. */
    fun render(): String {
        val sb = StringBuilder(label).append(" n=").append(samples)
            .append(" max=").append(maxGapMs).append("ms {")
        var first = true
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            if (!first) sb.append(' ')
            first = false
            if (i < bounds.size) sb.append('<').append(bounds[i]) else sb.append(">=").append(bounds.last())
            sb.append(':').append(counts[i])
        }
        return sb.append('}').toString()
    }
}
