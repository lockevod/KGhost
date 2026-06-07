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
 * - next != null  -> Show if nothing was shown yet, OR [forceReassert] is set, OR it moved
 *                    >= [minMoveM] from the last shown position; otherwise None (suppress
 *                    redundant re-emits while ~stationary).
 *
 * [forceReassert] is the heartbeat lever: the Karoo host drops our map symbol when it redraws its
 * layer (zoom / pan / map re-init), and a STATIONARY ghost (e.g. clamped at a segment end) never
 * crosses [minMoveM] again, so a purely move-thresholded decision would never re-show it — the ghost
 * vanishes for the rest of the stop. When the caller's heartbeat window has elapsed it passes
 * `forceReassert = true` to re-emit the CURRENT position regardless of movement, so a host redraw
 * can't permanently drop the marker. Mirrors the data-field `HEARTBEAT_MS` re-emit.
 *
 * The <=1 Hz cap is handled upstream by the tick cadence, so no clock is needed here.
 */
fun decideMapEmit(
    lastShown: GhostMarker?,
    next: GhostMarker?,
    minMoveM: Double,
    forceReassert: Boolean = false,
): MapEmit {
    if (next == null) return if (lastShown != null) MapEmit.Hide else MapEmit.None
    if (lastShown == null) return MapEmit.Show(next)
    if (forceReassert) return MapEmit.Show(next)
    val moved = Polyline.haversineM(
        LatLng(lastShown.lat, lastShown.lng),
        LatLng(next.lat, next.lng),
    )
    return if (moved >= minMoveM) MapEmit.Show(next) else MapEmit.None
}
