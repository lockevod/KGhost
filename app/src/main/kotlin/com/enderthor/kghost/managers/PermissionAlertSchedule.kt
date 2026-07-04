package com.enderthor.kghost.managers

/** Persisted decision state for the missing-permission in-ride alert. */
data class PermAlertState(val firedCount: Int, val lastFiredEpoch: Long)

/**
 * Decaying reminder for the missing all-files-access permission. The first [INITIAL_BURST] rides
 * nudge at most once per [SHORT_THROTTLE_MS] (72h); afterwards the throttle grows to
 * [LONG_THROTTLE_MS] (10 days) so the intrusive channel stops being chronic noise while the
 * always-visible settings banner carries the passive reminder. Pure + clock-injected for testing.
 */
object PermissionAlertSchedule {
    const val INITIAL_BURST = 3
    const val SHORT_THROTTLE_MS = 72L * 3600_000
    const val LONG_THROTTLE_MS = 10L * 24 * 3600_000

    /** New state to persist when an alert should fire NOW, or null to stay silent. */
    fun decide(state: PermAlertState, nowEpoch: Long): PermAlertState? {
        val throttle = if (state.firedCount < INITIAL_BURST) SHORT_THROTTLE_MS else LONG_THROTTLE_MS
        if (state.lastFiredEpoch != 0L && nowEpoch - state.lastFiredEpoch < throttle) return null
        return PermAlertState(state.firedCount + 1, nowEpoch)
    }
}
