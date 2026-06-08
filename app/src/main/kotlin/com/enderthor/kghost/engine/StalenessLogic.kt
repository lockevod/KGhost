package com.enderthor.kghost.engine

/**
 * Shared staleness constant for the gap engine.
 *
 * The "stopped at a light" (frozen `DISTANCE` is legitimate — the ghost keeps moving) versus "GPS
 * lost while moving" distinction is made by SPEED magnitude in [CoastingEstimator]; the threshold
 * here is the shared one it (and the route tick's movement gate) use.
 */
object StalenessLogic {

    /** Speed (m/s) below which the rider is treated as essentially stopped. */
    const val MIN_MOVING_MS = 0.5
}
