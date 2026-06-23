package com.enderthor.kghost.map

/**
 * Time-based smoothing for the on-map ghost marker. Turns the discrete per-tick ghost route
 * distances the 1 Hz gap tick publishes into a value the ~5 Hz map loop can emit so the marker
 * GLIDES instead of hopping.
 *
 * Design choice — LAG, never LEAD. The previous map ghost ran on a wall-clock EXTRAPOLATION
 * (`anchorElapsed + (now − anchorWall)`), so the marker was always AHEAD of the instant the data
 * field's gap number reflected → the rider saw the map ghost "más adelantado" than the field said.
 * This interpolates strictly BETWEEN the two most recent published distances: at the moment a new
 * sample lands the marker sits on the PREVIOUS one and glides to the CURRENT one over the next
 * tick interval. It therefore trails the field's published ghost by at most one tick (~1 s, the
 * same order as the data-field render coalescing) and can never overshoot it. The map ghost and
 * the gap field are then driven by the SAME published values, so they stay coordinated.
 */
object MapGlide {
    /**
     * @param prevDistM  ghost route distance published one tick ago (NaN if only one sample so far).
     * @param prevWallMs wall-clock of that previous publish.
     * @param curDistM   most recently published ghost route distance (NaN ⇒ nothing to show).
     * @param curWallMs  wall-clock of the most recent publish.
     * @param nowMs      current wall-clock.
     * @return the interpolated ghost route distance to draw, in [prevDistM, curDistM]; never ahead
     *         of [curDistM]. NaN when there is nothing trustworthy to show.
     */
    fun interpDistM(
        prevDistM: Double,
        prevWallMs: Long,
        curDistM: Double,
        curWallMs: Long,
        nowMs: Long,
    ): Double {
        if (!curDistM.isFinite()) return Double.NaN
        // Only one sample (or a degenerate/zero interval): show the latest, no glide to interpolate.
        if (!prevDistM.isFinite() || curWallMs <= prevWallMs) return curDistM
        val span = (curWallMs - prevWallMs).toDouble()
        val frac = ((nowMs - curWallMs).toDouble() / span).coerceIn(0.0, 1.0)
        return prevDistM + (curDistM - prevDistM) * frac
    }
}
