package com.enderthor.kghost.engine

import com.enderthor.kghost.data.GapDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory holder for render-only preferences consumed by the data fields.
 *
 * Without this, each data field would open its own [com.enderthor.kghost.managers.ConfigurationManager]
 * + `loadConfigFlow()` just to read [GapDisplay], adding a DataStore subscriber per field (3 total:
 * the service plus both fields). The service is the single writer here (it already observes
 * `activeConfig`), and the data fields read this StateFlow instead of touching DataStore.
 *
 * Defaults to [GapDisplay.BOTH] so the profile-editor preview (where the service is not feeding
 * this holder) still renders a sensible field.
 */
object RenderPrefs {
    private val _gapDisplay = MutableStateFlow(GapDisplay.BOTH)

    /** Latest gap-display preference. Updated by the extension from its `activeConfig` flow. */
    val gapDisplay: StateFlow<GapDisplay> = _gapDisplay.asStateFlow()

    /** Publishes a new gap-display preference. Called by the extension only. */
    fun setGapDisplay(v: GapDisplay) {
        _gapDisplay.value = v
    }

    private val _imperialDistance = MutableStateFlow(false)

    /**
     * Whether the rider's Karoo is configured for IMPERIAL distance units (so the fields render the
     * gap distance in feet rather than metres). Updated by the extension from the `UserProfile`
     * stream (`preferredUnit.distance`). Defaults to metric.
     */
    val imperialDistance: StateFlow<Boolean> = _imperialDistance.asStateFlow()

    /** Publishes the rider's distance unit (imperial vs metric). Called by the extension only. */
    fun setImperialDistance(v: Boolean) {
        _imperialDistance.value = v
    }
}
