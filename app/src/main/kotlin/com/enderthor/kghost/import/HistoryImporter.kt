package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackDecimator
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Orchestrates a one-shot import sweep of recorded rides from the two known on-disk locations into
 * the [TrackStore], emitting [ImportProgress] as it goes.
 *
 * Sources scanned:
 *   - [fitFilesDir]: `.fit` files written by other apps / the Karoo (Source.FITFILES_SCAN).
 *   - [importDir]: user-dropped `.fit` (Source.FIT_IMPORT) and `.gpx` (Source.GPX_IMPORT) files.
 *
 * De-duplication is delegated to [TrackStore.addAll] via each track's sourceKey, so re-running the
 * sweep is idempotent. A corrupt or throwing file is counted as a failure and never aborts the
 * batch. With `onlyNew = true`, only files modified after the last recorded scan time are processed.
 *
 * Every dependency is injectable so the orchestration can be unit-tested with fakes and temp dirs;
 * the real defaults wire up [FitDecoder], [GpxParser] and the production decimation. [import] returns
 * a cold [Flow] and pins no dispatcher — the caller is expected to collect it on `Dispatchers.IO`.
 */
class HistoryImporter(
    private val fitFilesDir: File,
    private val importDir: File,
    private val trackStore: TrackStore,
    private val decimate: (RecordedTrack) -> RecordedTrack = HistoryImporter::defaultDecimate,
    private val fitDecode: (File, Source) -> RecordedTrack? = FitDecoder::decode,
    private val gpxParse: (File) -> RecordedTrack? = GpxParser::parse,
    private val lastScanProvider: () -> Long = { 0L },
    // suspend so the caller can AWAIT each persist: the lastScan write must be ordered and complete
    // before the run continues, otherwise N fire-and-forget writes can land out of order (a smaller
    // epoch winning) or swallow a failure, leaving lastScan stale (a re-run then reprocesses files).
    private val lastScanSetter: suspend (Long) -> Unit = {},
    // Persistent record of already-decoded+stored files (path+size+mtime), so a re-import skips the
    // expensive DECODE entirely for unchanged files instead of only deduping post-decode via
    // sourceKey. Defaulted next to fitFilesDir purely so existing/unit tests that don't care about
    // the ledger get a fresh, never-populated file; production always passes the real tracks dir
    // (see HistoryImportRunner).
    private val processedLedgerFile: File = File(fitFilesDir, ".processed_ledger.json"),
) {

    private enum class Kind { FITFILES_FIT, IMPORT_FIT, IMPORT_GPX }

    private data class WorkItem(val file: File, val kind: Kind)

    /** Outcome of decoding+decimating ONE file, produced by the decode workers and consumed by the
     *  single ordered collector. A [Failed] carries no data (its file was null/short/threw); the
     *  policy mirrors the old inline body. [Decoded] also carries the source [File] so the collector
     *  can mark it in the [ProcessedLedger] once its chunk is actually FLUSHED (persisted to the
     *  store) — not merely buffered. */
    private sealed interface DecodedOrFail {
        data class Decoded(val file: File, val track: RecordedTrack, val lastModified: Long) : DecodedOrFail
        object Failed : DecodedOrFail
    }

    fun import(onlyNew: Boolean): Flow<ImportProgress> = flow {
        // --- SCANNING ---
        val cutoff = lastScanProvider()
        fun passesFilter(f: File): Boolean = !onlyNew || f.lastModified() > cutoff

        val workList = ArrayList<WorkItem>()

        fitFilesDir.listFiles { f -> f.isFile && f.name.endsWith(".fit", ignoreCase = true) }
            ?.filter(::passesFilter)
            ?.forEach { workList.add(WorkItem(it, Kind.FITFILES_FIT)) }

        importDir.listFiles { f -> f.isFile && f.name.endsWith(".fit", ignoreCase = true) }
            ?.filter(::passesFilter)
            ?.forEach { workList.add(WorkItem(it, Kind.IMPORT_FIT)) }

        importDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
            ?.filter(::passesFilter)
            ?.forEach { workList.add(WorkItem(it, Kind.IMPORT_GPX)) }

        // Ledger filter: drop files already decoded+stored, unchanged since (path, size, mtime) —
        // these skip DECODE entirely, not just the post-decode sourceKey dedup. Ledger-skipped files
        // are EXCLUDED from `total` so imported + skippedDuplicates + failed == total still holds
        // (they are neither imported, skipped-duplicate, nor failed — they were never attempted).
        val ledger = ProcessedLedger(processedLedgerFile)
        val ledgerMap = ledger.load()
        val skippedByLedger = workList.count { ledger.isProcessed(ledgerMap, it.file) }
        val work = workList.filterNot { ledger.isProcessed(ledgerMap, it.file) }
        val total = work.size
        if (skippedByLedger > 0) Timber.d("import: ledger skipped %d already-processed files", skippedByLedger)
        emit(ImportProgress(ImportProgress.Phase.SCANNING, current = 0, total = total, 0, 0, 0))

        // --- PARSING ---
        // The expensive decode stays per-file (one FIT buffer at a time). The STORE write is now
        // flushed in chunks of FLUSH_EVERY (plus a final flush at end-of-loop) instead of a single
        // addAll after the loop, so:
        //   - already-flushed chunks SURVIVE a later cancel (the user's Cancel keeps prior work),
        //   - cancellation is cooperative within ONE file via ensureActive() at the top of each
        //     iteration (cancel latency is one file, not PROGRESS_EVERY files).
        // running totals — accumulated across flushes, finalized identically to the old single addAll.
        var failed = 0
        var imported = 0
        var skippedDuplicates = 0
        // Bulk sink: keeps the index + known keys IN MEMORY across every chunk of this run and
        // persists the aggregate index.json/sourcekeys.json ONCE at commit() (below, in the
        // finally), instead of trackStore.addAll's per-chunk read-modify-write of those files
        // (O(n^2) bookkeeping over ~48 chunks / 1200 files). Each <id>.json is still written
        // per-chunk by sink.addAll, so a mid-run Cancel keeps already-flushed tracks on disk; a
        // hard process kill (skipping the finally) leaves a stale index that
        // TrackStore.prewarmAndReconcile() repairs at next startup.
        val sink = trackStore.openBulkSink()
        // Chunk buffer plus the lastModified of the file each buffered track came from, so a flush
        // advances lastScan only past files whose tracks were actually written (L-F2 preserved).
        val chunk = ArrayList<RecordedTrack>(FLUSH_EVERY)
        val chunkLastModified = ArrayList<Long>(FLUSH_EVERY)
        // Source File of each buffered-but-not-yet-flushed track, parallel to `chunk`/
        // `chunkLastModified`. Marked into the ledger ONLY once its chunk is actually persisted by
        // flushChunk() below — never at buffer time — so a mid-run cancel (or crash) can never mark a
        // file "processed" whose track was never written to the store. This mirrors maxFlushedLastModified,
        // which likewise only advances past FLUSHED files.
        val chunkFiles = ArrayList<File>(FLUSH_EVERY)
        // L-F2: highest lastModified among files whose decoded tracks have been FLUSHED so far.
        var maxFlushedLastModified = Long.MIN_VALUE

        // Flush the current chunk into the store, fold its counts into the running totals, mark the
        // flushed files' ledger entries, advance lastScan past them (success-only), and clear the
        // buffers. Called per full chunk and once more at end-of-loop. The ledger mark happens BEFORE
        // the suspend lastScanSetter() call so a Cancel landing during that suspend still leaves the
        // ledger consistent with the sink (both already reflect this chunk) — see the note at the mark
        // call below. Each flush takes effect immediately, so a CancellationException thrown
        // afterwards cannot undo already-persisted work.
        suspend fun flushChunk() {
            if (chunk.isEmpty()) return
            val added = sink.addAll(chunk)
            imported += added
            skippedDuplicates += (chunk.size - added)
            val chunkMax = chunkLastModified.max()
            if (chunkMax > maxFlushedLastModified) maxFlushedLastModified = chunkMax
            // Mark the ledger for exactly the files whose tracks were just persisted above — NOT at
            // buffer time — so a cancel before this point leaves those files unmarked and therefore
            // re-importable on the next run (ledger.save() in the finally only persists marks already
            // recorded here, bounding it to actually-flushed work, same as lastScan/sink.commit()).
            // Done BEFORE the suspend lastScanSetter() call below: if a Cancel lands exactly during
            // that suspend, the ledger is already marked for this chunk, so sink (folded above) and
            // ledger (marked here) stay consistent with each other at the cancel point — only lastScan
            // itself may lag by one chunk, which is harmless (a re-run just re-decodes+skips those
            // already-ledgered files instead of re-storing them).
            chunkFiles.forEach { ledger.mark(ledgerMap, it) }
            // L-F2: advance per successful flush so a cancel after some flushes still leaves lastScan
            // correctly past them (re-run with onlyNew won't reprocess flushed files).
            if (maxFlushedLastModified > lastScanProvider()) lastScanSetter(maxFlushedLastModified)
            chunk.clear()
            chunkLastModified.clear()
            chunkFiles.clear()
        }

        // Decode + decimate ONE file. Returns Failed for null/short/throwing files (same policy as
        // the old inline body: null decode, <2 decimated points, or any non-cancellation exception).
        // Pure w.r.t. the collector's mutable bookkeeping (chunk/failed/…) so it is safe to run on N
        // workers; a CancellationException still PROPAGATES (never counted as a per-file failure).
        fun decodeOne(item: WorkItem): DecodedOrFail = try {
            val track = when (item.kind) {
                Kind.FITFILES_FIT -> fitDecode(item.file, Source.FITFILES_SCAN)
                Kind.IMPORT_FIT -> fitDecode(item.file, Source.FIT_IMPORT)
                Kind.IMPORT_GPX -> gpxParse(item.file)
            } ?: return DecodedOrFail.Failed
            val decimated = decimate(track)
            if (decimated.points.size < 2) {
                // L-F1: a <2-point track is unusable/unraceable; count as failure, drop it.
                Timber.w("import dropped %s: decimated to %d point(s)", item.file.name, decimated.points.size)
                DecodedOrFail.Failed
            } else {
                DecodedOrFail.Decoded(item.file, decimated, item.file.lastModified())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "import failed for %s", item.file.name)
            DecodedOrFail.Failed
        }

        try {
            // Bounded parallel decode: N = min(3, cores-1) workers overlap the expensive per-file
            // decode+decimate while a SINGLE collector keeps the chunk/flush/progress/lastScan
            // bookkeeping (so no synchronisation is needed on those). The whole fan-out lives inside
            // this try so Task 6's finally { sink.commit() } still runs on completion or cancel.
            val workers = minOf(3, (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1))
            coroutineScope {
                val items = Channel<WorkItem>(Channel.RENDEZVOUS)
                val decoded = Channel<DecodedOrFail>(capacity = workers * 2)

                // Producer: feed every work item, then close so workers drain and exit.
                launch {
                    work.forEach { items.send(it) }
                    items.close()
                }
                // Workers: decode in parallel; ensureActive() before each file bounds cancel latency
                // to one file per worker. decodeOne only ever throws CancellationException (it catches
                // every other Exception → Failed). A CancellationException raised INSIDE a launch child
                // cancels only that child — it does NOT bubble up to the coroutineScope on its own — so
                // we explicitly scope.cancel(e) to tear down the siblings + collector, then rethrow so
                // the cancel propagates out of the flow (and Task 6's finally { commit() } still runs).
                val scope = this
                val workerJobs = List(workers) {
                    launch {
                        try {
                            for (item in items) {
                                currentCoroutineContext().ensureActive()
                                decoded.send(decodeOne(item))
                            }
                        } catch (e: CancellationException) {
                            scope.cancel(e)
                            throw e
                        }
                    }
                }
                // Close `decoded` EXACTLY ONCE: a dedicated coroutine joins all workers, then closes.
                // Since every worker has finished before close() runs, no send can race the close.
                launch {
                    workerJobs.forEach { it.join() }
                    decoded.close()
                }

                // Single collector — identical chunk/flush/progress/lastScan bookkeeping as before,
                // just keyed on a completed-count (`processed`) that is monotonic regardless of the
                // order decode results arrive in.
                var processed = 0
                for (d in decoded) {
                    when (d) {
                        is DecodedOrFail.Failed -> failed++
                        is DecodedOrFail.Decoded -> {
                            chunk.add(d.track)
                            chunkLastModified.add(d.lastModified)
                            chunkFiles.add(d.file)
                        }
                    }

                    // Chunked flush: independent of the PROGRESS_EVERY emit cadence below.
                    if (chunk.size >= FLUSH_EVERY) flushChunk()

                    processed++
                    if (processed % PROGRESS_EVERY == 0 || processed == total) {
                        emit(
                            ImportProgress(
                                ImportProgress.Phase.PARSING,
                                current = processed,
                                total = total,
                                imported = imported,
                                skippedDuplicates = skippedDuplicates,
                                failed = failed,
                            ),
                        )
                    }
                }

                // --- STORE (final flush of the trailing partial chunk) ---
                flushChunk()
            }
        } finally {
            // Persist the in-memory index/sourcekeys ONCE — on normal completion AND on a Cancel, so
            // the flushed <id>.json files are always reflected in the aggregate bookkeeping. A hard
            // process kill (no finally) leaves a stale index that startup reconcile rebuilds.
            sink.commit()
            // Persist the ledger ONCE, alongside the sink commit above. `ledgerMap` was only ever
            // mutated inside flushChunk() — i.e. exactly for files whose chunk was already persisted
            // by sink.addAll() — so on a cancel this save is bounded to actually-flushed work, same as
            // maxFlushedLastModified/lastScan and sink.commit(). Any file that was decoded+buffered
            // but whose chunk never flushed (e.g. a cancel mid-chunk) is simply absent from ledgerMap
            // and will be correctly re-decoded+re-imported next run. Failures never entered a chunk,
            // so they were never marked and keep retrying too.
            ledger.save(ledgerMap)
        }

        // --- DONE ---
        // L-F2: lastScan has already been advanced per flush above to the highest lastModified among
        // FLUSHED files (success-only). A failed/unreadable file never entered a chunk, so it is
        // retried next run, and a pure-failure or empty run never advances lastScan. The completed
        // invariant imported + skippedDuplicates + failed == total holds (every file ends in exactly
        // one of: a flushed chunk → imported|skipped, or the failed path).
        emit(
            ImportProgress(
                ImportProgress.Phase.DONE,
                current = total,
                total = total,
                imported = imported,
                skippedDuplicates = skippedDuplicates,
                failed = failed,
            ),
        )
    }

    companion object {
        /** Emit a PARSING progress every this many processed files (plus always on the last). */
        private const val PROGRESS_EVERY = 10

        /**
         * Flush decoded+decimated tracks to the [TrackStore] every this many buffered items (plus a
         * final flush at end-of-loop). Flushing in chunks means a mid-run cancel keeps already-
         * flushed work instead of discarding the whole batch.
         */
        private const val FLUSH_EVERY = 25

        /**
         * Production decimation: drop samples closer than 20 m (by cumulative ride distance) to the
         * previously kept one, using a fresh stateful [TrackDecimator]. The first point is always
         * kept (the decimator's lastKept starts null).
         */
        fun defaultDecimate(track: RecordedTrack): RecordedTrack {
            val decimator = TrackDecimator(20.0)
            val kept = track.points.filter { decimator.shouldKeep(it.lat, it.lng, it.distanceM) }
            // Recompute the dedup key off the DECIMATED tail so a scanned/imported ride collapses
            // onto the same key ② (TrackRecorder) produces from its already-decimated buffer.
            val total = kept.lastOrNull()?.distanceM ?: 0.0
            return track.copy(points = kept, sourceKey = sourceKeyOf(track.startedAtEpoch, total))
        }
    }
}
