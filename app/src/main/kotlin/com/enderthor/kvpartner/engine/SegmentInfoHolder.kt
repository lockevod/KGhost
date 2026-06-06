package com.enderthor.kvpartner.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canonical in-memory holder for the segment metadata that the segment data field reads
 * alongside [GapStateHolder].
 *
 * The ~1 Hz tick in [com.enderthor.kvpartner.extension.KVPartnerExtension] is the single
 * writer; `SegmentGapDataType` is the reader. Keeping [SegmentInfo] here (rather than
 * re-reading DataStore or passing it through Karoo's callback API) avoids async, racy
 * reads on the render path — the same pattern used by [GapStateHolder] for [GapState] and
 * [RenderPrefs] for gap-display preferences.
 *
 * Starts null: the field renders `---` until the first segment becomes active.
 */
object SegmentInfoHolder {
    private val _info = MutableStateFlow<SegmentInfo?>(null)

    /** Latest segment metadata, or null when no segment is active. */
    val info: StateFlow<SegmentInfo?> = _info.asStateFlow()

    /** Publishes segment metadata when the rider enters a live segment. Called by the extension. */
    fun set(i: SegmentInfo?) {
        _info.value = i
    }

    /** Clears the current segment. Called when the rider is between segments or off-route. */
    fun clear() {
        _info.value = null
    }
}
