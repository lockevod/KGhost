package com.enderthor.kghost.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canonical in-memory holder for which recorded stretch (if any) is currently active.
 *
 * The ~1 Hz tick in [com.enderthor.kghost.extension.KGhostExtension] is the single writer; the
 * gap data fields are the readers — they only use whether this is non-null to show their "SEG" (racing
 * a recorded stretch) vs "GP" (fixed-pace Ghost Pace) tag. Keeping it here (rather than re-reading
 * DataStore or passing it through Karoo's callback API) avoids async, racy reads on the render path —
 * the same pattern used by [GapStateHolder] for [GapState] and [RenderPrefs] for gap-display prefs.
 *
 * Starts null: VP mode until the rider enters a recorded stretch.
 */
object SegmentInfoHolder {
    private val _info = MutableStateFlow<SegmentInfo?>(null)

    /** Latest segment metadata, or null when no segment is active. */
    val info: StateFlow<SegmentInfo?> = _info.asStateFlow()

    /**
     * Publishes segment metadata when the rider enters a live segment. Called by the extension on the
     * ~1 Hz tick — which re-builds the SAME active segment's [SegmentInfo] (a fresh instance via
     * `toInfo()`) every tick. Skip the assignment when the segment identity (start/end/label) is
     * unchanged so a steady segment doesn't churn the readers (the gap fields' SEG/VP tag) every tick.
     */
    fun set(i: SegmentInfo?) {
        val cur = _info.value
        if (i != null && cur != null &&
            cur.routeStartM == i.routeStartM && cur.routeEndM == i.routeEndM && cur.label == i.label
        ) {
            return
        }
        _info.value = i
    }

    /** Clears the current segment. Called when the rider is between segments or off-route. */
    fun clear() {
        _info.value = null
    }
}
