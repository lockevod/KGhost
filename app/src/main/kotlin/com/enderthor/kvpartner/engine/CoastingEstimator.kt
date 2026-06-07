package com.enderthor.kvpartner.engine

/**
 * Dead-reckoning estimator for a distance signal that re-emits its LAST value when GPS is lost.
 * While the raw distance is frozen but the rider was moving, it extrapolates distance at the last
 * known speed for up to [graceMs] (coasting, like a real GPS unit), then declares the signal
 * untrustworthy. A genuine stop (speed below [minMovingMs]) is treated as legitimate, not coasted.
 * Pure; inject [clock] for tests. Confined to the single tick coroutine (no cross-thread use).
 *
 * Per tick, [update] classifies the raw distance into four regimes and produces an EFFECTIVE
 * distance + a trustworthy flag:
 *  1. **Distance changing** (new value): real movement → effective = raw, trustworthy = true.
 *     Remembers `lastChangedDistanceM`, `lastChangeMs`, and the last moving speed.
 *  2. **Frozen + essentially stopped** (`speed < minMovingMs`): legitimate stop → effective = raw
 *     (frozen), trustworthy = true (the gap stays valid because the ghost keeps moving). No coast.
 *  3. **Frozen + moving + within grace** (`now - lastChangeMs <= graceMs`): GPS dropout while
 *     moving → COAST: effective = `lastChangedDistanceM + lastMovingSpeedMs * dtSeconds`,
 *     trustworthy = true.
 *  4. **Frozen + moving + beyond grace** (`> graceMs`): sustained loss → trustworthy = false
 *     (→ the field blanks to `---`); effective holds the coasted estimate (not shown anyway).
 *
 * Frozen with no speed (`speedMs == null`): we cannot prove the rider is stopped and we have no
 * speed to coast with, so — mirroring [StalenessLogic.isTrustworthy] — we do NOT coast and set
 * trustworthy = false. Documented, conservative, SAFE choice.
 */
class CoastingEstimator(
    private val graceMs: Long = StalenessLogic.DEFAULT_STALE_THRESHOLD_MS,
    private val minMovingMs: Double = StalenessLogic.MIN_MOVING_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The distance (m) the gap engine should use this tick — raw, frozen, or coasted. */
    var effectiveDistanceM: Double = 0.0
        private set

    /** Whether [effectiveDistanceM] can be trusted for gap computation this tick. */
    var trustworthy: Boolean = false
        private set

    // --- internals -------------------------------------------------------------
    /** The last raw value seen (to detect change). NaN until the first call. */
    private var lastRawM: Double = Double.NaN

    /** The raw value at the last time it actually changed — the coast anchor. */
    private var lastChangedDistanceM: Double = 0.0

    /** Timestamp (ms) of the last time the raw value actually changed (0 until first call). */
    private var lastChangeMs: Long = 0L

    /** The last speed (m/s) observed while the rider was moving (>= [minMovingMs]). */
    private var lastMovingSpeedMs: Double = 0.0

    /** Feed the latest raw distance (m) and speed (m/s, or null if unavailable) once per tick. */
    fun update(rawDistanceM: Double, speedMs: Double?) {
        // Guard non-finite raw: ignore the sample and keep the previous state (no NaN propagation).
        if (!rawDistanceM.isFinite()) return

        val now = clock()
        val changed = rawDistanceM != lastRawM || lastChangeMs == 0L

        if (changed) {
            // Real movement (or first call): trust the raw value and re-anchor the coast.
            lastChangedDistanceM = rawDistanceM
            lastChangeMs = now
            if (speedMs != null && speedMs >= minMovingMs) lastMovingSpeedMs = speedMs
            lastRawM = rawDistanceM
            effectiveDistanceM = rawDistanceM
            trustworthy = true
            return
        }

        // Frozen (re-emitted last value).
        lastRawM = rawDistanceM
        val moving = speedMs != null && speedMs >= minMovingMs

        if (speedMs == null) {
            // Can't prove stopped, can't coast without a speed → conservatively untrustworthy.
            trustworthy = false
            return
        }
        if (!moving) {
            // Legitimate stop (e.g. red light): frozen distance is valid, no extrapolation.
            effectiveDistanceM = rawDistanceM
            trustworthy = true
            return
        }
        // Frozen while moving: GPS dropout.
        val gapMs = now - lastChangeMs
        if (gapMs <= graceMs) {
            // Coast: carry the rider forward at the last known moving speed.
            effectiveDistanceM = lastChangedDistanceM + lastMovingSpeedMs * (gapMs / 1000.0)
            trustworthy = true
        } else {
            // Sustained loss: hold the coasted estimate (not shown) and blank.
            effectiveDistanceM = lastChangedDistanceM + lastMovingSpeedMs * (graceMs / 1000.0)
            trustworthy = false
        }
    }
}
