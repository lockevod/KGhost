package com.enderthor.kghost.engine

/**
 * How far am I along the ghost's axis? Plus whether the signal is reliable.
 *
 * The "last known value" defense the Karoo SDK requires (the DISTANCE/position stream re-emits its
 * LAST value when GPS is lost) is implemented by tracking value *change* vs emission — see
 * [RouteProjectedProgress] (route mode) and [CoastingEstimator] (Ghost-Pace mode), which is
 * the production implementation. Do NOT filter identical emissions upstream: the frozen re-emission
 * is exactly the signal staleness detection relies on.
 */
interface ProgressProvider {
    val progressM: Double
    val isFresh: Boolean
}
