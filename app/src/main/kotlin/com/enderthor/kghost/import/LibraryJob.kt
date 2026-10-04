package com.enderthor.kghost.import_

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.reconcileOwed
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.TrackStore
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
    val newImporter: (onStored: (Int, Int) -> Unit) -> HistoryImporter,
    val sweep: (checkCancel: () -> Unit) -> Int?,
)

/**
 * One import plus the discharge of the library's durable debts (spec §2b). Lock-FREE: the caller holds
 * [com.enderthor.kghost.geo.LibraryLock]. Returns how many tracks it stored.
 *
 * The debts are set BEFORE any decode (a crash or cancel after a flush must still owe the model rebuild
 * and the twin reconciliation) and cleared only after their work verifiably succeeded. Automatic runs
 * count what they stored into `pendingFoundRides` even when cancelled, from the importer's own per-flush
 * count — UI progress lags a flush and would undercount.
 */
suspend fun runLibraryJob(
    deps: LibraryDeps,
    auto: Boolean,
    onlyNew: Boolean,
    onProgress: (ImportProgress) -> Unit,
): Int {
    val stored = AtomicInteger(0)
    try {
        deps.newImporter { added, _ -> stored.addAndGet(added) }.import(onlyNew).collect { p ->
            // The importer suspends on this emission, so the debts are durable before its first decode.
            if (p.phase == ImportProgress.Phase.SCANNING && p.total > 0) {
                val ok = withContext(NonCancellable) {
                    deps.updateConfig { it.copy(gradeModelDirty = true, reconcileGen = it.reconcileGen + 1) }
                }
                check(ok) { "could not persist the library debts; import aborted before decoding" }
            }
            onProgress(p)
        }
        dischargeDebts(deps)
        return stored.get()
    } finally {
        val n = stored.get()
        if (auto && n > 0) withContext(NonCancellable) {
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
    if (cfg.gradeModelDirty) {
        try {
            // STREAMED, one track at a time: the whole library in heap OOMs a Karoo.
            val builder = GradePace.Builder()
            TrackStore(deps.tracksDir).forEachTrack(checkCancel, builder::add)
            val model = builder.build()
            if (GradePaceStore(deps.tracksDir).save(model) && deps.updateConfig { it.copy(gradeModelDirty = false) }) {
                Timber.i("grade-pace model rebuilt: coveredM=%.0f", model.coveredM)
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
