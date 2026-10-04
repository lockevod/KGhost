package com.enderthor.kghost.extension

import com.enderthor.kghost.data.KGhostConfig
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Automatic library work runs only on an OBSERVED Idle (null = not yet known) with all-files access. The
 * master switch is checked separately, in the admit, because it is read from the store.
 */
fun shouldAutoImport(rideState: RideState?, hasAccess: Boolean): Boolean =
    rideState is RideState.Idle && hasAccess

/**
 * Decides when an AUTOMATIC import may run. Requests come from every transition into Idle (the first
 * one after startup, and every ride end) and from the app's resume; the decision itself is taken by
 * [admit], which the runner calls only after it holds the library lock — so a request queued behind a
 * long pass is judged on the ride state of THAT moment, not the one it was made in. Leaving Idle cancels
 * a running automatic import; a manual one is never touched ([cancelAuto]).
 *
 * [runActive] is the runner's in-flight flag: a request made while a run is active would be refused by
 * the runner's single flight and lost, so ONE pending request is kept and re-issued once it is false.
 */
class AutoDiscovery(
    private val scope: CoroutineScope,
    private val rideState: StateFlow<RideState?>,
    private val runActive: StateFlow<Boolean>,
    private val hasAccess: () -> Boolean,
    private val masterEnabled: suspend () -> Boolean,
    private val start: (admit: suspend () -> Boolean) -> Boolean,
    private val cancelAuto: () -> Unit,
) {
    // Guards [pending] together with the check-then-start in [request]: the re-issue waiter takes it
    // under the same monitor, so a refused start can't park a request after the waiter already ran.
    private val lock = Any()
    private var pending: String? = null

    init {
        // A StateFlow only emits on change, so every Idle seen here is a transition INTO Idle.
        scope.launch {
            rideState.collect { s ->
                when {
                    s is RideState.Idle -> request("idle")
                    s != null -> cancelAuto()
                }
            }
        }
    }

    /** Callable from any thread. */
    fun request(reason: String) {
        val outcome = synchronized(lock) {
            when {
                runActive.value -> { defer(reason); "deferred" }
                start(admitFor(reason)) -> "started"
                // Refused by single flight: the previous run hasn't completed yet, so [runActive] is still
                // true until its completion, and the waiter re-issues this once it is false.
                else -> { defer(reason); "refused" }
            }
        }
        Timber.i("KVP discovery: request $reason → $outcome")
    }

    /** Under [lock]. Waits on the CURRENT value, not on a true→false emission: a StateFlow conflates, so
     *  a run that starts and ends between two dispatches leaves no emission for a collector to see. */
    private fun defer(reason: String) {
        val waiting = pending != null
        pending = reason
        if (waiting) return
        scope.launch {
            runActive.first { !it }
            val r = synchronized(lock) { pending.also { pending = null } } ?: return@launch
            request(r)
        }
    }

    private fun admitFor(reason: String): suspend () -> Boolean = {
        val state = rideState.value
        val access = hasAccess()
        val master = masterEnabled()
        (master && shouldAutoImport(state, access)).also {
            if (!it) Timber.i("KVP discovery: request $reason → dropped (state=$state access=$access master=$master)")
        }
    }
}

/** The extension's single [AutoDiscovery], for the app's resume (same process). Null while no service. */
object AutoDiscoveryHub {
    @Volatile
    var instance: AutoDiscovery? = null
}

/**
 * Takes the "N past rides found" count for the alert at ride start: null unless N > 0 AND the clear
 * persisted — at most once, may be lost on a crash before the dispatch (preferred to a duplicate). The
 * clear reads N inside the write itself, so two near-simultaneous Recording emissions can't both claim
 * it, and rides an automatic run stores meanwhile are announced next time. With the master switch off
 * nothing is taken: the count waits for a ride with KGhost on.
 */
suspend fun consumeFoundRides(
    load: suspend () -> KGhostConfig,
    update: suspend ((KGhostConfig) -> KGhostConfig) -> Boolean,
): Int? {
    val cfg = load()
    if (!cfg.masterEnabled || cfg.pendingFoundRides <= 0) return null
    var n = 0
    val ok = update { n = it.pendingFoundRides; it.copy(pendingFoundRides = 0) }
    return n.takeIf { ok && it > 0 }
}

/**
 * A Recording that follows Idle or an unknown state (fresh process) is a ride START; one that follows
 * Paused is a resume, and one that follows Recording is a reconnect replay — neither re-announces.
 */
fun isFreshRideStart(prev: RideState?, state: RideState): Boolean =
    state is RideState.Recording && (prev == null || prev is RideState.Idle)
