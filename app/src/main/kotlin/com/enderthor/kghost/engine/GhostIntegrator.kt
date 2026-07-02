package com.enderthor.kghost.engine

/**
 * Path-following ghost race engine (pure). Accrues the rider's HISTORICAL time per metre ACTUALLY ridden
 * (from a pace source, VP-fill where there is no history), so the ghost rides the rider's own path —
 * reroutes / shortcuts / loops are irrelevant. A decimated breadcrumb places the map-ghost marker.
 *
 * gapTimeS = ghostTime − riderElapsed  (positive → rider faster than historical-self over ground covered)
 * gapDistM = riderDist − D_ghost       (D_ghost = path distance where cumGhostTime == riderElapsed)
 *
 * The gap is ANCHORED at the first tick (ghostTime := elapsedS) so the race starts at 0 regardless of the
 * first tick's distance/elapsed origin; a backward step (coast/GPS-recovery correction) just re-baselines.
 */
class GhostIntegrator(
    @Suppress("unused") private val pick: GhostPick,
    private val vpTimePerM: Double,
    private val decimateM: Double = 20.0,
) {
    init { require(vpTimePerM.isFinite() && vpTimePerM > 0.0) { "vpTimePerM must be finite > 0, was $vpTimePerM" } }

    var ghostTime = 0.0; private set
    var gapTimeS = 0.0; private set
    var gapDistM = 0.0; private set
    var ghostLat = Double.NaN; private set
    var ghostLng = Double.NaN; private set

    private var lastRiderDist = Double.NaN
    private var pendingResumeLead: Double? = null
    private val bcDist = ArrayList<Double>()
    private val bcTime = ArrayList<Double>()
    private val bcLat = ArrayList<Double>()
    private val bcLng = ArrayList<Double>()

    /** [paceAt] = the pace lookup (lat, lng, bearing) -> s/m or null (→ VP-fill). */
    fun onTick(riderDist: Double, lat: Double, lng: Double, bearingDeg: Double, elapsedS: Double,
               paceAt: (Double, Double, Double) -> Double?) {
        val resumeLead = pendingResumeLead
        if (resumeLead != null) {
            // First tick after restore(): re-anchor ghostTime to THIS tick's race clock + the restored lead,
            // so gapTimeS = ghostTime − elapsedS == leadS regardless of the resumed process's fresh elapsed
            // origin. lastRiderDist was set by restore() (odometer continuity handled by the dd branches).
            ghostTime = elapsedS + resumeLead; pendingResumeLead = null
            bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
        } else if (lastRiderDist.isNaN()) {
            // First tick: anchor the gap to 0 (do NOT accrue the unknown pre-roll).
            lastRiderDist = riderDist; ghostTime = elapsedS
            bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
        }
        if (bcDist.isEmpty()) push(riderDist, lat, lng) // seed: fresh OR post-restore (never NaN ghost)
        val dd = riderDist - lastRiderDist
        if (dd > 0.0) {
            ghostTime += (paceAt(lat, lng, bearingDeg) ?: vpTimePerM) * dd
            lastRiderDist = riderDist
            if (riderDist - bcDist.last() >= decimateM) push(riderDist, lat, lng)
        } else if (dd < 0.0) {
            // Backward step = a non-monotonic coast/GPS-recovery correction (the odometer source is
            // CoastingEstimator.effectiveDistanceM, which snaps back when the raw fix returns). Re-baseline
            // to the corrected position and KEEP ghostTime + the accrued lead; accrue nothing this tick.
            // (A true odometer reset = a new activity, which gets a fresh integrator — never reached here.)
            lastRiderDist = riderDist
        }
        gapTimeS = ghostTime - elapsedS
        place(elapsedS, riderDist, lat, lng)
    }

    /** Restore scalar state on resume (Component 3): [leadS] is the race lead (gapTimeS) to reproduce, NOT
     *  an absolute ghostTime — the next [onTick] re-anchors `ghostTime = elapsedS + leadS` so the fresh
     *  resumed elapsed origin can't inflate the gap. [lastRiderDist] restores the odometer baseline (a
     *  reset odometer just re-baselines via the backward-Δd branch). Breadcrumb re-seeds on that tick. */
    fun restore(leadS: Double, lastRiderDist: Double) {
        this.lastRiderDist = lastRiderDist; this.pendingResumeLead = leadS
        bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
    }

    private fun push(dist: Double, lat: Double, lng: Double) {
        bcDist.add(dist); bcTime.add(ghostTime); bcLat.add(lat); bcLng.add(lng)
    }

    private fun place(elapsedS: Double, riderDist: Double, lat: Double, lng: Double) {
        if (bcTime.isEmpty()) { gapDistM = 0.0; ghostLat = lat; ghostLng = lng; return } // #7a: never NaN
        var lo = 0; var hi = bcTime.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (bcTime[m] < elapsedS) lo = m + 1 else hi = m }
        if (lo >= bcTime.size) {
            // elapsedS is past the latest crumb's ghost-time. The target sits between the last crumb and the
            // LIVE point (riderDist, ghostTime) — interpolate there so the sign tracks gapTimeS. (#7c)
            if (elapsedS <= ghostTime) {
                val t0 = bcTime.last(); val d0 = bcDist.last(); val la0 = bcLat.last(); val ln0 = bcLng.last()
                val f = if (ghostTime > t0) ((elapsedS - t0) / (ghostTime - t0)).coerceIn(0.0, 1.0) else 1.0
                gapDistM = riderDist - (d0 + f * (riderDist - d0))
                ghostLat = la0 + f * (lat - la0); ghostLng = ln0 + f * (lng - ln0)
            } else {
                // Rider BEHIND → ghost is ahead, off the known path → clamp to the rider's position (sign-safe).
                gapDistM = 0.0; ghostLat = lat; ghostLng = lng
            }
            return
        }
        if (lo == 0) { gapDistM = riderDist - bcDist[0]; ghostLat = bcLat[0]; ghostLng = bcLng[0]; return }
        val t0 = bcTime[lo - 1]; val t1 = bcTime[lo]
        val f = if (t1 > t0) (elapsedS - t0) / (t1 - t0) else 0.0
        gapDistM = riderDist - (bcDist[lo - 1] + f * (bcDist[lo] - bcDist[lo - 1]))
        ghostLat = bcLat[lo - 1] + f * (bcLat[lo] - bcLat[lo - 1])
        ghostLng = bcLng[lo - 1] + f * (bcLng[lo] - bcLng[lo - 1])
    }
}
