package com.enderthor.kvpartner.engine

/** How far am I along the ghost's axis? Plus whether the signal is reliable. */
interface ProgressProvider {
    val progressM: Double
    val isFresh: Boolean
}

/**
 * Progress = accumulated ride distance (DISTANCE stream from the Karoo SDK).
 *
 * Implements the "last known value" defense required by the Karoo SDK: the stream
 * re-emits the LAST known value even when GPS is lost, so we must track two
 * separate timestamps — the last *emission* vs the last *value change*. [isFresh]
 * is true only when the value changed within [staleThresholdMs].
 *
 * NOTE: Do NOT filter identical emissions upstream — doing so would hide the
 * staleness signal entirely, because the frozen re-emission is exactly what
 * we need to detect and expose as stale. Keep every emission flowing in.
 *
 * @param staleThresholdMs  How long (ms) without a value change before [isFresh] becomes false.
 * @param clock             Injectable time source so tests do not need Android or real time.
 */
class DistanceProgress(
    private val staleThresholdMs: Long = StalenessLogic.DEFAULT_STALE_THRESHOLD_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressProvider {

    // Access is confined to the single tick coroutine in KVPartnerExtension; no cross-thread reads.
    override var progressM: Double = 0.0
        private set

    /** Timestamp of the last time [progressM] actually changed (or 0 if never received). */
    private var lastChangeMs: Long = 0L

    /**
     * Called whenever the DISTANCE stream emits a new value.
     * Updates [progressM] and, if the value changed, resets [lastChangeMs].
     */
    fun onDistance(newDistanceM: Double) {
        val now = clock()
        if (newDistanceM != progressM || lastChangeMs == 0L) lastChangeMs = now
        progressM = newDistanceM
    }

    /** True when the distance value changed within the last [staleThresholdMs] milliseconds. */
    override val isFresh: Boolean
        get() = lastChangeMs > 0L && (clock() - lastChangeMs) < staleThresholdMs
}
