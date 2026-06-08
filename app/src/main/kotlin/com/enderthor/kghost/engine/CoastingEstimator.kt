package com.enderthor.kghost.engine

/** Quality of the distance [CoastingEstimator] produces this tick. */
enum class CoastQuality {
    /** A real fix (distance changed) or a legitimate stop (speed ≈ 0): measured, fully reliable. */
    LIVE,

    /** Brief GPS dropout, dead-reckoned within the coast window: shown transparently as if live. */
    COASTING,

    /** Prolonged GPS loss past the coast window: still dead-reckoned, but flagged as an estimate so
     *  the field can render it marked (it NEVER blanks for GPS loss) and the host can be alerted. */
    LONG_LOSS,
}

/**
 * Dead-reckoning estimator for a distance signal that re-emits its LAST value when GPS is lost.
 * While the raw distance is frozen but the rider was moving, it extrapolates distance at the last
 * known speed (coasting, like a real GPS unit) and NEVER stops producing a value — a prolonged loss
 * is reported as [CoastQuality.LONG_LOSS] (an estimate to be shown marked + alerted) rather than
 * blanked. A genuine stop (speed below [minMovingMs]) is treated as legitimate, not coasted.
 * Pure; the caller supplies the ride-elapsed clock via [update]'s `elapsedS`. Confined to the
 * single tick coroutine (no cross-thread use).
 *
 * Per tick, [update] classifies the raw distance and produces an EFFECTIVE distance + a [quality] +
 * [coastingSeconds]:
 *  1. **Distance changing** (new value): real movement → effective = raw, quality = LIVE, coasting = 0.
 *     Remembers `lastChangedDistanceM`, `lastChangeElapsedS`, and the last moving speed.
 *  2. **Frozen + essentially stopped** (`speed < minMovingMs`): legitimate stop → effective = raw
 *     (frozen), quality = LIVE, coasting = 0 (the gap stays valid because the ghost keeps moving).
 *  3. **Frozen + moving (or speed unavailable)**: GPS dropout → COAST: effective =
 *     `lastChangedDistanceM + lastMovingSpeedMs * coastingSeconds`. quality = COASTING while
 *     `coastingSeconds * 1000 <= coastWindowMs`, else LONG_LOSS. Either way we keep extrapolating —
 *     we never blank, because a bike computer should keep estimating through a dropout and snap back
 *     when the fix returns.
 *
 * Unlike the old design there is no "untrustworthy → blank" regime: even speed-unavailable frozen
 * data is coasted at the last remembered moving speed (0 if the rider was stopped), so the field
 * always shows something and the LONG_LOSS flag drives the visual mark + the GPS-loss alert instead.
 *
 * ## Time source: RIDE-ELAPSED, not wall-clock
 * The coast gap is measured against the ride's `ELAPSED_TIME` ([update]'s `elapsedS`), NOT
 * `System.currentTimeMillis()`. The ride app freezes `ELAPSED_TIME` while the ride is paused, so a
 * Paused→Recording cycle contributes ZERO to the coast gap. Were this driven by wall-clock, a long
 * pause (a café stop) ending with the bike still rolling would, on resume, see the frozen DISTANCE
 * plus a huge wall-clock gap and inject `lastMovingSpeed × pauseDuration` of phantom distance into
 * the gap. Using elapsed time makes pause a no-op.
 */
class CoastingEstimator(
    private val coastWindowMs: Long = COAST_WINDOW_MS,
    private val minMovingMs: Double = StalenessLogic.MIN_MOVING_MS,
) {
    /** The distance (m) the gap engine should use this tick — raw, frozen, or coasted. */
    var effectiveDistanceM: Double = 0.0
        private set

    /** Quality of [effectiveDistanceM] this tick. */
    var quality: CoastQuality = CoastQuality.LIVE
        private set

    /** Seconds the distance has been frozen-while-moving (dead-reckoned). 0 when [quality] is LIVE. */
    var coastingSeconds: Double = 0.0
        private set

    // --- internals -------------------------------------------------------------
    /** The last raw value seen (to detect change). NaN until the first call. */
    private var lastRawM: Double = Double.NaN

    /** The raw value at the last time it actually changed — the coast anchor. */
    private var lastChangedDistanceM: Double = 0.0

    /** Ride-elapsed seconds at the last time the raw value actually changed (NaN until first call). */
    private var lastChangeElapsedS: Double = Double.NaN

    /** The last speed (m/s) observed while the rider was moving (>= [minMovingMs]). */
    private var lastMovingSpeedMs: Double = 0.0

    /**
     * Feed the latest raw distance (m), speed (m/s, or null if unavailable) and the ride's elapsed
     * time (seconds) once per tick. [elapsedS] is the coast clock — see the class KDoc on why this is
     * ride-elapsed rather than wall-clock (pause-safety).
     */
    fun update(rawDistanceM: Double, speedMs: Double?, elapsedS: Double) {
        // Guard non-finite inputs: ignore the sample and keep the previous state (no NaN propagation).
        if (!rawDistanceM.isFinite() || !elapsedS.isFinite()) return

        val firstCall = lastChangeElapsedS.isNaN()
        val changed = rawDistanceM != lastRawM || firstCall

        if (changed) {
            // Real movement (or first call): trust the raw value and re-anchor the coast.
            lastChangedDistanceM = rawDistanceM
            lastChangeElapsedS = elapsedS
            if (speedMs != null && speedMs >= minMovingMs) lastMovingSpeedMs = speedMs
            lastRawM = rawDistanceM
            effectiveDistanceM = rawDistanceM
            quality = CoastQuality.LIVE
            coastingSeconds = 0.0
            return
        }

        // Frozen (re-emitted last value).
        lastRawM = rawDistanceM

        if (speedMs != null && speedMs < minMovingMs) {
            // Legitimate stop (e.g. red light): frozen distance is valid, no extrapolation.
            effectiveDistanceM = rawDistanceM
            quality = CoastQuality.LIVE
            coastingSeconds = 0.0
            return
        }

        if (lastMovingSpeedMs <= 0.0) {
            // The rider has never moved yet (no moving speed ever recorded) — e.g. stationary at the
            // start line before the SPEED stream first emits, so the stop check above can't fire on a
            // null speed. There is nothing to dead-reckon, so treat the frozen distance as LIVE rather
            // than coasting a standing start into a false "GPS lost" after the window.
            effectiveDistanceM = rawDistanceM
            quality = CoastQuality.LIVE
            coastingSeconds = 0.0
            return
        }

        // Frozen while moving — or speed unavailable, in which case we coast at the last remembered
        // moving speed (0 if the rider was stopped before). Gap is ride-elapsed time since the last
        // real change, so a pause (ELAPSED_TIME frozen) contributes nothing. coerceAtLeast(0) guards a
        // non-monotonic elapsed glitch. We NEVER blank — a prolonged loss is flagged LONG_LOSS.
        val gapS = (elapsedS - lastChangeElapsedS).coerceAtLeast(0.0)
        coastingSeconds = gapS
        effectiveDistanceM = lastChangedDistanceM + lastMovingSpeedMs * gapS
        quality = if (gapS * 1000.0 <= coastWindowMs) CoastQuality.COASTING else CoastQuality.LONG_LOSS
    }

    companion object {
        /**
         * How long (ms) a frozen-while-moving distance is dead-reckoned as a TRANSPARENT coast
         * ([CoastQuality.COASTING]) before it is treated as a prolonged loss ([CoastQuality.LONG_LOSS],
         * rendered marked + alert-eligible). ~30 s comfortably covers tunnels/underpasses.
         */
        const val COAST_WINDOW_MS = 30_000L
    }
}
