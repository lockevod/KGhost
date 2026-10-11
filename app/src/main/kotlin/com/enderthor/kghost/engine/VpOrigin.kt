package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Is the ride now running the one a persisted resume state was cut in? The ONE rule shared by
 *  [GhostCheckpoint.continuesRide] and [VpOrigin]. Same epoch → same process, yes. Otherwise (fresh process)
 *  BOTH clocks must carry on: the cut AND the current ride past [CHECKPOINT_RESUME_MARGIN_M] (inside it, a new
 *  ride's start can't be told from a resume by odometer), odometer within the margin, and the ride clock not
 *  behind the cut's (minus [CHECKPOINT_ELAPSED_SLACK_S]). [cpRideElapsedS] < 0 = no clock evidence → no
 *  fresh-process resume. */
fun rideContinues(
    cpEpoch: Long, cpLastDist: Double, cpRideElapsedS: Double,
    epochNow: Long, distNow: Double, elapsedNow: Double,
): Boolean =
    cpEpoch == epochNow || (
        cpRideElapsedS >= 0.0 &&
            cpLastDist > CHECKPOINT_RESUME_MARGIN_M &&
            distNow > CHECKPOINT_RESUME_MARGIN_M &&
            abs(distNow - cpLastDist) <= CHECKPOINT_RESUME_MARGIN_M &&
            elapsedNow >= cpRideElapsedS - CHECKPOINT_ELAPSED_SLACK_S
        )

/** The Ghost-Pace (VP) race origin, persisted so a fresh process mid-ride resumes the race instead of
 *  re-stamping it at the current elapsed against the whole-ride odometer (field log e7fef4: D=4831m AHEAD).
 *  [originElapsedS]/[startDistM] are fixed once set; [lastDist]/[rideElapsedS]/[savedAtEpoch] are the
 *  continuity evidence, refreshed periodically in BOTH modes so a route-mode start still resumes. */
@Serializable
data class VpOrigin(
    val rideEpoch: Long,
    val originElapsedS: Double,
    val startDistM: Double,
    val lastDist: Double,
    val rideElapsedS: Double,
    val savedAtEpoch: Long,
) {
    fun withEvidence(lastDist: Double, rideElapsedS: Double, savedAtEpoch: Long) =
        copy(lastDist = lastDist, rideElapsedS = rideElapsedS, savedAtEpoch = savedAtEpoch)

    /** Upper-bounded only: a negative age is a wall clock that was behind at the save (cached pre-GPS time
     *  after a reboot, corrected later), not a different ride — [continues] is the strong gate. */
    fun isRecent(nowEpoch: Long, maxAgeMs: Long) = nowEpoch - savedAtEpoch <= maxAgeMs

    fun continues(epochNow: Long, distNow: Double, elapsedNow: Double) =
        rideContinues(rideEpoch, lastDist, rideElapsedS, epochNow, distNow, elapsedNow)
}

/** Can ([distM], [elapsedS]) be one ride's real state? A distance no bicycle covers in that clock
 *  ([AGG_MAX_SPEED_MS], plus the resume margin's slack) is a replayed/stale sample — a fresh subscription
 *  replaying the previous ride's odometer at elapsed≈0, or a resumed process whose ELAPSED_TIME has not
 *  caught up yet. The VP origin must not be stamped from one: wait for a coherent tick. */
fun vpOriginCoherent(distM: Double, elapsedS: Double): Boolean =
    distM <= elapsedS * AGG_MAX_SPEED_MS + CHECKPOINT_RESUME_MARGIN_M

/** The origin a VP race starting NOW takes: [saved]'s (re-keyed to [rideEpoch], evidence refreshed) iff it is
 *  recent and the same ride, else a fresh one at ([elapsedS], [distM]). */
fun startVpOrigin(saved: VpOrigin?, rideEpoch: Long, elapsedS: Double, distM: Double, nowEpoch: Long, maxAgeMs: Long): VpOrigin =
    if (saved != null && saved.isRecent(nowEpoch, maxAgeMs) && saved.continues(rideEpoch, distM, elapsedS))
        saved.copy(rideEpoch = rideEpoch).withEvidence(distM, elapsedS, nowEpoch)
    else VpOrigin(rideEpoch, elapsedS, distM, distM, elapsedS, nowEpoch)

/** The VP gap: distance ridden since the origin against race-elapsed since it, at the target pace. */
fun vpRaceGap(distM: Double, startDistM: Double, raceElapsedS: Double, curve: GhostCurve, fresh: Boolean): GapState =
    GapCalculator.compute(distM - startDistM, raceElapsedS, curve, fresh)
