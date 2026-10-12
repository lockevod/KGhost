package com.enderthor.kghost.extension

import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState

/**
 * BENCH TESTING ONLY: picks the gradient between the Karoo's ELEVATION_GRADE and Ride Replay's grade
 * stream (Ride Replay can't mock the barometer). Only a Streaming replay owns the gradient: anything else
 * (NotAvailable, Searching, Idle, a dead stream) leaves the Karoo's grade exactly as with the switch off.
 * Not thread-safe; the grade collector runs on one dispatcher.
 */
internal class GradeSourceGate {
    sealed interface Action {
        /** Store this gradient (null = none) with a fresh timestamp. */
        data class Write(val pct: Double?) : Action
        /** Keep the stored gradient, refresh its timestamp. */
        data object Refresh : Action
        /** Forget the stored gradient (it was the replay's, and the replay just stopped owning it). */
        data object Clear : Action
        data object Ignore : Action
    }

    var replayOpen = false
        private set

    fun onEvent(fromReplay: Boolean, state: StreamState): Action {
        if (!fromReplay) {
            // Ride Replay only emits on a change, so a constant replayed grade would go stale after the
            // freshness window; the Karoo's ~1 Hz samples keep it fresh without touching its value.
            return if (replayOpen) Action.Refresh else Action.Write(state.value(DataType.Field.ELEVATION_GRADE))
        }
        val open = state is StreamState.Streaming
        if (open) {
            replayOpen = true
            return Action.Write(state.value(DataType.Field.SINGLE))
        }
        if (!replayOpen) return Action.Ignore
        replayOpen = false
        return Action.Clear
    }

    private fun StreamState.value(field: String): Double? =
        (this as? StreamState.Streaming)?.dataPoint?.values?.get(field)
}
