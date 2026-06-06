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
 * NOTE: a windowed search over only nearby route segments is a possible future optimisation
 * for very long routes; for sub-project ② the full scan via [PolylinePath.nearestProjection]
 * is fast enough (typical routes are < 5 000 points).
 */
class RouteProjectedProgress(
    private val route: PolylinePath,
    private val toleranceM: Double = 25.0,
    private val staleThresholdMs: Long = 3000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressProvider {

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
        val proj = route.nearestProjection(p)
        val now = clock()

        val newDist = proj.distanceAlongM
        if (newDist != progressM || lastChangeMs == 0L) lastChangeMs = now
        progressM = newDist
        onRoute = proj.perpDistM < toleranceM
    }

    /** True when [progressM] changed within the last [staleThresholdMs] milliseconds. */
    override val isFresh: Boolean
        get() = lastChangeMs > 0L && (clock() - lastChangeMs) < staleThresholdMs
}
