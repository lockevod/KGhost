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
    // Retained for persisted-schema compatibility only. Since the neutral-fill change it no longer
    // influences the accrued gap and no longer gates the resume (see KGhostExtension's paramMatch) —
    // removing the field would be a persisted-schema change, which isn't worth it for a dead value.
    val vpTimePerM: Double,
    val savedAtEpoch: Long,
    // Stable identity (name + length via routeKeyOf) of the route the lead was accrued on. Restore requires
    // it to match the CURRENTLY loaded route → a mid-ride route change deterministically can't restore the
    // old route's lead onto the new one, with no reliance on a racy delete (rideEpoch is unchanged across a
    // route change). Uses the SAME key the aggregate store uses, so it survives a host polyline re-encode
    // between sessions (a raw-polyline hash would not, silently killing every resume).
    val routeKey: String,
)
