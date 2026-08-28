package com.enderthor.kghost.import_

import android.content.Context
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.geo.TrackIdentity
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.managers.ConfigurationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Runs the history import (FitFiles / GPX scan) in a PROCESS-scoped coroutine — NOT a Composable
 * scope. Switching settings screens/tabs while it scans therefore does NOT cancel it: the screen only
 * OBSERVES [progress]/[running]/[canceled] and re-attaches (live) when it returns, instead of owning
 * the work. Before this, the import lived in a `rememberCoroutineScope()` and its progress in
 * `remember {}`, so leaving the screen cancelled the cold import flow (it really stopped) AND lost the
 * progress (it looked stopped). Single-writer StateFlow pattern, like GapStateHolder.
 */
object HistoryImportRunner {
    // SupervisorJob + IO: survives the Activity; the import is file-bound work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    /** Latest import progress, or null before the first import this process. */
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** True while an import is in flight. Drives the buttons' enabled state. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _canceled = MutableStateFlow(false)
    /** True when the last import ended via [cancel] (cleared at the next [start]). */
    val canceled: StateFlow<Boolean> = _canceled.asStateFlow()

    private val _pendingCompletion = MutableStateFlow(false)
    /**
     * A terminal import (DONE, ERROR, or a cancel that already flushed some chunks) the host has NOT
     * yet reacted to. Lets the screen refresh its recorded-track count EXACTLY ONCE per completion —
     * including one that finished while the user was on another screen — instead of re-firing every
     * time the screen is re-entered (the [progress] StateFlow keeps replaying its terminal value, so a
     * `LaunchedEffect` keyed on it would run again on each re-entry). The screen calls
     * [consumeCompletion] after refreshing.
     */
    val pendingCompletion: StateFlow<Boolean> = _pendingCompletion.asStateFlow()

    private val _preparing = MutableStateFlow(false)
    /**
     * True only during [rebuildAll]'s DESTRUCTIVE window — [prepareRebuild]'s dedup reset + archive,
     * before the import proper starts. The screen must NOT offer Cancel here: [TrackStore.archive] is
     * non-suspending so it always completes, and a cancel landing right after it would leave the whole
     * imported library in `archive/` with nothing re-importing it (and, without all-files access,
     * `archive/` unreachable from the device). A cancel AFTER this window is survivable and the screen
     * says so — see [rebuilding].
     */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val _rebuilding = MutableStateFlow(false)
    /**
     * True for the WHOLE of a [rebuildAll] run (not just its [preparing] window), so the screen can say
     * something a plain import's "Import canceled." cannot: after a rebuild's archive, a Cancel leaves
     * the library in `archive/` and re-importing it is one tap on "All". Cleared at the next [start].
     */
    val rebuilding: StateFlow<Boolean> = _rebuilding.asStateFlow()

    /** The two directories an import scans — one definition, so [rebuildAll]'s "are the source files
     *  still there?" guard counts exactly the files [runImport] will read. */
    private val fitFilesDir = File("/sdcard/FitFiles")
    private val importDir = File("/sdcard/KGhost")

    @Volatile
    private var job: Job? = null

    /**
     * Shared start-of-run guard: no-op (returns false) if a job is already active, otherwise resets
     * the per-run state and marks [running] true. Used by both [start] and [rebuildAll] so a
     * double-tap or a screen re-entry that re-fires can't stack two scans.
     */
    private fun beginRun(): Boolean {
        if (job?.isActive == true) return false
        _progress.value = null
        _canceled.value = false
        _rebuilding.value = false
        // Deliberately NOT cleared here: if a prior import finished while the screen was away and the
        // host never refreshed its count, clearing it would lose that refresh. Every terminal path
        // below sets it back to true anyway, so leaving a stale `true` only means one extra (idempotent)
        // recount mid-run; clearing it could silently drop one. So we let the host consume it.
        _running.value = true
        return true
    }

    /**
     * Starts an import in the process scope. No-op if one is already running (so a double-tap or a
     * screen re-entry that re-fires can't stack two scans). [appContext] should be the APPLICATION
     * context (the work outlives the Activity). [lastScanEpoch] is the cutoff for `onlyNew`.
     */
    fun start(appContext: Context, configManager: ConfigurationManager, onlyNew: Boolean, lastScanEpoch: Long) {
        if (!beginRun()) return
        job = scope.launch { runImport(appContext, configManager, onlyNew, lastScanEpoch) }
    }

