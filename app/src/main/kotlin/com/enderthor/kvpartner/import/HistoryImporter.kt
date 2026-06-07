package com.enderthor.kvpartner.import_

import com.enderthor.kvpartner.geo.RecordedTrack
import com.enderthor.kvpartner.geo.Source
import com.enderthor.kvpartner.geo.TrackDecimator
import com.enderthor.kvpartner.geo.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    private val lastScanSetter: (Long) -> Unit = {},
) {

    private enum class Kind { FITFILES_FIT, IMPORT_FIT, IMPORT_GPX }

    private data class WorkItem(val file: File, val kind: Kind)

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

        val total = workList.size
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
        // Chunk buffer plus the lastModified of the file each buffered track came from, so a flush
        // advances lastScan only past files whose tracks were actually written (L-F2 preserved).
        val chunk = ArrayList<RecordedTrack>(FLUSH_EVERY)
        val chunkLastModified = ArrayList<Long>(FLUSH_EVERY)
        // L-F2: highest lastModified among files whose decoded tracks have been FLUSHED so far.
        var maxFlushedLastModified = Long.MIN_VALUE

        // Flush the current chunk into the store, fold its counts into the running totals, advance
        // lastScan past the flushed files (success-only), and clear the buffer. Called per full
        // chunk and once more at end-of-loop. Each flush + lastScanSetter takes effect immediately,
        // so a CancellationException thrown afterwards cannot undo already-persisted work.
        suspend fun flushChunk() {
            if (chunk.isEmpty()) return
            val added = trackStore.addAll(chunk)
            imported += added
            skippedDuplicates += (chunk.size - added)
            val chunkMax = chunkLastModified.max()
            if (chunkMax > maxFlushedLastModified) maxFlushedLastModified = chunkMax
            // L-F2: advance per successful flush so a cancel after some flushes still leaves lastScan
            // correctly past them (re-run with onlyNew won't reprocess flushed files).
            if (maxFlushedLastModified > lastScanProvider()) lastScanSetter(maxFlushedLastModified)
            chunk.clear()
            chunkLastModified.clear()
        }

        workList.forEachIndexed { index, item ->
            // Per-file cooperative cancellation: a cancel is honored within one file, not ten.
            currentCoroutineContext().ensureActive()
            try {
                val track = when (item.kind) {
                    Kind.FITFILES_FIT -> fitDecode(item.file, Source.FITFILES_SCAN)
                    Kind.IMPORT_FIT -> fitDecode(item.file, Source.FIT_IMPORT)
                    Kind.IMPORT_GPX -> gpxParse(item.file)
                }
                if (track == null) {
                    failed++
                } else {
                    val decimated = decimate(track)
                    if (decimated.points.size < 2) {
                        // L-F1: a <2-point track is unusable/unraceable; count as failure, drop it.
                        failed++
                        Timber.w("import dropped %s: decimated to %d point(s)", item.file.name, decimated.points.size)
                    } else {
                        chunk.add(decimated)
                        chunkLastModified.add(item.file.lastModified())
                    }
                }
            } catch (e: CancellationException) {
                // A cooperative cancel must propagate (not be counted as a per-file failure); the
                // chunks flushed before it persist.
                throw e
            } catch (e: Exception) {
                failed++
                Timber.w(e, "import failed for %s", item.file.name)
            }

            // Chunked flush: independent of the PROGRESS_EVERY emit cadence below.
            if (chunk.size >= FLUSH_EVERY) flushChunk()

            val current = index + 1
            if (current % PROGRESS_EVERY == 0 || current == total) {
                emit(
                    ImportProgress(
                        ImportProgress.Phase.PARSING,
                        current = current,
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
