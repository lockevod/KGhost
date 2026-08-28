package com.enderthor.kghost.import_

import android.content.Context
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackStorage
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
     * Full rebuild: archives every track that came from a FILE (so a re-decode does not create a
     * duplicate), clears both dedup gates ([clearImportDedup]), then runs a complete import — same
     * process-scoped job as [start], so leaving the screen doesn't cancel it either. `Source.RECORDED`
     * tracks are KEPT: they were recorded live and no source file can re-create them. If the
     * archive/clear step itself fails, the import still runs (against whatever dedup state remains)
     * rather than silently doing nothing.
     */
    fun rebuildAll(appContext: Context, configManager: ConfigurationManager, lastScanEpoch: Long) {
        if (!beginRun()) return
        job = scope.launch {
            runCatching {
                val dir = TrackStorage.tracksDir(appContext)
                val store = TrackStore(dir)
                val fileSourced = store.allTracks().filter { it.source != Source.RECORDED }.map { it.id }
                if (fileSourced.isNotEmpty()) store.archive(fileSourced)
                clearImportDedup(dir)
            }.onFailure { Timber.w(it, "rebuild: could not archive stale tracks / clear dedup gates") }
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
                fitFilesDir = File("/sdcard/FitFiles"),
                importDir = File("/sdcard/KGhost"),
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
            runCatching {
                val dir = TrackStorage.tracksDir(appContext)
                val model = GradePace.build(TrackStore(dir).allTracks())
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
 * Deletes BOTH import dedup gates in [tracksDir] so the next full import re-decodes every source file:
 *  - `processed.json` — the ledger, which skips a file whose size+mtime are unchanged.
 *  - `sourcekeys.json` — the post-decode dedup, which would otherwise drop each re-decoded track.
 * Clearing only one is a no-op: the ledger alone leaves the tracks dropped at store time, and the keys
 * alone leave the files never decoded. Missing files are not an error.
 */
fun clearImportDedup(tracksDir: File) {
    File(tracksDir, "processed.json").delete()
    File(tracksDir, "sourcekeys.json").delete()
}
