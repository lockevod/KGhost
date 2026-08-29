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
 * While the raw distance is frozen but the rider was moving, it extrapolates distance at the reported
 * speed (coasting, like a real GPS unit) and NEVER stops producing a value — a prolonged loss is
 * reported as [CoastQuality.LONG_LOSS] (an estimate to be shown marked + alerted) rather than blanked.
 * A genuine stop (speed below [minMovingMs]) is treated as legitimate, not coasted.
 * Pure; the caller supplies the ride-elapsed clock via [update]'s `elapsedS`. Confined to the
 * single tick coroutine (no cross-thread use).
 *
 * ## Two independent clocks
 * A stop has to do two OPPOSITE things, so they are tracked separately:
 *  * the **odometer** ([effectiveDistanceM]) INTEGRATES speed tick by tick while blind. A stop simply
 *    holds it: the stop adds nothing (no phantom metres), and takes nothing away (metres already
 *    dead-reckoned were really ridden and are kept). Integrating per tick — rather than multiplying
 *    one speed sample by the whole frozen span — also makes it monotone: a speed sample that drops
 *    late in a dropout can never yank the odometer backwards.
 *  * the **loss clock** ([coastingSeconds]) is what the rider is TOLD. It accumulates only while the
 *    raw distance is frozen AND we cannot prove the rider is stopped, and it is cleared ONLY when the
 *    raw distance genuinely changes. A stop therefore neither advances it (a parked bike is not a GPS
 *    failure) nor clears it (a dropout broken by a red light is still one dropout), so the "GPS lost"
 *    alert, the `estimated` mark and the give-up blank stay honest across stop-and-go riding.
 *
 * Per tick, [update] classifies the raw distance and produces an EFFECTIVE distance + a [quality] +
 * [coastingSeconds]:
 *  1. **Distance changing** (new value): real movement → effective = raw, quality = LIVE, both clocks
 *     reset. Remembers the last moving speed.
 *  2. **Frozen + provably stopped** (`speed < minMovingMs`): legitimate stop → hold everything.
 *  3. **Frozen + moving (or speed unavailable)**: GPS dropout → COAST: `effective += speed * dt`, and
 *     the loss clock advances. quality is COASTING while the loss is within `coastWindowMs`, else
 *     LONG_LOSS. Either way we keep extrapolating — we never blank, because a bike computer should
 *     keep estimating through a dropout and snap back when the fix returns. With a NULL speed we fall
 *     back to the last moving speed, BOUNDED to one coast window's worth: a silent SPEED stream (a
 *     paired wheel/ANT+ sensor stops broadcasting exactly when the wheel stops) is indistinguishable
 *     from a dropout by speed alone, so we cap the invention instead of guessing.
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
    private val maxCoastS: Double = MAX_COAST_S,
) {
    /** The distance (m) the gap engine should use this tick — raw, frozen, or coasted. */
    var effectiveDistanceM: Double = 0.0
        private set

    /** Quality of [effectiveDistanceM] this tick. */
    var quality: CoastQuality = CoastQuality.LIVE
        private set

    /**
     * The loss clock: seconds the raw distance has been frozen while the rider was NOT provably
     * stopped. 0 when [quality] is LIVE. It is what the GPS-lost alert and the give-up blank run on,
     * and it SURVIVES a stop (see the class KDoc) — only a real change in the raw distance clears it.
     */
    var coastingSeconds: Double = 0.0
        private set

    // --- internals -------------------------------------------------------------
    /** The last raw value seen (to detect change). NaN until the first call. */
    private var lastRawM: Double = Double.NaN

    /** Previous tick's ride-elapsed seconds, for the per-tick integration step (NaN until first call). */
    private var prevElapsedS: Double = Double.NaN

    /** The last PLAUSIBLE speed (m/s) observed while the rider was moving (>= [minMovingMs]). This is the
     *  rate every dead-reckoned metre is invented at, so one corrupt sample would otherwise be remembered
     *  as the cruising speed and spent on the NEXT dropout — a single 100 m/s reading bought 3 km of
     *  phantom distance and ~840 s of unearned lead. Samples above [AGG_MAX_SPEED_MS] (108 km/h, the same
     *  "not a bicycle" ceiling the pace models use) are REJECTED rather than clamped: the previous good
     *  value is a better estimate of the rider's speed than a capped corruption. NaN fails the range test
     *  too. */
    private var lastMovingSpeedMs: Double = 0.0

    /** Seconds already dead-reckoned on a NULL speed since the last real distance change — the budget
     *  for the bound described in the class KDoc. */
    private var unprovenCoastS: Double = 0.0

    /** Seconds dead-reckoned on THIS loss from ANY speed source, capped at [maxCoastS].
     *
     *  The null path has its own, much tighter budget: no speed at all is no evidence. A speed that keeps
     *  ARRIVING is positive evidence and used to be trusted without any limit — which is right for the
     *  case this class exists for (a real tunnel: the rider IS moving and the sensor IS correct), and
     *  wrong for a sensor that reports movement while the bike is parked (a wheel spinning on a rack, a
     *  mis-configured circumference, a stuck reading). The estimator cannot tell those apart: both show a
     *  frozen raw distance and a plausible speed. Only DURATION separates them, so the trust is bounded
     *  rather than symmetric — a 30 s cap would freeze the odometer inside a genuine tunnel.
     *
     *  Past [maxCoastS] the app has already declared the number untrustworthy (the field blanks in
     *  no-route mode, and the route gap neutral-fills coasted metres), so continuing to invent distance
     *  buys nothing and can run to kilometres: 4 h of frozen raw with a sensor insisting on 8 m/s
     *  fabricated 115 km. The odometer freezes; the loss clock and the quality keep running, so the
     *  signalling stays honest. */
    private var coastSpentS: Double = 0.0

    /**
     * Feed the latest raw distance (m), speed (m/s, or null if unavailable) and the ride's elapsed
     * time (seconds) once per tick. [elapsedS] is the coast clock — see the class KDoc on why this is
     * ride-elapsed rather than wall-clock (pause-safety).
     */
    fun update(rawDistanceM: Double, speedMs: Double?, elapsedS: Double) {
        // Guard non-finite inputs: ignore the sample and keep the previous state (no NaN propagation).
        if (!rawDistanceM.isFinite() || !elapsedS.isFinite()) return

        val firstCall = prevElapsedS.isNaN()
        // Elapsed-based, so a pause (ELAPSED_TIME frozen) advances nothing. coerceAtLeast(0) guards a
        // non-monotonic elapsed glitch.
        val dt = if (firstCall) 0.0 else (elapsedS - prevElapsedS).coerceAtLeast(0.0)
        prevElapsedS = elapsedS
        val changed = rawDistanceM != lastRawM || firstCall
        lastRawM = rawDistanceM

        if (changed) {
            // Real movement (or first call): trust the raw value and clear both clocks — a raw change
            // is the ONLY proof that the fix is back.
            if (speedMs != null && speedMs in minMovingMs..AGG_MAX_SPEED_MS) lastMovingSpeedMs = speedMs
            effectiveDistanceM = rawDistanceM
            quality = CoastQuality.LIVE
            coastingSeconds = 0.0
            unprovenCoastS = 0.0
            coastSpentS = 0.0
            return
        }

        if (speedMs != null && speedMs < minMovingMs) {
            // Legitimate stop (e.g. red light): the wheel is provably still, so add nothing — and take
            // nothing away. HOLDING the odometer is what makes a stop safe in both directions: it
            // cannot age into phantom metres (auto-pause is a user setting many riders leave off, so
            // ELAPSED_TIME counts through a light, and multiplying the last moving speed by the whole
            // stop used to mint ~726 m in one tick), and it cannot throw away real dead-reckoned ones
            // (a junction stop mid-dropout used to rewind the odometer to where the fix died and then
            // hand every discarded metre back as one huge LIVE step when it returned).
            // The loss clock is frozen but NOT cleared: a dropout is still a dropout after a red light.
            quality = qualityOf(coastingSeconds)
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

        // Frozen while moving — or speed unavailable. We NEVER blank; a prolonged loss is LONG_LOSS.
        coastingSeconds += dt
        // Whatever the speed source, one loss may only buy maxCoastS of dead reckoning (see coastSpentS).
        val room = (maxCoastS - coastSpentS).coerceIn(0.0, dt)
        if (speedMs != null) {
            // Positive evidence of movement: dead-reckon at the speed we are actually being told. This is
            // the genuine-dropout-while-moving path, and it gets the generous budget.
            effectiveDistanceM += speedMs * room
            coastSpentS += room
        } else {
            // No speed at all: fall back to the last moving speed, but spend at most one coast window
            // of it — beyond that we would be inventing a ride out of pure silence.
            val budgetS = (coastWindowMs / 1000.0 - unprovenCoastS).coerceIn(0.0, room)
            unprovenCoastS += budgetS
            coastSpentS += budgetS
            effectiveDistanceM += lastMovingSpeedMs * budgetS
        }
        quality = qualityOf(coastingSeconds)
    }

    private fun qualityOf(lossS: Double): CoastQuality = when {
        lossS <= 0.0 -> CoastQuality.LIVE
        lossS * 1000.0 <= coastWindowMs -> CoastQuality.COASTING
        else -> CoastQuality.LONG_LOSS
    }

    companion object {
        /**
         * How long (ms) a frozen-while-moving distance is dead-reckoned as a TRANSPARENT coast
         * ([CoastQuality.COASTING]) before it is treated as a prolonged loss ([CoastQuality.LONG_LOSS],
         * rendered marked + alert-eligible). ~30 s comfortably covers tunnels/underpasses.
         */
        const val COAST_WINDOW_MS = 30_000L

        /**
         * Total seconds of dead reckoning ONE loss may buy, from any speed source.
         *
         * Deliberately GENEROUS. A frozen raw distance with a plausible reported speed is the same picture
         * whether the rider is in a tunnel (sensor right, keep reckoning) or parked with a wheel spinning
         * on a rack (sensor wrong, stop). Only duration separates them, so the bound's job is to stop the
         * RUNAWAY case — a sensor insisting on 8 m/s through 4 h of frozen distance fabricated 115 km —
         * not to second-guess a plausible loss. A 180 s cap was tried and rejected: it cut 420 m off a
         * real 450 s urban-canyon loss that a regression test pins to the metre.
         *
         * 30 minutes is longer than any GPS loss a rider survives while still riding, and it caps the
         * runaway at ~14 km instead of 115. Past it the odometer freezes while the loss clock and the
         * quality keep running, so the alert and the estimate mark stay honest.
         */
        const val MAX_COAST_S = 1_800.0
    }
}
