package com.enderthor.kghost.engine

/**
 * The ONE "GPS lost" episode, shared by Ghost-Pace and route mode (field log 2026-10-02: a 17-min route-mode
 * loss raised nothing, because the alert lived only in the no-route branch).
 *
 * Signal: age since the last TRUSTED LOCATION delivery (monotonic ms). Not the odometer's loss clock: that
 * one is cleared only by a raw-distance change, so a rider parked with a perfect fix and a sleeping wheel
 * sensor read as "lost". Not a verified measurement time either — a replayed cached fix counts as delivered.
 *
 * Baselines: nothing is judged before the race starts (a stationary pre-lock wait is never "lost") nor
 * before the ride has delivered ANY trusted fix (an indoor/trainer ride on a wheel sensor never has GPS to
 * lose — firing there would nag every ride); a resume from pause restarts the clock (a grace of [alertS] to
 * reacquire); while paused it neither fires nor re-arms. One alert per episode, re-armed ONLY by a real
 * fix younger than half the threshold — never by the resume grace, or a loss spanning a pause would
 * alert twice. Synchronized, but the caller must also run [update] + the dispatch on the SAME thread as the
 * pause/resume handler, or a pause can land between the decision and the alert. Limitation: it is only
 * evaluated when the tick runs, i.e. while at least one tick input (ELAPSED_TIME, in practice) still emits.
 */
class GpsHealth(private val alertS: Double) {
    private var anchorMs: Long? = null

    @Volatile var fired = false
        private set

    @Synchronized fun onRaceStart(nowMs: Long) { if (anchorMs == null) anchorMs = nowMs }

    @Synchronized fun onResume(nowMs: Long) { if (anchorMs != null) anchorMs = nowMs }

    @Synchronized fun reset() { anchorMs = null; fired = false }

    /** The host did not accept the alert (unbound / dispatch threw): leave the episode eligible to retry. */
    @Synchronized fun undoFire() { fired = false }

    /** Seconds without a trusted fix, or null before the race starts. */
    @Synchronized fun ageS(nowMs: Long, lastFixMs: Long?): Double? {
        val anchor = anchorMs ?: return null
        return (nowMs - maxOf(anchor, lastFixMs ?: Long.MIN_VALUE)) / 1000.0
    }

    /** True exactly on the tick the alert must be dispatched. */
    @Synchronized fun update(nowMs: Long, lastFixMs: Long?, paused: Boolean): Boolean {
        if (paused || lastFixMs == null) return false
        val age = ageS(nowMs, lastFixMs) ?: return false
        if (age >= alertS) {
            if (!fired) { fired = true; return true }
        } else if ((nowMs - lastFixMs) / 1000.0 < alertS * 0.5) {
            fired = false
        }
        return false
    }
}
