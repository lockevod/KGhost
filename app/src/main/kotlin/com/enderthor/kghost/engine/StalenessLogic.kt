package com.enderthor.kghost.engine

/**
 * Shared staleness constants for the gap engine.
 *
 * The "stopped at a light" (frozen `DISTANCE` is legitimate — the ghost keeps moving) versus "GPS
 * lost while moving" distinction is made by SPEED magnitude in [CoastingEstimator]; the constants
 * here are the shared thresholds it (and [RouteProjectedProgress]) use.
 */
object StalenessLogic {

    /** Speed (m/s) below which the rider is treated as essentially stopped. */
    const val MIN_MOVING_MS = 0.5

    /**
     * How long (ms) the projected route position may stay frozen before [RouteProjectedProgress]
     * reports `isFresh = false`. Sized to tolerate a BRIEF GPS dropout (a short tunnel, an underpass,
     * a momentary loss) without flicker. Tunable.
     */
    const val DEFAULT_STALE_THRESHOLD_MS = 8_000L
}
