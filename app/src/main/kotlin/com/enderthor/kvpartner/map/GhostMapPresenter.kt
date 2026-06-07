package com.enderthor.kvpartner.map

import com.enderthor.kvpartner.geo.PolylinePath

/**
 * Turns the ghost's current distance ALONG THE ROUTE into a map marker (time-based: the caller
 * passes `seg.routeStartM + GapState.ghostProgressM`). Pure — no Android, no karoo-ext types.
 *
 * Returns null when there is nothing trustworthy to show: the GPS/stream is stale, or the input is
 * non-finite. The caller only invokes this while a segment race is active, so "active" is implicit.
 */
object GhostMapPresenter {
    fun marker(ghostRouteDistM: Double, route: PolylinePath, fresh: Boolean): GhostMarker? {
        if (!fresh || !ghostRouteDistM.isFinite()) return null
        val s = route.sampleAt(ghostRouteDistM)
        return GhostMarker(s.location.lat, s.location.lng, s.bearingDeg.toFloat())
    }
}
