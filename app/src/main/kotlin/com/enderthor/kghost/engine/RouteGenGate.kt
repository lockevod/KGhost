package com.enderthor.kghost.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Clear-generation ownership for the route-local marker/finish state. clearRouteMode() (Main or
 * Default) only bumps [onClear]; each RouteMode is stamped with [current] at creation, and ONLY the
 * tick calls [needsReset] — so an in-flight tick that captured the old mode can never write stale
 * state after a reset (the reset itself happens on the tick, once, for the first newer route).
 *
 * `applied` starts at -1, so the very first route of a process (gen 0) also reports a reset: harmless,
 * the fields are already fresh then.
 */
class RouteGenGate {
    private val cleared = AtomicLong(0L)
    @Volatile private var applied = -1L // tick-only

    fun onClear() { cleared.incrementAndGet() }

    fun current(): Long = cleared.get()

    /** Tick-only. True (and remembers [routeGen]) the first time a route of a newer generation is seen. */
    fun needsReset(routeGen: Long): Boolean {
        if (routeGen <= applied) return false
        applied = routeGen
        return true
    }
}
