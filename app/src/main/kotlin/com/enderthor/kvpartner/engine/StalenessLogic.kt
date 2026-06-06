package com.enderthor.kvpartner.engine

/**
 * Pure staleness/trustworthiness logic for the gap engine.
 *
 * The clean distinction the UI needs is "stopped at a light" (the frozen `DISTANCE` is legitimate
 * — the gap stays valid because the ghost keeps moving) versus "GPS lost while moving" (a tunnel —
 * the frozen `DISTANCE` is wrong and would render a believable-but-false growing "behind" gap that
 * snaps on recovery). The discriminator is the SPEED magnitude.
 */
object StalenessLogic {

    /** Speed (m/s) below which the rider is treated as essentially stopped. */
    const val MIN_MOVING_MS = 0.5

    /**
     * Returns whether the current distance reading can be trusted for gap computation.
     *
     * @param distanceFresh whether `DISTANCE` produced a new value within the staleness threshold.
     * @param speedMs       current speed in m/s, or null when the SPEED stream is unavailable.
     * @param minMovingMs   speed below which the rider is considered stopped.
     * @return true when distance is fresh, OR distance is frozen but the rider is essentially
     *         stopped (frozen distance is legitimate). Returns false when distance is frozen WHILE
     *         moving (GPS unreliable), including when speed is unavailable — we cannot prove the
     *         rider is stopped, so a frozen distance is not trustworthy.
     */
    fun isTrustworthy(
        distanceFresh: Boolean,
        speedMs: Double?,
        minMovingMs: Double = MIN_MOVING_MS,
    ): Boolean = distanceFresh || (speedMs != null && speedMs < minMovingMs)
}
