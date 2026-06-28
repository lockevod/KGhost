package com.enderthor.kghost.engine

/**
 * Path-following ghost race engine (pure). Accrues the rider's HISTORICAL time per metre ACTUALLY ridden
 * (from a pace source, VP-fill where there is no history), so the ghost rides the rider's own path —
 * reroutes / shortcuts / loops are irrelevant. A decimated breadcrumb places the map-ghost marker.
 *
 * gapTimeS = ghostTime − riderElapsed  (positive → rider faster than historical-self over ground covered)
 * gapDistM = riderDist − D_ghost       (D_ghost = breadcrumb distance where cumGhostTime == riderElapsed)
 */
class GhostIntegrator(
    @Suppress("unused") private val pick: GhostPick,
    private val vpTimePerM: Double,
    private val decimateM: Double = 20.0,
) {
    var ghostTime = 0.0; private set
    var gapTimeS = 0.0; private set
    var gapDistM = 0.0; private set
    var ghostLat = Double.NaN; private set
    var ghostLng = Double.NaN; private set

    private var lastRiderDist = Double.NaN
    private val bcDist = ArrayList<Double>()
    private val bcTime = ArrayList<Double>()
    private val bcLat = ArrayList<Double>()
    private val bcLng = ArrayList<Double>()

    /** [paceAt] = the pace lookup (lat, lng, bearing) -> s/m or null (→ VP-fill). */
    fun onTick(riderDist: Double, lat: Double, lng: Double, bearingDeg: Double, elapsedS: Double,
               paceAt: (Double, Double, Double) -> Double?) {
        if (lastRiderDist.isNaN()) { lastRiderDist = riderDist; push(riderDist, lat, lng) }
        val dd = riderDist - lastRiderDist
        if (dd > 0.0) {
            ghostTime += (paceAt(lat, lng, bearingDeg) ?: vpTimePerM) * dd
            lastRiderDist = riderDist
            if (bcDist.isEmpty() || riderDist - bcDist.last() >= decimateM) push(riderDist, lat, lng)
        }
        gapTimeS = ghostTime - elapsedS
        place(elapsedS, riderDist)
    }

    /** Restore scalar state on resume (Component 3). Breadcrumb restarts empty; the marker re-seeds. */
    fun restore(ghostTime: Double, lastRiderDist: Double) {
        this.ghostTime = ghostTime; this.lastRiderDist = lastRiderDist
        bcDist.clear(); bcTime.clear(); bcLat.clear(); bcLng.clear()
    }

    private fun push(dist: Double, lat: Double, lng: Double) {
        bcDist.add(dist); bcTime.add(ghostTime); bcLat.add(lat); bcLng.add(lng)
    }

    private fun place(elapsedS: Double, riderDist: Double) {
        if (bcTime.isEmpty()) { gapDistM = 0.0; return }
        var lo = 0; var hi = bcTime.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (bcTime[m] < elapsedS) lo = m + 1 else hi = m }
        if (lo >= bcTime.size) { gapDistM = riderDist - bcDist.last(); ghostLat = bcLat.last(); ghostLng = bcLng.last(); return }
        if (lo == 0) { gapDistM = riderDist - bcDist[0]; ghostLat = bcLat[0]; ghostLng = bcLng[0]; return }
        val t0 = bcTime[lo - 1]; val t1 = bcTime[lo]
        val f = if (t1 > t0) (elapsedS - t0) / (t1 - t0) else 0.0
        val dGhost = bcDist[lo - 1] + f * (bcDist[lo] - bcDist[lo - 1])
        gapDistM = riderDist - dGhost
        ghostLat = bcLat[lo - 1] + f * (bcLat[lo] - bcLat[lo - 1])
        ghostLng = bcLng[lo - 1] + f * (bcLng[lo] - bcLng[lo - 1])
    }
}
