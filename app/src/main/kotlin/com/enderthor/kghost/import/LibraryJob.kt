package com.enderthor.kghost.import_

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.reconcileOwed
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.RecordedTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything one library job touches, injected so the job body runs on plain JVM with temp dirs and stub
 * decoders. Production builds it in [HistoryImportRunner] from the real dirs, decoders and config store.
 */
data class LibraryDeps(
    val tracksDir: File,
    val fitFilesDir: File,
    val importDir: File,
    val config: suspend () -> KGhostConfig,
    val updateConfig: suspend ((KGhostConfig) -> KGhostConfig) -> Boolean,
    val newImporter: (onStored: (List<RecordedTrack>, Int) -> Unit) -> HistoryImporter,
    val sweep: (checkCancel: () -> Unit) -> Int?,
)

/**
 * One import plus the discharge of the library's durable debts (spec §2b). Lock-FREE: the caller holds
 * [com.enderthor.kghost.geo.LibraryLock]. Returns how many tracks it stored.
 *
 * The debts are set BEFORE any decode (a crash or cancel after a flush must still owe the model rebuild
 * and the twin reconciliation) and cleared only after their work verifiably succeeded. Automatic runs
 * count what they stored into `pendingFoundRides` even when cancelled, from the importer's own per-flush
 * report — UI progress lags a flush and would undercount.
 *
 * A scan that finds files but changes nothing (every file a duplicate without new altitude, or invalid) must
 * not trigger a whole-library model rebuild and twin sweep after every ride (Copilot finding). So a run that
 * COMPLETES normally with zero stored tracks and zero enriched hands back the debts IT set: the model flag
 * only if it was clear before, the reconcile debt only by acking the gen this run wrote (gen/ack semantics:
 * a recorder save that bumped the gen meanwhile stays owed). Debts owed from before are kept and paid as
 * usual. Cancelled or failed runs never clear anything. Only tracks that started before
 * [KGhostConfig.discoveryEpoch] count: a later ride was recorded live, so its FIT is a twin, not a find.
 */
suspend fun runLibraryJob(
    deps: LibraryDeps,
    auto: Boolean,
    onlyNew: Boolean,
    onProgress: (ImportProgress) -> Unit,
): Int {
    val stored = AtomicInteger(0)
    val found = AtomicInteger(0)
    val mutated = AtomicInteger(0)
    var debtsSet = false
    var modelOwedBefore = true
    var reconcileOwedBefore = true
    var bumpedGen = 0L
    // 0 (not stamped yet) counts nothing: no ride starts before the epoch 0.
    val cutoff = if (auto) deps.config().discoveryEpoch else 0L
    try {
        deps.newImporter { added, enriched ->
            stored.addAndGet(added.size)
            mutated.addAndGet(added.size + enriched)
            found.addAndGet(added.count { it.startedAtEpoch < cutoff })
        }.import(onlyNew).collect { p ->
            // The importer suspends on this emission, so the debts are durable before its first decode.
            if (p.phase == ImportProgress.Phase.SCANNING && p.total > 0) {
                val ok = withContext(NonCancellable) {
                    deps.updateConfig {
                        // Only the FIRST write sees the pre-run state; later SCANNING emissions see our own debt.
                        if (!debtsSet) {
                            modelOwedBefore = it.gradeModelDirty
                            reconcileOwedBefore = it.reconcileOwed()
                        }
                        bumpedGen = it.reconcileGen + 1 // the LAST value written, so the ack covers every bump of ours
                        it.copy(gradeModelDirty = true, reconcileGen = it.reconcileGen + 1)
                    }
                }
                debtsSet = debtsSet || ok
                check(ok) { "could not persist the library debts; import aborted before decoding" }
            }
            onProgress(p)
        }
        if (debtsSet && mutated.get() == 0) {
            val ok = withContext(NonCancellable) {
                deps.updateConfig {
                    it.copy(
                        gradeModelDirty = it.gradeModelDirty && modelOwedBefore,
                        reconcileAckGen = if (reconcileOwedBefore) it.reconcileAckGen else maxOf(it.reconcileAckGen, bumpedGen),
                    )
                }
            }
            if (!ok) Timber.w("no-op scan could not hand its debts back; they stay owed")
        }
        dischargeDebts(deps)
        return stored.get()
    } finally {
        val n = found.get()
        if (n > 0) withContext(NonCancellable) {
            if (!deps.updateConfig { it.copy(pendingFoundRides = it.pendingFoundRides + n) }) {
                Timber.w("could not persist %d found ride(s); the next-ride notice will miss them", n)
            }
        }
    }
}

/** Runs on EVERY run, zero-work included: a debt left by a cancelled or crashed run is paid here. */
private suspend fun dischargeDebts(deps: LibraryDeps) {
    val cfg = deps.config()
    val ctx = currentCoroutineContext()
    val checkCancel = { ctx.ensureActive() }
    val gradeStore = GradePaceStore(deps.tracksDir)
    // A missing or older-schema model is owed too: that is how a schema bump makes the first run after an
    // upgrade rebuild once over the whole archive (an older but servable model keeps serving meanwhile).
    // No loop: save() writes even an empty model.
    // The debt goes down BEFORE the rebuild: once a current-schema model is saved, nothing but the flag would
    // retry a partial one, so a post-save write that fails or is cancelled must not be what records it.
    // If the debt can't be persisted, skip the rebuild this run (the stale schema keeps it owed).
    if (cfg.gradeModelDirty || (!gradeStore.isCurrent() &&
            withContext(NonCancellable) { deps.updateConfig { it.copy(gradeModelDirty = true) } })
    ) {
        try {
            // STREAMED, one track file at a time (live + archive, cached per file): the library in heap OOMs a Karoo.
            val r = gradeStore.rebuild(checkCancel)
            if (r.saved && !r.complete) {
                // A partial model beats none, but it is OWED: the debt set above stays; the fold cache makes
                // the retry cheap.
                Timber.w("grade-pace model rebuilt from a partial read; the debt stays for the next run")
            } else if (r.saved && deps.updateConfig { it.copy(gradeModelDirty = false) }) {
                Timber.i("grade-pace model rebuilt: coveredM=%.0f", r.model.coveredM)
            } else {
                Timber.w("grade-pace model not persisted; the debt stays for the next run")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "grade-pace rebuild failed; the ghost falls back to the neutral fill")
        }
    }
    // Ack the gen read BEFORE the sweep: a ride saved while it runs bumps the gen and stays owed.
    val gen = cfg.reconcileGen
    if (cfg.reconcileOwed() && cfg.autoTidy) {
        try {
            if (deps.sweep(checkCancel) != null) {
                if (!deps.updateConfig { it.copy(reconcileAckGen = maxOf(it.reconcileAckGen, gen)) }) {
                    Timber.w("reconcile sweep done but its ack did not persist; it will rerun")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "reconcile sweep failed; the debt stays for the next run")
        }
    }
}
