package com.enderthor.kvpartner.engine

import kotlin.math.abs

/**
 * Three-state classification of the gap for display purposes.
 *
 *  - [NEUTRAL]: effectively on-pace (within a small epsilon). Rendered in the neutral
 *    day/night colour with no leading sign — NOT green/red, since "exactly on pace" is
 *    neither ahead nor behind.
 *  - [AHEAD]: clearly faster than the ghost. Rendered green with a leading "+".
 *  - [BEHIND]: clearly slower than the ghost. Rendered red with a leading "-".
 */
enum class GapStatus { NEUTRAL, AHEAD, BEHIND }

/**
 * Pure display-logic helpers, kept free of Android types so they are JVM-unit-testable.
 */
object GapDisplayLogic {

    /**
     * Classifies the time gap into [GapStatus] using the engine's mathematical sign convention
     * (ahead ⇒ [gapTimeS] negative). Values within ±[epsS] of zero are [GapStatus.NEUTRAL] so an
     * exactly-on-pace gap renders neutral rather than a misleading green "+0:00".
     *
     * @param gapTimeS gap in seconds (negative when ahead, positive when behind).
     * @param epsS     half-width of the neutral dead-band in seconds (default 1.0).
     */
    fun gapStatus(gapTimeS: Double, epsS: Double = 1.0): GapStatus = when {
        abs(gapTimeS) < epsS -> GapStatus.NEUTRAL
        gapTimeS < 0.0 -> GapStatus.AHEAD   // ahead ⇒ negative time gap
        else -> GapStatus.BEHIND
    }
}
