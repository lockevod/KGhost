package com.enderthor.kghost.engine

/**
 * The ONE definition of when a tick may pay the HISTORICAL verdict (tier 1 PacePatch / tier 2
 * GradePace) instead of a neutral fill. Both conditions are load-bearing and are documented at the
 * production call site: a stale fix would price dead-reckoned metres at the position where the fix
 * FROZE, and a non-LIVE quality means the metres were invented rather than ridden.
 *
 * WHY THIS IS A FUNCTION and not an expression repeated at each call site: it used to be repeated —
 * once in the tick and once in each of the four pipeline test rigs that declare themselves verbatim
 * replicas of it. A change to the production copy alone therefore left the rigs asserting the OLD
 * behaviour while still reporting green, which is exactly how a gate change shipped 585 passing tests
 * while `AdvNumPipelineTest`'s LOCK 2 — the lock on the very failure that change could cause — was
 * quietly testing dead code. Syncing the rigs afterwards failed five locks. One definition, called by
 * production and by every rig, makes that class of false green impossible.
 *
 * Keep it a single expression. If it ever needs to grow, the rigs get the growth for free.
 */
fun verdictAllowed(fixAgeOk: Boolean, quality: CoastQuality): Boolean =
    fixAgeOk && quality == CoastQuality.LIVE

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
 *  3. **Frozen + moving (or speed unavailable)**: GPS dropout → COAST: `effective += speed * dt` with
 *     the speed capped at [AGG_MAX_SPEED_MS] (a corrupt sample may not buy a rate no bicycle reaches), and
 *     the loss clock advances. quality is COASTING while the loss is within `coastWindowMs`, else
 *     LONG_LOSS. Either way we keep extrapolating — we never blank, because a bike computer should
 *     keep estimating through a dropout and snap back when the fix returns. With a NULL speed we fall
 *     back to the last moving speed, BOUNDED to one coast window's worth: a silent SPEED stream (a
 *     paired wheel/ANT+ sensor stops broadcasting exactly when the wheel stops) is indistinguishable
 *     from a dropout by speed alone, so we cap the invention instead of guessing. With NO usable rate at
 *     all we invent nothing — but the loss clock and the quality still run, so a dropout is never shown
 *     as trusted (the only silent case is a rider who has never moved; see `everMoved`).
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

    /**
     * DIAGNOSTIC (read-only, no behaviour): the raw distance (m) the LAST resolved freeze was frozen
     * AT — i.e. the value the raw stream stopped changing at, kept across the tick that breaks the
     * freeze so a caller can compare it against the raw value that broke it. Set on every raw change,
     * so during normal riding it is simply the previous tick's raw (a zero-length "freeze") and on a
     * recovery tick it is the frozen value. `rawDistanceM` on the first call.
     */
    var rawAtFreezeM: Double = Double.NaN
        private set

    /**
     * DIAGNOSTIC (read-only, no behaviour): the metres this class had dead-reckoned ON TOP of the
     * frozen raw distance at the moment the LAST freeze resolved — exactly what the recovery branch
     * discards when it snaps `effectiveDistanceM` back to raw. 0.0 during normal riding and after a
     * legitimate stop (nothing was invented). Read it on the recovery tick, together with
     * [rawAtFreezeM]: whether discarding it is right depends on whether the raw stream jumps forward
     * to cover the blind metres or resumes where it froze, which only a field log can answer.
     */
    var coastedSurplusM: Double = 0.0
        private set

    // --- internals -------------------------------------------------------------
    /** The last raw value seen (to detect change). NaN until the first call. */
    private var lastRawM: Double = Double.NaN

    /** Previous tick's ride-elapsed seconds, for the per-tick integration step (NaN until first call). */
    private var prevElapsedS: Double = Double.NaN

    /** The last PLAUSIBLE speed (m/s) observed while the rider was moving (>= [minMovingMs]). This is the
     *  rate the NULL-speed path invents at (only that path — a reported speed is spent directly, capped),
     *  so one corrupt sample would otherwise be remembered as the cruising speed and spent on the NEXT
     *  dropout: a single 100 m/s reading bought 3 km of phantom distance and ~840 s of unearned lead.
     *  Samples above [AGG_MAX_SPEED_MS] (108 km/h, the same "not a bicycle" ceiling the pace models use)
     *  are REJECTED rather than clamped here, because we are choosing what to REMEMBER and the previous
     *  good value is a better estimate of the rider's speed than a capped corruption. The reported-speed
     *  path clamps instead: there we need a rate for THIS tick and the corrupt sample is all we have.
     *  Rejecting every sample of a persistently implausible stream leaves this at 0.0 — see [everMoved]
     *  for why that no longer silences the loss signalling. */
    private var lastMovingSpeedMs: Double = 0.0

    /** Whether the raw distance has ever INCREASED, i.e. proof the rider moved that is independent of any
     *  speed sample. It separates the two states the "no rate to reckon with" branch used to conflate:
     *  a standing start (nothing has moved, treat the frozen distance as LIVE) from a rider who is moving
     *  but whose speed stream is silent or implausible (invent nothing, but keep the loss clock and the
     *  quality running so the alert, the estimate mark and the give-up blank stay honest).
     *  A DECREASE is a source reset / new activity, so it clears the flag rather than setting it. */
    private var everMoved: Boolean = false

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
     *  Past [maxCoastS] the app has already declared the number untrustworthy, so continuing to invent
     *  distance buys nothing: 4 h of frozen raw with a sensor insisting on 8 m/s fabricated 115 km of
     *  ODOMETER — not of user-visible fiction. No consumer today ever renders those metres: the no-route
     *  field has given up and blanked ~1620 s before the cap is reached, and route mode's moving-time
     *  race clock freezes together with the odometer, so the gap is unharmed either way. This bound is
     *  defence-in-depth on the number itself (and on any future consumer), not the fix for a symptom a
     *  rider sees. The odometer freezes; the loss clock and the quality keep running, so the signalling
     *  stays honest. */
    private var coastSpentS: Double = 0.0

    /**
     * Feed the latest raw distance (m), speed (m/s, or null if unavailable) and the ride's elapsed
     * time (seconds) once per tick. [elapsedS] is the coast clock — see the class KDoc on why this is
     * ride-elapsed rather than wall-clock (pause-safety).
     */
    fun update(rawDistanceM: Double, speedMs: Double?, elapsedS: Double) {
        // Guard non-finite inputs: ignore the sample and keep the previous state (no NaN propagation).
        if (!rawDistanceM.isFinite() || !elapsedS.isFinite()) return
        // A non-finite SPEED is ABSENT, not a rate: NaN < minMovingMs is false, so it slipped past the
        // stop test into the coast path and NaN * anything poisoned the odometer permanently — the budget
        // freeze does not help (NaN * 0.0 is NaN). Production only survived it because the caller filters
        // with takeIf { isFinite() }; the class now guarantees it itself.
        @Suppress("NAME_SHADOWING") val speedMs = speedMs?.takeIf { it.isFinite() }

        val firstCall = prevElapsedS.isNaN()
        // Elapsed-based, so a pause (ELAPSED_TIME frozen) advances nothing. coerceAtLeast(0) guards a
        // non-monotonic elapsed glitch.
        val dt = if (firstCall) 0.0 else (elapsedS - prevElapsedS).coerceAtLeast(0.0)
        prevElapsedS = elapsedS
        val prevRawM = lastRawM
        val changed = rawDistanceM != lastRawM || firstCall
        lastRawM = rawDistanceM

        if (changed) {
            // Real movement (or first call): trust the raw value and clear both clocks — a raw change
            // is the ONLY proof that the fix is back.
            // An INCREASE is also proof the rider moved, with no help from the speed stream; a decrease
            // is a source reset (new activity), which un-proves it.
            if (!firstCall) everMoved = rawDistanceM > prevRawM
            // Diagnostics only (two subtractions, no behaviour): this is the ONLY tick on which the
            // pair below still describes the freeze that just ended — prevRawM is the value it was
            // frozen at, and effectiveDistanceM still carries the surplus the next line discards.
            rawAtFreezeM = if (firstCall) rawDistanceM else prevRawM
            coastedSurplusM = if (firstCall) 0.0 else effectiveDistanceM - prevRawM
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

        if (speedMs == null && lastMovingSpeedMs <= 0.0) {
            // No rate to reckon with at all. Two very different states, kept apart by [everMoved]:
            if (!everMoved) {
                // The rider has never moved — e.g. stationary at the start line before the SPEED stream
                // first emits, so the stop check above can't fire on a null speed. Nothing to
                // dead-reckon, and nothing has gone wrong: treat the frozen distance as LIVE rather than
                // coasting a standing start into a false "GPS lost" after the window.
                effectiveDistanceM = rawDistanceM
                quality = CoastQuality.LIVE
                coastingSeconds = 0.0
                return
            }
            // The rider IS moving (the raw distance grew) but we have no plausible rate — a silent speed
            // stream, or one every sample of which is implausible. Invent no distance, but the loss clock
            // and the quality MUST still run: this is a genuine dropout and it has to announce itself
            // (alert, estimate mark, give-up blank). Conflating it with the start line turned a
            // ten-minute GPS loss into a fully trusted reading.
            coastingSeconds += dt
            quality = qualityOf(coastingSeconds)
            return
        }

        // Frozen while moving — or speed unavailable. We NEVER blank; a prolonged loss is LONG_LOSS.
        coastingSeconds += dt
        // Whatever the speed source, one loss may only buy maxCoastS of dead reckoning (see coastSpentS).
        val room = (maxCoastS - coastSpentS).coerceIn(0.0, dt)
        if (speedMs != null) {
            // Positive evidence of movement: dead-reckon at the speed we are actually being told. This is
            // the genuine-dropout-while-moving path, and it gets the generous budget. The rate is CLAMPED
            // to the same "not a bicycle" ceiling, not rejected as it is when choosing what to remember:
            // we need a rate for THIS tick and the reported one is all we have, so cap the corruption
            // rather than discard the tick. Uncapped, a stuck 100 m/s register spent MAX_COAST_S at
            // 100 m/s = 180 km — 12.5x the bound the duration cap alone claims.
            effectiveDistanceM += speedMs.coerceAtMost(AGG_MAX_SPEED_MS) * room
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
         * RUNAWAY case — a sensor insisting on 8 m/s through 4 h of frozen distance ran the ODOMETER to
         * 115 km (no consumer renders that far: see [coastSpentS]) — not to second-guess a plausible loss. A 180 s cap was tried and rejected: it cut 420 m off a
         * real 450 s urban-canyon loss that a regression test pins to the metre.
         *
         * 30 minutes is longer than any GPS loss a rider survives while still riding, and it caps the
         * runaway at ~14 km instead of 115. Past it the odometer freezes while the loss clock and the
         * quality keep running, so the alert and the estimate mark stay honest.
         */
        const val MAX_COAST_S = 1_800.0
    }
}
