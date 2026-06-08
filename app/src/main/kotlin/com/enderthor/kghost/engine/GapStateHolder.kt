package com.enderthor.kghost.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canonical in-memory gap state. Data fields read from here, never from DataStore.
 *
 * This mirrors KSafe's `EmergencyManager.uiState` pattern: the ~1 Hz tick in
 * [com.enderthor.kghost.extension.KGhostExtension] is the single writer, and the
 * data-field render callbacks are readers. Keeping the live state in a [StateFlow] (rather
 * than re-reading DataStore) avoids the async, racy reads that DataStore would impose on the
 * 1 Hz render path.
 */
object GapStateHolder {
    private val _state = MutableStateFlow(GapState.inactive())

    /** Latest computed gap. Starts [GapState.inactive] until the first Recording tick. */
    val state: StateFlow<GapState> = _state.asStateFlow()

    /** Publishes a freshly computed gap. Called once per tick by the extension. */
    fun update(s: GapState) {
        _state.value = s
    }

    /** Resets to the inactive state. Called when no target is configured or the ride stops. */
    fun clear() {
        _state.value = GapState.inactive()
    }
}
