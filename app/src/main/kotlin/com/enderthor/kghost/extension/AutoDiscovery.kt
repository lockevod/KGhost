package com.enderthor.kghost.extension

import com.enderthor.kghost.data.KGhostConfig
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Automatic library work runs only on an OBSERVED Idle (null = not yet known) with all-files access. */
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
 * the runner's single flight and lost, so ONE pending request is kept and re-issued when it turns false.
 */
class AutoDiscovery(
    scope: CoroutineScope,
    private val rideState: StateFlow<RideState?>,
    private val runActive: StateFlow<Boolean>,
    private val hasAccess: () -> Boolean,
    private val start: (admit: suspend () -> Boolean) -> Boolean,
    private val cancelAuto: () -> Unit,
) {
    // Guards [pending] together with the check-then-start in [request]: the re-issue collector takes it
    // under the same monitor, so a refused start can't park a request after the collector already ran.
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
        scope.launch {
            runActive.collect { active ->
                if (active) return@collect
                val reason = synchronized(lock) { pending.also { pending = null } } ?: return@collect
                request(reason)
            }
        }
    }

    /** Callable from any thread. */
    fun request(reason: String) {
        val outcome = synchronized(lock) {
            when {
                runActive.value -> { pending = reason; "deferred" }
                start(admitFor(reason)) -> "started"
                // Refused by single flight: the previous run hasn't completed yet, so [runActive] is still
                // (or about to be seen) true and its fall re-issues this.
                else -> { pending = reason; "refused" }
            }
        }
        Timber.i("KVP discovery: request $reason → $outcome")
    }

    private fun admitFor(reason: String): suspend () -> Boolean = {
        val state = rideState.value
        val access = hasAccess()
        shouldAutoImport(state, access).also {
            if (!it) Timber.i("KVP discovery: request $reason → dropped (state=$state access=$access)")
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
 * it, and rides an automatic run stores meanwhile are announced next time.
 */
suspend fun consumeFoundRides(
    load: suspend () -> Int,
    update: suspend ((KGhostConfig) -> KGhostConfig) -> Boolean,
): Int? {
    if (load() <= 0) return null
    var n = 0
    val ok = update { n = it.pendingFoundRides; it.copy(pendingFoundRides = 0) }
    return n.takeIf { ok && it > 0 }
}
