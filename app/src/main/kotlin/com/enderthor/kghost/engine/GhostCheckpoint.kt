package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable

/** Tiny scalar resume state, persisted periodically so a mid-ride power-off resumes with the lead intact.
 *  Keyed by [rideEpoch] (recordingStartedEpoch); a foreign/absent epoch → fresh start. */
@Serializable
data class GhostCheckpoint(
    val rideEpoch: Long,
    val ghostTime: Double,
    val lastRiderDist: Double,
    val pick: GhostPick,
    val vpTimePerM: Double,
)
