package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression coverage for marking the [ProcessedLedger] at FLUSH time rather than at buffer time
 * (see HistoryImporter.flushChunk). Before the fix, a file was marked "processed" as soon as its
 * decoded track was appended to the in-memory `chunk` — before `sink.addAll(chunk)` ever ran. A
 * mid-run cancel (or crash) could then leave up to FLUSH_EVERY-1 decoded-but-never-flushed files
 * marked processed, so a re-run would skip them forever even though their tracks were never
 * persisted. Marking now happens inside flushChunk(), strictly after `sink.addAll(chunk)`
 * succeeds, so a mark can only exist for a file whose track is actually in the store.
 */
class HistoryImporterLedgerFlushTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, sourceKey: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.001, 0.001, 100.0, 10.0),
        ),
        sourceKey = sourceKey,
        source = Source.FIT_IMPORT,
    )

    private fun touch(dir: File, name: String): File =
        File(dir, name).apply { writeText("") }

    @Test fun `ledger marks only successfully-stored files, not the one that failed to decode`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")
        val ledgerFile = File(tmp.newFolder("bookkeeping"), "processed.json")

        // N = 30 is NOT a multiple of FLUSH_EVERY (25): one full chunk flushes mid-run, plus a
        // trailing partial chunk flushes at end-of-loop. One of the files (index 15) is rigged to
        // fail decoding — fitDecode returns null for it — so it must never reach a chunk at all.
        val n = 30
        val failIndex = 15
        repeat(n) { i ->
            touch(fitFilesDir, "f%02d.fit".format(i)).apply { setLastModified(1_000L + i) }
        }
        val failedFile = File(fitFilesDir, "f%02d.fit".format(failIndex))

        val store = TrackStore(tracksDir)

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                val id = f.name.removeSuffix(".fit")
                if (f.name == failedFile.name) null else track("t$id", "key-$id")
            },
            gpxParse = { null },
            lastScanProvider = { 0L },
            processedLedgerFile = ledgerFile,
        )

        val done = importer.import(onlyNew = false).toList().last()

        // Sanity: the DONE invariant holds and exactly one file failed.
        assertEquals(n, done.total)
        assertEquals(done.total, done.imported + done.skippedDuplicates + done.failed)
        assertEquals(1, done.failed)
        assertEquals(n - 1, done.imported)

        // The ledger, reloaded fresh from disk, must contain EXACTLY the N-1 successfully-stored
        // files (marked at flush time, after sink.addAll succeeded) and must NOT contain the file
        // that failed to decode (it never entered a chunk, so flushChunk() never marked it).
        val ledger = ProcessedLedger(ledgerFile)
        val reloaded = ledger.load()
        assertEquals(n - 1, reloaded.size)

        (0 until n).forEach { i ->
            val f = File(fitFilesDir, "f%02d.fit".format(i))
            if (i == failIndex) {
                assertFalse("failed file must not be marked processed", ledger.isProcessed(reloaded, f))
            } else {
                assertTrue("successfully-stored file must be marked processed", ledger.isProcessed(reloaded, f))
            }
        }
    }

    // NOTE on the cancel case (no deterministic test added here — Task 7's
    // `cancellation mid-run preserves already-flushed chunks and advances lastScan past them` in
    // HistoryImporterTest already covers cancel PROPAGATION and the sink/lastScan bounding; the
    // ledger now uses the identical mechanism):
    //
    // Before this fix, `chunkFiles`-equivalent tracking (then `processedFiles`) was populated in the
    // collector loop the moment a Decoded result was buffered — i.e. BEFORE flushChunk() ran for
    // that chunk. A cancel thrown after, say, file 26 of a 30-file run (as in the Task 7 cancel
    // test) would have already buffered files 26..29 into `chunk` without flushing them, yet those
    // files were already added to `processedFiles` and so got marked+saved in the `finally`. On the
    // next run they would be skipped by the ledger filter even though `sink.addAll` never ran for
    // them — the rides would be silently unrecoverable.
    //
    // After this fix, marking moves INSIDE flushChunk(), strictly after `sink.addAll(chunk)`
    // returns successfully, and `chunkFiles` is cleared alongside `chunk`/`chunkLastModified` on
    // every flush. A cancel thrown mid-chunk therefore finds `ledgerMap` mutated ONLY for chunks
    // that already flushed — the trailing buffered-but-unflushed files were never appended to
    // `ledgerMap`, so `ledger.save(ledgerMap)` in the `finally` persists marks for exactly the
    // flushed prefix, mirroring `maxFlushedLastModified`/`lastScan` and `sink.commit()`. Those
    // trailing files are therefore correctly re-decoded and re-imported on the next run instead of
    // being silently skipped.
}
