package com.enderthor.kghost.import_

import android.content.Context
import com.enderthor.kghost.geo.LibraryLock
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.geo.TrackIdentity
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.managers.ConfigurationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    /** True when the last import ended via [cancel] (cleared at the next manual [start]). */
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

    private val _rebuildRefused = MutableStateFlow(false)
    /**
     * True when [rebuildAll]'s destructive phase REFUSED (see [prepareRebuild]) and the ordinary import
     * ran in its place. Without this the refusal is log-only: the import that follows ends on a normal
     * "0 imported · N duplicates" and the rider, who was told nothing, taps Rebuild again. Cleared at
     * the next [start]/[rebuildAll].
     */
    val rebuildRefused: StateFlow<Boolean> = _rebuildRefused.asStateFlow()

    private val _refusedCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    /**
     * (source files found, rides needing them) for a rebuild refused BY THE FILE COUNT, or null when the
     * refusal had another cause (the keys rewrite not taking) or there was none. A refusal the rider
     * cannot act on retires the button for good: the ordinary workflow (drop a backup folder in
     * `/sdcard/KGhost`, import it, delete it to free space) leaves the library permanently short, and
     * "the ride files aren't all there" does not say WHAT to put back. These are the numbers that do.
     */
    val refusedCounts: StateFlow<Pair<Int, Int>?> = _refusedCounts.asStateFlow()

    private val _shortfall = MutableStateFlow(0)
    /**
     * How many of the tracks a [rebuildAll] ARCHIVED did not come back from the re-import ([rebuildShortfall]).
     * 0 for an ordinary [start], for a refused prepare, and for a healthy rebuild. Anything above 0 goes on the
     * summary line: those rides now exist ONLY in `archive/`, and the rider has to be told to go get them.
     */
    val shortfall: StateFlow<Int> = _shortfall.asStateFlow()

    /** The two directories an import scans — one definition, so [rebuildAll]'s "are the source files
     *  still there?" guard counts exactly the files the import will read. */
    private val fitFilesDir = File("/sdcard/FitFiles")
    private val importDir = File("/sdcard/KGhost")

    /** Test seams (null in production): the scope jobs launch in, and the deps they run with. */
    internal var scopeForTest: CoroutineScope? = null
    internal var depsForTest: ((Context) -> LibraryDeps)? = null
    /** Runs inside the completion handler, after its identity check and before its writes. */
    internal var completionHookForTest: (() -> Unit)? = null

    // Guards admission: [job] and [jobIsAuto] change together, and only here.
    private val admission = Any()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var jobIsAuto = false

    /** True while the admitted run is an AUTOMATIC one (queued on the lock or running). */
    val activeRunIsAuto: Boolean
        get() = synchronized(admission) { jobIsAuto && job?.isCompleted == false }

    /**
     * Single flight: refuses (false) until the previous job has COMPLETED — not merely been cancelled,
     * which would let a cancelled run's cleanup overlap the next one. On admission marks [running] and,
     * for a manual run, resets the per-run state; an automatic one keeps the last manual summary on screen
     * until its scan finds work (see [runImport]). The job starts only after it is published as [job], so the identity
     * check in [ifCurrent] can never miss its own run.
     */
    private fun launchRun(auto: Boolean, rebuild: Boolean, body: suspend (me: Job) -> Unit): Boolean =
        synchronized(admission) {
            if (job?.isCompleted == false) return false
            if (!auto) resetRunState(rebuild)
            // Deliberately NOT cleared here: if a prior import finished while the screen was away and the
            // host never refreshed its count, clearing it would lose that refresh. Every terminal path
            // sets it back to true anyway, so leaving a stale `true` only means one extra (idempotent)
            // recount mid-run; clearing it could silently drop one. So we let the host consume it.
            _running.value = true
            val j = (scopeForTest ?: scope).launch(start = CoroutineStart.LAZY) {
                val me = coroutineContext.job
                // Backstop for what fails OUTSIDE the import proper (resolving the dirs, [start]'s admit):
                // an exception escaping a SupervisorJob child would crash the process.
                try {
                    body(me)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publishError(me, e, show = !auto)
                }
            }
            // On COMPLETION, not in a finally: covers the whole job — lock wait, admission, body — and also
            // a job cancelled before its first dispatch, whose body (and any finally in it) never runs.
            // Under [admission]: `isCompleted` turns true BEFORE handlers run, so without the monitor a
            // start() on another thread could be admitted between the identity check and the writes, and
            // this stale handler would then clear the NEW run's [running]. (Reentrant if a cancel under
            // the monitor completes the job synchronously.)
            j.invokeOnCompletion { cause ->
                synchronized(admission) {
                    ifCurrent(j) {
                        completionHookForTest?.invoke()
                        // An automatic run was never started by the rider: the screen must not say
                        // "Import canceled." for it.
                        if (cause is CancellationException && !auto) _canceled.value = true
                        _running.value = false
                    }
                }
            }
            job = j
            jobIsAuto = auto
            j.start()
            true
        }

    private fun resetRunState(rebuild: Boolean) {
        _progress.value = null
        _canceled.value = false
        _rebuilding.value = rebuild
        _rebuildRefused.value = false
        _refusedCounts.value = null
        _shortfall.value = 0
    }

    /** Terminal/progress state is published only by the run that is still the current [job]. */
    private inline fun ifCurrent(me: Job, block: () -> Unit) {
        if (job === me) block()
    }

    private fun depsFor(appContext: Context, configManager: ConfigurationManager, lastScanEpoch: Long): LibraryDeps =
        depsForTest?.invoke(appContext) ?: run {
            val tracksDir = TrackStorage.tracksDir(appContext)
            LibraryDeps(
                tracksDir = tracksDir,
                fitFilesDir = fitFilesDir,
                importDir = importDir,
                config = { configManager.loadConfigFlow().first() },
                updateConfig = configManager::updateConfig,
                newImporter = { onStored ->
                    HistoryImporter(
                        fitFilesDir = fitFilesDir,
                        importDir = importDir,
                        trackStore = TrackStore(tracksDir),
                        decimate = HistoryImporter::defaultDecimate,
                        lastScanProvider = { lastScanEpoch },
                        // Awaited inside the importer's flush (suspend setter): persisting lastScan in
                        // order — not via a detached scope.launch — prevents an out-of-order write from
                        // leaving a stale epoch and surfaces a write failure instead of swallowing it.
                        lastScanSetter = { epoch ->
                            configManager.updateConfig { it.copy(lastScanEpoch = epoch) }
                        },
                        processedLedgerFile = File(tracksDir, "processed.json"),
                        onStored = onStored,
                    )
                },
                sweep = { c -> TrackStore(tracksDir).sweep(checkCancel = c) },
            )
        }

    /**
     * Starts an import in the process scope; returns false (and does nothing) while a previous job has
     * not completed, so a double-tap or a screen re-entry can't stack two scans. [appContext] should be
     * the APPLICATION context (the work outlives the Activity). [lastScanEpoch] is the cutoff for a
     * manual `onlyNew`; an [auto] run always scans everything (the ledger skips unchanged files).
     * [admit] runs AFTER [LibraryLock] is acquired — the moment the decision is current — and false
     * ends the job without importing.
     */
    fun start(
        appContext: Context,
        configManager: ConfigurationManager,
        onlyNew: Boolean,
        lastScanEpoch: Long,
        auto: Boolean = false,
        admit: (suspend () -> Boolean)? = null,
    ): Boolean = launchRun(auto, rebuild = false) { me ->
        LibraryLock.mutex.withLock {
            if (admit?.invoke() == false) return@withLock
            runImport(me, depsFor(appContext, configManager, lastScanEpoch), auto, onlyNew = if (auto) false else onlyNew)
        }
    }

    /**
     * Full rebuild: resets the dedup gates and archives every track that came from a FILE (so a
     * re-decode does not create a duplicate), then runs a complete import — same process-scoped job as
     * [start], so leaving the screen doesn't cancel it either. `Source.RECORDED` tracks are KEPT: they
     * were recorded live and no source file can re-create them.
     *
     * The destructive half is [prepareRebuild], which REFUSES to run at all unless the source files that
     * would re-import the library are still there (see its doc). Whether it ran or refused, the ordinary
     * import runs afterwards — and however many tracks it archived are then compared against what the
     * import stored, so a strand its file count could not foresee reaches the rider as [shortfall]
     * instead of as a cheerful "0 imported".
     */
    fun rebuildAll(appContext: Context, configManager: ConfigurationManager, lastScanEpoch: Long) {
        launchRun(auto = false, rebuild = true) { me ->
            // ONE lock hold for prepare + import: nothing may touch the library between the archive and
            // the re-import that restores it.
            LibraryLock.mutex.withLock {
                val deps = depsFor(appContext, configManager, lastScanEpoch)
                val prepared = try {
                    _preparing.value = true
                    runCatching {
                        prepareRebuild(
                            deps.tracksDir,
                            deps.fitFilesDir,
                            deps.importDir,
                            onShortOfFiles = { available, tracks -> _refusedCounts.value = available to tracks },
                        )
                    }
                        .onFailure { Timber.w(it, "rebuild: prepare failed; running an ordinary import instead") }
                        // A THROWN prepare folds into 0, NOT into a refusal: it is already logged with a
                        // stack trace and may have archived before it threw, so the shortfall line — not the
                        // refusal line — is the one that can still catch it.
                        .getOrDefault(0)
                } finally {
                    _preparing.value = false
                }
                _rebuildRefused.value = prepared == null // null ⇒ prepareRebuild REFUSED; the screen says so.
                val archived = prepared ?: 0
                runImport(me, deps, auto = false, onlyNew = false)
                // After runImport, so it sees the terminal counts. A cancel rethrows out of runImport and
                // never reaches here — that path already has its own "your rides are in archive/" line.
                _shortfall.value = rebuildShortfall(archived, _progress.value?.imported ?: 0)
                    .also { if (it > 0) Timber.w("rebuild STRANDED %d of %d archived track(s) in archive/", it, archived) }
            }
        }
    }

    private suspend fun runImport(me: Job, deps: LibraryDeps, auto: Boolean, onlyNew: Boolean) {
        // An automatic run surfaces only once its scan finds work: a zero-work or refused one (the common
        // case, at every Idle) must not wipe the result line of the rider's last Import/Rebuild.
        var shown = !auto
        try {
            runLibraryJob(deps, auto, onlyNew) { p ->
                ifCurrent(me) {
                    if (!shown && p.total > 0) { resetRunState(rebuild = false); shown = true }
                    if (shown) _progress.value = p
                }
            }
            // Normal DONE: ask the host to refresh its track count once.
            ifCurrent(me) { _pendingCompletion.value = true }
        } catch (e: CancellationException) {
            // A cancel may still have flushed chunks (partial work persists), so signal a
            // completion too — the count must reflect whatever was already written.
            ifCurrent(me) { _pendingCompletion.value = true }
            throw e
        } catch (e: Exception) {
            // Surface real failures instead of swallowing them: leave a terminal ERROR (keeping
            // the last counts) so the screen shows an error line rather than a frozen progress
            // bar, and still let the host refresh (chunked flushes may have persisted tracks).
            publishError(me, e, shown)
        }
    }

    private fun publishError(me: Job, e: Exception, show: Boolean) {
        Timber.w(e, "history import failed")
        ifCurrent(me) {
            if (show) {
                val last = _progress.value
                _progress.value = (last ?: ImportProgress(ImportProgress.Phase.ERROR, 0, 0, 0, 0, 0))
                    .copy(phase = ImportProgress.Phase.ERROR, message = e.message)
            }
            _pendingCompletion.value = true
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

    /** Cancels the active run only if it is an AUTOMATIC one; a manual import or rebuild is left alone. */
    fun cancelAuto() {
        synchronized(admission) { if (activeRunIsAuto) job?.cancel() }
    }
}

/**
 * The rebuild's DESTRUCTIVE prepare, hoisted out of [HistoryImportRunner.rebuildAll] (which needs a
 * `Context`) so it is plain-JVM testable: the tracks dir plus the two source dirs [HistoryImporter]
 * scans. Returns HOW MANY tracks it archived, or NULL when it REFUSED. Null and 0 are distinct on
 * purpose: both archive nothing, but a refusal is a decision the rider has to be TOLD about (the
 * ordinary import that follows reports a perfectly cheerful "0 imported · N duplicates", so a
 * log-only refusal reads as "the button did nothing" and gets tapped again).
 *
 * It refuses to touch anything unless the RECOVERABLE half is demonstrably there:
 *
 *  - Nothing file-sourced to archive → nothing to do.
 *  - FEWER source files than tracks about to be archived → REFUSE. Archiving is only reversible by a
 *    re-import, so it must be contingent on the files that would do the re-importing still existing.
 *    The rider who imported a backup folder into `/sdcard/KGhost` (the workflow the button's hint
 *    describes) and later cleared it would otherwise move every track into `archive/` — unreachable on
 *    a Karoo without all-files access — while `listFiles` returned null and the screen reported a
 *    cheerful `0 imported`.
 *
 *    Why one-file-per-track and not the old `available * 2 < ids.size` (i.e. "at least half"): the
 *    count is inflated relative to the tracks it is meant to vouch for, never deflated, so in a HEALTHY
 *    library `available >= ids.size` always holds and a stricter rule cannot produce a false refusal.
 *    Every file-sourced track was created by at least one source file, and the file→track mapping is
 *    many-to-one and lossy in the file's favour: `/sdcard/FitFiles` also holds the Karoo's own `.fit`
 *    for every RECORDED ride (each counted, none archivable, each re-importing to nothing), a rider's
 *    backup copy of a FIT folder counts the same ride two or three times, and files below the minimum
 *    distance are counted but store no track. A count that has fallen BELOW the number of tracks has
 *    therefore lost more than every one of those cushions, and at least one ride provably has no file to
 *    come back from.
 *
 *    NOT a cushion (the earlier wording claimed it was): a file that FAILS TO DECODE. A file the current
 *    build refuses — the sport gate rejects a hike whose FIT the PREVIOUS build imported as a ride — is
 *    counted in `available` while being unable to bring its track back, so it inflates the count in the
 *    WRONG direction and the pre-flight passes on a library that cannot be restored. Counting files can
 *    only disprove recoverability; that residue is caught after the fact by [rebuildShortfall].
 *
 *    [onShortOfFiles] is called with (files found, tracks needing them) before the refusal returns, so the
 *    screen can tell the rider HOW MANY files to put back instead of just "they aren't all there". The old
 *    rule tolerated stranding half the library BY
 *    CONSTRUCTION, and its slack was consumed by the FitFiles inflation before it protected anything:
 *    70 device FITs "covered" 120 orphaned imports, so the guard passed and every one of them stranded.
 *    A refusal costs only the altitude upgrade, and the next press retries.
 *  - The dedup reset not taking → REFUSE. It runs BEFORE the archive precisely so this refusal is still
 *    free: [atomicWriteText] preserves the old keys file on an IO error and reports nothing, so a reset
 *    that silently failed AFTER archiving would leave every old key in place, dedup every re-decoded
 *    track away, and end at "0 imported · N duplicates" with the whole library in `archive/`. An IO
 *    failure is the ONLY thing that can produce it — a corrupt or absent `sourcekeys.json` is a cache
 *    miss that [TrackStore.dropSourceKeys] recomputes from the library, and the rebuild proceeds.
 *
 * Counting files can never PROVE recoverability, only disprove it — so the surviving residue is caught
 * after the fact by [rebuildShortfall], which compares what was archived against what came back.
 *
 * The library is walked via [TrackStore.allTracksMeta] (one track parsed at a time). Loading every track
 * instead is ~230 MB at 1500 rides: the OOM would be swallowed by the caller's `runCatching` and the
 * button would appear to run while doing nothing at all.
 */
fun prepareRebuild(
    tracksDir: File,
    fitFilesDir: File,
    importDir: File,
    // onShortOfFiles stays LAST so the many `prepareRebuild(...) { available, tracks -> }` call sites
    // keep binding their trailing lambda to it.
    onShortOfFiles: (available: Int, tracks: Int) -> Unit = { _, _ -> },
): Int? {
    val store = TrackStore(tracksDir)
    val meta = store.allTracksMeta()
    val ids = fileSourcedIds(meta)
    if (ids.isEmpty()) return 0
    val available = HistoryImporter.sourceFileCount(fitFilesDir, importDir)
    if (available < ids.size) {
        onShortOfFiles(available, ids.size)
        Timber.w(
            "rebuild REFUSED: only %d source file(s) available to re-import %d file-sourced track(s); " +
                "archiving them would be unrecoverable. Running an ordinary import instead.",
            available, ids.size,
        )
        return null
    }
    if (!resetImportDedup(tracksDir, store, archivedSourceKeys(meta))) {
        Timber.w("rebuild REFUSED: the sourceKey rewrite did not take (the keys file could not be written); nothing archived")
        return null
    }
    val moved = store.archive(ids)
    Timber.i("rebuild: archived %d of %d file-sourced track(s); %d source file(s) to re-read", moved, ids.size, available)
    return moved
}

/**
 * How many rides an archive of [archived] tracks did NOT get back, given a re-import that stored
 * [imported]. The pre-flight guard counts FILES, which can disprove recoverability but never prove it
 * (a file may fail to decode, or no longer hold the ride it once did); this compares AFTER the fact,
 * and anything above zero is put on the summary line pointing at `archive/`. Silence is what turns a
 * recoverable strand into a lost library.
 *
 * ponytail: a source file that is genuinely NEW (never imported before) counts towards [imported] and
 * so can mask a shortfall of one. Tightening that needs per-track identity through the import, which is
 * not worth it for a line whose job is to say "go look in archive/".
 */
fun rebuildShortfall(archived: Int, imported: Int): Int = (archived - imported).coerceAtLeast(0)

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
fun resetImportDedup(
    tracksDir: File,
    store: TrackStore,
    dropKeys: Set<String>,
): Boolean {
    if (!store.dropSourceKeys(dropKeys)) return false
    File(tracksDir, LEDGER_FILE).delete() // absent is normal, not an error
    return true
}

/** The import's processed-file ledger, written into the tracks dir (see `ProcessedLedger`). */
private const val LEDGER_FILE = "processed.json"

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
