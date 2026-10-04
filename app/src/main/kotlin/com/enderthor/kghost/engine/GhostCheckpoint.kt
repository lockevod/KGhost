package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Odometer proximity that lets a resume across a FRESH process (power-off, ride-app restart: new epoch,
 *  continuous distance) restore. Also the floor below which such a resume is refused: a checkpoint cut in
 *  the first [CHECKPOINT_RESUME_MARGIN_M] is indistinguishable by odometer from a new ride's start. */
const val CHECKPOINT_RESUME_MARGIN_M = 300.0

/** How far the ride clock may read BELOW the checkpoint's on a fresh-process resume. The host restores the
 *  ride with ELAPSED_TIME carrying on (field log e7fef4: 3030 s → 3277 s, 4449 s → 4587 s); a new ride
 *  restarts it from 0. Small slack for a host that restores from a slightly older save of its own. */
const val CHECKPOINT_ELAPSED_SLACK_S = 30.0

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
    // Ride ELAPSED_TIME (s) when written: the ride-clock evidence that a fresh process is resuming THIS ride
    // and not starting a new one. -1 = written before this field existed → no evidence, no fresh-process resume.
    val rideElapsedS: Double = -1.0,
    // The comparator of the race that earned [leadS] (the integrator's latch, not the route's current
    // classification). Null = written before comparators existed.
    val comparator: RaceComparator? = null,
    // The HISTORY race had consumed history before this cut → the resumed race shows its gap at once,
    // even at lead 0, instead of "---" until the next history metre.
    val historyVerdictSeen: Boolean = false,
) {
    /** The comparator a resumed race takes, or null → no resume (fresh race on the route's comparator).
     *  A same-ride checkpoint ([otherGatesPass]: recent, same route key, pick, continuous ride) resumes with
     *  the comparator that EARNED its lead, NOT the current route's: the race latched it, and a reroute onto a
     *  differently classified route keeps it, so a restart must too. A lead never changes comparator — it
     *  brings its own. Legacy blobs (null) are rejected: a 1.2.x lead may be target-earned (1.2.0 tier 4). */
    fun resumeComparator(otherGatesPass: Boolean): RaceComparator? = if (otherGatesPass) comparator else null

    /** Is the ride now being raced the one this checkpoint was written in? Same epoch → same process, yes.
     *  Otherwise (fresh process) BOTH clocks must carry on: the cut AND the current ride past the margin
     *  (inside it, a new ride's start can't be told from a resume by odometer: 310 m/35 s vs 15 m/10 s
     *  passed every other check), odometer within the margin, and the ride clock not behind the
     *  checkpoint's. Odometer proximity alone let a new ride adopt the lead of a ride that ended while
     *  KGhost was dead. Residual: a new ride on the same route, within 6 h, whose route loads near the old
     *  cut at a later clock — then the lead is from the same stretch. */
    fun continuesRide(rideEpoch: Long, riderDistNow: Double, elapsedNowS: Double): Boolean =
        this.rideEpoch == rideEpoch || (
            rideElapsedS >= 0.0 &&
                lastRiderDist > CHECKPOINT_RESUME_MARGIN_M &&
                riderDistNow > CHECKPOINT_RESUME_MARGIN_M &&
                abs(riderDistNow - lastRiderDist) <= CHECKPOINT_RESUME_MARGIN_M &&
                elapsedNowS >= rideElapsedS - CHECKPOINT_ELAPSED_SLACK_S
            )
}

/** The ONE way the race tick builds a checkpoint. [comparator] and [historyVerdictSeen] are REQUIRED here
 *  (the class defaults them only so legacy blobs decode): a checkpoint written without its comparator is
 *  rejected on every resume, which would silently kill the mid-ride resume with no test failing. */
fun raceCheckpoint(
    rideEpoch: Long,
    leadS: Double,
    lastRiderDist: Double,
    pick: GhostPick,
    vpTimePerM: Double,
    savedAtEpoch: Long,
    routeKey: String,
    rideElapsedS: Double,
    comparator: RaceComparator,
    historyVerdictSeen: Boolean,
): GhostCheckpoint = GhostCheckpoint(
    rideEpoch = rideEpoch,
    leadS = leadS,
    lastRiderDist = lastRiderDist,
    pick = pick,
    vpTimePerM = vpTimePerM,
    savedAtEpoch = savedAtEpoch,
    routeKey = routeKey,
    rideElapsedS = rideElapsedS,
    comparator = comparator,
    historyVerdictSeen = historyVerdictSeen,
)