    /**
     * Full rebuild: resets the dedup gates and archives every track that came from a FILE (so a
     * re-decode does not create a duplicate), then runs a complete import — same process-scoped job as
     * [start], so leaving the screen doesn't cancel it either. `Source.RECORDED` tracks are KEPT: they
     * were recorded live and no source file can re-create them.
     *
     * The destructive half is [prepareRebuild], which REFUSES to run at all unless the source files that
     * would re-import the library are still there (see its doc). Whether it ran or refused, the ordinary
     * import runs afterwards.
     */
    fun rebuildAll(appContext: Context, configManager: ConfigurationManager, lastScanEpoch: Long) {
        if (!beginRun()) return
        _rebuilding.value = true
        job = scope.launch {
            try {
                _preparing.value = true
                runCatching { prepareRebuild(TrackStorage.tracksDir(appContext), fitFilesDir, importDir) }
                    .onFailure { Timber.w(it, "rebuild: prepare failed; running an ordinary import instead") }
            } finally {
                _preparing.value = false
            }
            runImport(appContext, configManager, onlyNew = false, lastScanEpoch = lastScanEpoch)
        }
    }

    private suspend fun runImport(
        appContext: Context,
        configManager: ConfigurationManager,
        onlyNew: Boolean,
        lastScanEpoch: Long,
    ) {
        try {
            val importer = HistoryImporter(
                fitFilesDir = fitFilesDir,
                importDir = importDir,
                trackStore = TrackStore(TrackStorage.tracksDir(appContext)),
                decimate = HistoryImporter::defaultDecimate,
                lastScanProvider = { lastScanEpoch },
                // Awaited inside the importer's flush (suspend setter): persisting lastScan in
                // order — not via a detached scope.launch — prevents an out-of-order write from
                // leaving a stale epoch and surfaces a write failure instead of swallowing it.
                lastScanSetter = { epoch ->
                    configManager.updateConfig { it.copy(lastScanEpoch = epoch) }
                },
                processedLedgerFile = File(TrackStorage.tracksDir(appContext), "processed.json"),
            )
            importer.import(onlyNew = onlyNew).collect { _progress.value = it }
            // Rebuild the global gradient model from the freshly-imported history — off Main (this
            // whole job runs on Dispatchers.IO) and never allowed to fail the import: a bad rebuild
            // just leaves the ghost falling back to its neutral fill until the next successful one.
            // Gated on something having ACTUALLY been imported: a "New only" run that found nothing new
            // would otherwise re-parse the whole library to rebuild the identical model.
            if ((_progress.value?.imported ?: 0) > 0) runCatching {
                val dir = TrackStorage.tracksDir(appContext)
                // STREAMED, one track parsed at a time: the whole library in heap at once OOMs a Karoo,
                // and the failure would land here as a swallowed "rebuild failed" with no model.
                val builder = GradePace.Builder()
                TrackStore(dir).forEachTrack(builder::add)
                val model = builder.build()
                GradePaceStore(dir).save(model)
                Timber.i("grade-pace model rebuilt: coveredM=%.0f", model.coveredM)
            }.onFailure { Timber.w(it, "grade-pace rebuild failed; the ghost falls back to the neutral fill") }
            // Normal DONE: ask the host to refresh its track count once.
            _pendingCompletion.value = true
        } catch (e: CancellationException) {
            // A cancel may still have flushed chunks (partial work persists), so signal a
            // completion too — the count must reflect whatever was already written.
            _canceled.value = true
            _pendingCompletion.value = true
            throw e
        } catch (e: Exception) {
            // Surface real failures instead of swallowing them: leave a terminal ERROR (keeping
            // the last counts) so the screen shows an error line rather than a frozen progress
            // bar, and still let the host refresh (chunked flushes may have persisted tracks).
            Timber.w(e, "history import failed")
            val last = _progress.value
            _progress.value = (last ?: ImportProgress(ImportProgress.Phase.ERROR, 0, 0, 0, 0, 0))
                .copy(phase = ImportProgress.Phase.ERROR, message = e.message)
            _pendingCompletion.value = true
        } finally {
            _running.value = false
        }
    }

    /** Clears [pendingCompletion] once the host has refreshed its track count for this completion. */
    fun consumeCompletion() {
        _pendingCompletion.value = false
    }

    /** Cancels an in-flight import (sets [canceled]); no-op if none is running. */
    fun cancel() {
        job?.cancel()
    }
}

