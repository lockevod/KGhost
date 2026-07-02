package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable

/** Tiny scalar resume state, persisted periodically so a mid-ride power-off resumes with the lead intact.
 *  Keyed by [rideEpoch] (recordingStartedEpoch); a foreign/absent epoch → fresh start.
 *
 *  [leadS] is the RACE LEAD at checkpoint (= the integrator's `gapTimeS`, +ahead/−behind), NOT the raw
 *  accrued ghostTime: the rider-elapsed origin (`firstMoveElapsedS`) is re-stamped from zero on a resumed
 *  process, so persisting absolute ghostTime would publish a gap inflated by the whole ride elapsed. The
 *  integrator re-anchors to `elapsedS + leadS` at the first resumed tick, reproducing the lead exactly.
 *  [savedAtEpoch] (wall-clock ms) bounds how stale a checkpoint may be to count as a resume. */
@Serializable
data class GhostCheckpoint(
    val rideEpoch: Long,
    val leadS: Double,
    val lastRiderDist: Double,
    val pick: GhostPick,
    val vpTimePerM: Double,
    val savedAtEpoch: Long,
)
