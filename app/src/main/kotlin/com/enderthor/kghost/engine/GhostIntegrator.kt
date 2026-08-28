package com.enderthor.kghost.engine

/**
 * Path-following ghost race engine (pure). Accrues the rider's HISTORICAL time per metre ACTUALLY ridden
 * (from a pace source; where there is no history the fill is NEUTRAL — the rider's own pace over that
 * tick, so novel ground moves the gap by 0), so the ghost rides the rider's own path — reroutes /
 * shortcuts / loops are irrelevant. A decimated breadcrumb places the map-ghost marker.
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

    /** Metres accrued against real history, and metres neutral-filled. The gap is a verdict on
     *  [matchedM] ONLY — [filledM] metres moved it by 0 — so `matchedM / (matchedM + filledM)` is the
     *  coverage the number actually speaks for. Nothing measured this before; without it neither the
     *  rider nor the code can tell a verdict from a silence. */
    var matchedM = 0.0; private set
    var filledM = 0.0; private set

    private var lastRiderDist = Double.NaN
    private var prevElapsedS = Double.NaN
    private var pendingResumeLead: Double? = null
    private val bcDist = ArrayList<Double>()
    private val bcTime = ArrayList<Double>()
    private val bcLat = ArrayList<Double>()
    private val bcLng = ArrayList<Double>()

    /** [paceAt] = the pace lookup (lat, lng, bearing) -> s/m or null (→ neutral-fill). */
    fun onTick(riderDist: Double, lat: Double, lng: Double, bearingDeg: Double, elapsedS: Double,
               paceAt: (Double, Double, Double) -> Double?) {
        val resumeLead = pendingResumeLead
        if (resumeLead != null) {
            // First tick after restore(): re-anchor ghostTime to THIS tick's race clock + the restored lead,
            // so gapTimeS = ghostTime − elapsedS == leadS regardless of the resumed process's fresh elapsed
            // origin. Re-baseline lastRiderDist to THIS tick's odometer (dd=0) so the un-checkpointed metres
            // ridden between the last write and the cut don't accrue an unoffset lead overshoot, and an
            // odometer reset across the resume is absorbed cleanly.
            ghostTime = elapsedS + resumeLead; lastRiderDist = riderDist; pendingResumeLead = null
            bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
        } else if (lastRiderDist.isNaN()) {
            // First tick: anchor the gap to 0 (do NOT accrue the unknown pre-roll).
            lastRiderDist = riderDist; ghostTime = elapsedS
            bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
        }
        if (bcDist.isEmpty()) push(riderDist, lat, lng) // seed: fresh OR post-restore (never NaN ghost)
        val dd = riderDist - lastRiderDist
        if (dd > 0.0) {
            // NEUTRAL fill on ground with no history: accrue the rider's OWN pace over this tick, so those
            // metres move the gap by exactly 0 and the number only ever judges ground the rider has ridden
            // before. The old fixed VP-fill (the 12 km/h Ghost-Pace target) fabricated a verdict on novel
            // ground: a rider averaging more than 2x the target came out "ahead" by more than their own
            // elapsed time, and by kilometres.
            //
            // When the race clock did NOT advance (a repeated ELAPSED_TIME value against a fresh distance —
            // the caller's combine+sample can emit one, and its own freeze is gated on `elapsedS > prev`)
            // the neutral contribution is 0, NOT the VP pace: charging vpTimePerM there would mint
            // `vpTimePerM * dd` of unearned lead on every such tick, one-signed and never given back —
            // the same fabricated verdict, re-entering through the back door. Also covers the NaN
            // prevElapsed of the very first tick (which carries dd == 0 anyway).
            val de = elapsedS - prevElapsedS
            val fill = if (de > 0.0) de / dd else 0.0 // NaN prevElapsed → false → 0
            val hist = paceAt(lat, lng, bearingDeg)
            if (hist != null) matchedM += dd else filledM += dd
            ghostTime += (hist ?: fill) * dd
            lastRiderDist = riderDist
            if (riderDist - bcDist.last() >= decimateM) push(riderDist, lat, lng)
        } else if (dd < 0.0) {
            // Backward step = a non-monotonic coast/GPS-recovery correction (the odometer source is
            // CoastingEstimator.effectiveDistanceM, which snaps back when the raw fix returns). Re-baseline
            // to the corrected position and KEEP ghostTime + the accrued lead; accrue nothing this tick.
            // (A true odometer reset = a new activity, which gets a fresh integrator — never reached here.)
            lastRiderDist = riderDist
        }
        prevElapsedS = elapsedS
        gapTimeS = ghostTime - elapsedS
        place(elapsedS, riderDist, lat, lng)
    }

    /** Restore scalar state on resume (Component 3): [leadS] is the race lead (gapTimeS) to reproduce, NOT
     *  an absolute ghostTime — the next [onTick] re-anchors `ghostTime = elapsedS + leadS` so the fresh
     *  resumed elapsed origin can't inflate the gap. [lastRiderDist] restores the odometer baseline (a
     *  reset odometer just re-baselines via the backward-Δd branch). Breadcrumb re-seeds on that tick. */
    fun restore(leadS: Double, lastRiderDist: Double) {
        this.lastRiderDist = lastRiderDist; this.pendingResumeLead = leadS; this.prevElapsedS = Double.NaN
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
