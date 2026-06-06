package com.enderthor.kvpartner.engine

/**
 * Pure freshness tracker for a Karoo SDK numeric stream.
 *
 * Karoo streams re-emit the LAST known value even when the underlying signal is lost (e.g. SPEED
 * freezes at its last value on GPS loss instead of dropping to 0). To tell a genuinely-current
 * reading apart from a frozen re-emission we track two timestamps — the last *emission* vs the last
 * *value CHANGE* — exactly like [DistanceProgress]. A value is "fresh" only when it CHANGED within
 * [staleThresholdMs].
 *
 * [freshValueOrNull] returns the last value when it is fresh, or `null` when it is stale/frozen or
 * was never seen. Feeding that null to downstream gates lets the caller distinguish "real current
 * value" from "frozen re-emission" robustly, rather than by coincidence.
 *
 * NOTE: callers must NOT filter identical emissions upstream — the frozen re-emission is exactly the
 * signal this tracker needs to observe to detect staleness.
 *
 * Access is expected to be confined to a single coroutine/thread (no internal synchronisation).
 *
 * @param staleThresholdMs How long (ms) without a value change before the value is no longer fresh.
 * @param clock            Injectable time source so tests need neither Android nor real time.
 */
class FreshnessTracker(
    private val staleThresholdMs: Long = 3000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastValue: Double = 0.0
    private var hasValue: Boolean = false

    /**
     * Timestamp of the last time the value actually changed. Only meaningful once [hasValue] is
     * true — a never-set tracker is identified by [hasValue], NOT by a sentinel timestamp, so a
     * clock that legitimately reads 0 is not mistaken for "never received".
     */
    private var lastChangeMs: Long = 0L

    /**
     * Called whenever the tracked stream emits a value. Records the value and, if it changed
     * (or this is the first value), resets [lastChangeMs].
     */
    fun onValue(v: Double) {
        val now = clock()
        if (!hasValue || v != lastValue) lastChangeMs = now
        lastValue = v
        hasValue = true
    }

    /**
     * Returns the last value if it changed within [staleThresholdMs], otherwise `null`
     * (stale/frozen, or never set).
     */
    fun freshValueOrNull(): Double? {
        if (!hasValue) return null
        return if ((clock() - lastChangeMs) < staleThresholdMs) lastValue else null
    }
}
