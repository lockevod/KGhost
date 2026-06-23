package com.enderthor.kghost.import_

import android.content.Context
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
     * Starts an import in the process scope. No-op if one is already running (so a double-tap or a
     * screen re-entry that re-fires can't stack two scans). [appContext] should be the APPLICATION
     * context (the work outlives the Activity). [lastScanEpoch] is the cutoff for `onlyNew`.
     */
    fun start(appContext: Context, configManager: ConfigurationManager, onlyNew: Boolean, lastScanEpoch: Long) {
        if (job?.isActive == true) return
        _progress.value = null
        _canceled.value = false
        // Deliberately NOT cleared here: if a prior import finished while the screen was away and the
        // host never refreshed its count, clearing it would lose that refresh. Every terminal path
        // below sets it back to true anyway, so leaving a stale `true` only means one extra (idempotent)
        // recount mid-run; clearing it could silently drop one. So we let the host consume it.
        _running.value = true
        job = scope.launch {
            try {
                val importer = HistoryImporter(
                    fitFilesDir = File("/sdcard/FitFiles"),
                    importDir = File("/sdcard/KGhost"),
                    trackStore = TrackStore(TrackStorage.tracksDir(appContext)),
                    decimate = HistoryImporter::defaultDecimate,
                    lastScanProvider = { lastScanEpoch },
                    lastScanSetter = { epoch ->
                        scope.launch { configManager.updateConfig { it.copy(lastScanEpoch = epoch) } }
                    },
                )
                importer.import(onlyNew = onlyNew).collect { _progress.value = it }
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
