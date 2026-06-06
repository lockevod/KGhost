package com.enderthor.kvpartner.engine

import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.PolylinePath

/**
 * A [ProgressProvider] that derives progress from the rider's GPS position projected onto
 * a loaded route polyline, rather than from the Karoo DISTANCE stream.
 *
 * Each call to [onLocation] projects the supplied coordinate onto the route via
 * [PolylinePath.nearestProjection], which gives both the cumulative distance-along-route
 * ([progressM]) and the perpendicular distance from the route.  The rider is considered
 * on-route when that perpendicular distance is less than [toleranceM].
 *
 * Freshness uses the SAME two-timestamp value-change logic as [DistanceProgress]:
 * [lastChangeMs] is updated only when [progressM] actually changes; [isFresh] is true
 * only while the projected distance changed within the last [staleThresholdMs] milliseconds.
 * This mirrors the "last known value" defence — a GPS fix that is frozen at the same route
 * position for more than [staleThresholdMs] is treated as stale.
 *
 * @param route             The route polyline to project onto.
 * @param toleranceM        Maximum perpendicular distance (m) for the rider to be considered
 *                          on-route.  Default 25 m.
 * @param staleThresholdMs  How long (ms) without a position change before [isFresh] becomes
 *                          false.  Default 3000 ms.
 * @param clock             Injectable time source for tests.
 *
 * Out-and-back / self-overlapping routes: on a route A→B→A a point on the shared road projects
 * equally well onto BOTH the outbound and the return vertex range, so a plain global
 * [PolylinePath.nearestProjection] lets GPS noise flip the winning pass tick-to-tick — making
 * [progressM] jump km backward/forward (and spuriously read fresh). To prevent this, every fix
 * after the first uses [PolylinePath.nearestProjectionNear] windowed around the last known
 * route-distance, so progress can only advance along the CURRENT pass. The first fix (acquisition)
 * and any fix where the windowed projection falls outside [toleranceM] (a genuine deviation/reroute
 * or a legitimate skip) fall back to a GLOBAL [PolylinePath.nearestProjection] to re-acquire.
 */
class RouteProjectedProgress(
    private val route: PolylinePath,
    private val toleranceM: Double = 25.0,
    private val staleThresholdMs: Long = 3000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressProvider {

    /** Whether a route position has been acquired yet (false until the first [onLocation]). */
    private var acquired: Boolean = false

    /** Cumulative distance-along-route of the last projected position (metres). */
    override var progressM: Double = 0.0
        private set

    /** True when the last GPS fix projects within [toleranceM] of the route. */
    var onRoute: Boolean = false
        private set

    /** Timestamp of the last time [progressM] actually changed (0 if [onLocation] never called). */
    private var lastChangeMs: Long = 0L

    /**
     * Projects [p] onto the route, updates [progressM], [onRoute], and freshness state.
     * Must be called from a single coroutine (no cross-thread synchronisation).
     */
    fun onLocation(p: LatLng) {
        val proj = if (!acquired) {
            // First fix: global scan to acquire the pass.
            route.nearestProjection(p)
        } else {
            // Subsequent fixes: window around the last known route-distance so progress advances
            // along the CURRENT pass and cannot snap onto the other pass of an out-and-back route.
            val windowed = route.nearestProjectionNear(
                p,
                aroundDistanceM = progressM,
                backWindowM = BACK_WINDOW_M,
                fwdWindowM = FWD_WINDOW_M,
            )
            // Rider left the window (genuine deviation / reroute / legitimate skip): re-acquire
            // globally so we don't get stuck off the current pass.
            if (windowed.perpDistM >= toleranceM) route.nearestProjection(p) else windowed
        }
        val now = clock()

        val newDist = proj.distanceAlongM
        if (newDist != progressM || lastChangeMs == 0L) lastChangeMs = now
        progressM = newDist
        onRoute = proj.perpDistM < toleranceM
        acquired = true
    }

    /** True when [progressM] changed within the last [staleThresholdMs] milliseconds. */
    override val isFresh: Boolean
        get() = lastChangeMs > 0L && (clock() - lastChangeMs) < staleThresholdMs

    private companion object {
        /** Window behind the last route-distance — tolerates GPS jitter without snapping back a pass. */
        const val BACK_WINDOW_M = 50.0

        /** Window ahead of the last route-distance — bounds how far progress may advance per fix. */
        const val FWD_WINDOW_M = 200.0
    }
}
