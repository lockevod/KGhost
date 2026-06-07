package com.enderthor.kvpartner.map

import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.Polyline

/** What the map layer should do with the next computed marker, relative to the last shown one. */
sealed interface MapEmit {
    data class Show(val marker: GhostMarker) : MapEmit
    data object Hide : MapEmit
    data object None : MapEmit
}

/**
 * Edge-triggered, movement-thresholded decision for the ghost map marker. Pure.
 *
 * - next == null  -> Hide if something was shown, else None (idempotent removal).
 * - next != null  -> Show if nothing was shown yet OR it moved >= [minMoveM] from the last shown
 *                    position; otherwise None (suppress redundant re-emits while ~stationary).
 *
 * The <=1 Hz cap is handled upstream by the tick cadence, so no clock is needed here.
 */
fun decideMapEmit(lastShown: GhostMarker?, next: GhostMarker?, minMoveM: Double): MapEmit {
    if (next == null) return if (lastShown != null) MapEmit.Hide else MapEmit.None
    if (lastShown == null) return MapEmit.Show(next)
    val moved = Polyline.haversineM(
        LatLng(lastShown.lat, lastShown.lng),
        LatLng(next.lat, next.lng),
    )
    return if (moved >= minMoveM) MapEmit.Show(next) else MapEmit.None
}