/**
 * The rebuild's DESTRUCTIVE prepare, hoisted out of [HistoryImportRunner.rebuildAll] (which needs a
 * `Context`) so it is plain-JVM testable: the tracks dir plus the two source dirs [HistoryImporter]
 * scans. Returns true ONLY when it actually archived.
 *
 * It refuses to touch anything unless the RECOVERABLE half is demonstrably there:
 *
 *  - Nothing file-sourced to archive → nothing to do.
 *  - Fewer source files than half the tracks about to be archived — ZERO INCLUDED → REFUSE. Archiving
 *    is only reversible by a re-import, so it must be contingent on the files that would do the
 *    re-importing still existing. The rider who imported a backup folder into `/sdcard/KGhost` (the
 *    workflow the button's hint describes) and later cleared it would otherwise move every track into
 *    `archive/` — unreachable on a Karoo without all-files access — while `listFiles` returned null and
 *    the screen reported a cheerful `0 imported`. Half, not "any missing file", is deliberately loose:
 *    rides pruned as twins and a handful of hand-deleted files must not block a legitimate rebuild,
 *    while a folder that was cleared, moved or never mounted is caught long before it can strand the
 *    library. A refusal costs only the altitude upgrade, and the next press retries.
 *  - The dedup reset not taking → REFUSE. It runs BEFORE the archive precisely so this refusal is still
 *    free: [atomicWriteText] preserves the old keys file on an IO error and reports nothing, so a reset
 *    that silently failed AFTER archiving would leave every old key in place, dedup every re-decoded
 *    track away, and end at "0 imported · N duplicates" with the whole library in `archive/`.
 *
 * The library is walked via [TrackStore.allTracksMeta] (one track parsed at a time). Loading every track
 * instead is ~230 MB at 1500 rides: the OOM would be swallowed by the caller's `runCatching` and the
 * button would appear to run while doing nothing at all.
 */
fun prepareRebuild(tracksDir: File, fitFilesDir: File, importDir: File): Boolean {
    val store = TrackStore(tracksDir)
    val meta = store.allTracksMeta()
    val ids = fileSourcedIds(meta)
    if (ids.isEmpty()) return false
    val available = HistoryImporter.sourceFileCount(fitFilesDir, importDir)
    if (available * 2 < ids.size) {
        Timber.w(
            "rebuild REFUSED: only %d source file(s) available to re-import %d file-sourced track(s); " +
                "archiving them would be unrecoverable. Running an ordinary import instead.",
            available, ids.size,
        )
        return false
    }
    if (!resetImportDedup(tracksDir, store, archivedSourceKeys(meta))) {
        Timber.w("rebuild REFUSED: the sourceKey rewrite did not take (IO error); nothing archived")
        return false
    }
    val moved = store.archive(ids)
    Timber.i("rebuild: archived %d of %d file-sourced track(s); %d source file(s) to re-read", moved, ids.size, available)
    return true
}

/**
 * Resets both import dedup gates so the next full import re-decodes every source file, and reports
 * whether it took:
 *  - `sourcekeys.json` — the post-decode dedup, which would otherwise drop each re-decoded track — has
 *    [dropKeys] SUBTRACTED from it ([TrackStore.dropSourceKeys]), NOT deleted and NOT overwritten.
 *  - `processed.json` — the ledger (skips a file whose size+mtime are unchanged) — is DELETED, but only
 *    once the fallible key rewrite has succeeded.
 *
 * Resetting only one is a no-op: the ledger alone leaves the tracks dropped at store time, and the keys
 * alone leave the files never decoded.
 *
 * Why subtract instead of delete: `defaultDecimate` recomputes a scanned ride's `sourceKey` off its
 * decimated tail precisely so an imported FIT collapses onto the key the live [TrackRecorder] produced
 * for the SAME ride. That key is the only thing stopping a live-recorded ride's FIT from being stored a
 * second time as its twin — and the pair never self-heals, because `selectArchivable` leaves twin groups
 * of <= 3 alone forever, so every aggregate that counts RIDES double-counts it. Dropping ONLY the
 * archived tracks' keys lets their files re-decode while every other key — including one written by a
 * ride that finished mid-rebuild — keeps that collapse working.
 */
fun resetImportDedup(tracksDir: File, store: TrackStore, dropKeys: Set<String>): Boolean {
    if (!store.dropSourceKeys(dropKeys)) return false
    File(tracksDir, "processed.json").delete() // absent is normal, not an error
    return true
}

/**
 * PURE data-safety rule of the rebuild: the ids safe to archive are exactly the tracks that came from a
 * FILE. A `Source.RECORDED` track was recorded live and NO source file can re-create it, so it must
 * never be returned here — this one predicate is what stands between the rebuild and destroying history.
 */
fun fileSourcedIds(tracks: List<TrackIdentity>): List<String> =
    tracks.filter { it.source != Source.RECORDED }.map { it.id }

/**
 * PURE companion rule: the source keys to DROP — those of the tracks being archived, so their files
 * re-decode. Everything else on disk is left alone by [TrackStore.dropSourceKeys], which is what keeps a
 * `RECORDED` ride's FIT collapsing onto it instead of landing a permanent duplicate — including a ride
 * that finished after the meta snapshot this is computed from. Empty keys are dropped from the set: they
 * are never deduped against anyway (see [TrackStore.add]) and subtracting "" would be a no-op.
 */
fun archivedSourceKeys(tracks: List<TrackIdentity>): Set<String> =
    tracks.filter { it.source != Source.RECORDED }
        .mapNotNull { it.sourceKey.takeIf(String::isNotEmpty) }
        .toSet()
