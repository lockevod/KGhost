package com.enderthor.kvpartner.import_

import com.enderthor.kvpartner.geo.RecordedTrack
import com.enderthor.kvpartner.geo.Source
import com.enderthor.kvpartner.geo.TrackDecimator
import com.enderthor.kvpartner.geo.TrackStore
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
        // The expensive decode stays per-file (one FIT buffer at a time); only the STORE write is
        // batched via addAll after the loop, so imported/skipped stay 0 during PARSING and are
        // finalized once the batch lands.
        var failed = 0
        val accumulated = ArrayList<RecordedTrack>()
        // L-F2: advance lastScan only past files that were actually processed without failure.
        var maxProcessedLastModified = Long.MIN_VALUE

        workList.forEachIndexed { index, item ->
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
                        accumulated.add(decimated)
                        val lm = item.file.lastModified()
                        if (lm > maxProcessedLastModified) maxProcessedLastModified = lm
                    }
                }
            } catch (e: Exception) {
                failed++
                Timber.w(e, "import failed for %s", item.file.name)
            }

            val current = index + 1
            if (current % PROGRESS_EVERY == 0 || current == total) {
                emit(
                    ImportProgress(
                        ImportProgress.Phase.PARSING,
                        current = current,
                        total = total,
                        imported = 0,
                        skippedDuplicates = 0,
                        failed = failed,
                    ),
                )
            }
        }

        // --- STORE (batched, O(n)) ---
        val added = trackStore.addAll(accumulated)
        val imported = added
        val skippedDuplicates = accumulated.size - added

        // --- DONE ---
        // L-F2: only advance lastScan past files that decoded AND passed the <2-point guard. A
        // failed/unreadable file (lastModified still > lastScan) is therefore retried next run, and
        // a pure-failure or empty run never advances lastScan.
        if (maxProcessedLastModified > lastScanProvider()) lastScanSetter(maxProcessedLastModified)
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
