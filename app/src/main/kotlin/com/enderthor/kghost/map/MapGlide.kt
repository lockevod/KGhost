package com.enderthor.kghost.map

/**
 * Time-based smoothing for the on-map ghost marker. Turns the discrete per-tick ghost route
 * distances the 1 Hz gap tick publishes into a value the ~5 Hz map loop can emit so the marker
 * GLIDES instead of hopping.
 *
 * Design choice — LAG, never LEAD. The previous map ghost ran on a wall-clock EXTRAPOLATION
 * (`anchorElapsed + (now − anchorWall)`), so the marker was always AHEAD of the instant the data
 * field's gap number reflected → the rider saw the map ghost "más adelantado" than the field said.
 * This interpolates strictly BETWEEN the two most recent published distances and never overshoots
 * the CURRENT one, so it can never lead the field.
 *
 * It glides from the previous to the current published distance over a short [GLIDE_MS] and then
 * HOLDS at the current value until the next sample — so the marker sits ON the field's latest ghost
 * for most of each tick (near-zero lag), instead of trailing prev→cur across the WHOLE inter-tick
 * interval (which made it lag ~a full tick — visibly "stuck" ~10 m closer than the field when the
 * rider was chasing). The map ghost and the gap field stay driven by the SAME published values.
 */
object MapGlide {

    /**
     * How long the marker takes to glide from the previous published distance to the current one
     * after a new sample lands; it then holds at the current value until the next sample. A few
     * map-loop frames (the loop runs ~5 Hz) — smooth, but short enough that the marker spends most of
     * the ~1–3 s inter-tick interval AT the latest value rather than trailing toward it.
     */
    const val GLIDE_MS = 800.0

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
        // Glide over a fixed short window after the sample landed, then hold at cur (frac clamps to 1).
        // Normalising by GLIDE_MS rather than the full inter-tick span is the whole fix: the marker
        // reaches cur quickly and sits there, so it no longer trails ~a full tick behind the field.
        val frac = ((nowMs - curWallMs).toDouble() / GLIDE_MS).coerceIn(0.0, 1.0)
        return prevDistM + (curDistM - prevDistM) * frac
    }
}
