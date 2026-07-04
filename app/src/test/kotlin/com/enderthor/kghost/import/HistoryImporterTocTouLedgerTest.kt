package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TOCTOU regression: a file that changes BETWEEN decodeOne (worker) and the ledger mark (collector)
 * must be ledgered with the stats captured AT DECODE TIME, not whatever the file's stats happen to
 * be when the collector gets around to marking it. Before the fix, `ledger.mark(map, file)` read
 * `file.length()`/`file.lastModified()` live at mark time — if the underlying file mutated in that
 * window (e.g. a mid-write FIT finishing between decode and mark), the ledger would record the
 * file's POST-mutation stats. A later import comparing the file's (by-then-settled) current stats
 * against those recorded stats would then match, so the file — even if it had gone on to become a
 * fully valid, never-yet-stored ride — would be silently skipped forever.
 *
 * This test simulates the race with a side-effecting `fitDecode` for "flaky.fit": as a side effect
 * of being decoded, it mutates the file's own mtime (standing in for "the file changed after
 * decodeOne captured its stats but before the collector marked it") and returns a <2-point
 * (Invalid) track, so the mark path under test is the Invalid one — the one Copilot's review
 * flagged.
 */
class HistoryImporterTocTouLedgerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun onePointTrack(id: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0)),
        sourceKey = "key-$id",
        source = Source.FIT_IMPORT,
    )

    private fun touch(dir: File, name: String, lastModified: Long): File =
        File(dir, name).apply { writeText("x"); setLastModified(lastModified) }

    @Test fun `ledger mark uses decode-time stats, not stats mutated mid-processing`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")
        val ledgerFile = File(tmp.newFolder("bookkeeping"), "processed.json")

        val decodeTimeMtime = 5_000L
        val mutatedMtime = 6_000L
        val flakyFile = touch(fitFilesDir, "flaky.fit", decodeTimeMtime)

        val store = TrackStore(tracksDir)

        fun makeImporter(onFlakyDecode: (() -> Unit)? = null) = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                if (f.name == flakyFile.name) {
                    onFlakyDecode?.invoke()
                    // Simulate the file mutating AFTER decodeOne captured its stats but before the
                    // collector marks it — e.g. a writer finishing mid-decode.
                    flakyFile.setLastModified(mutatedMtime)
                    onePointTrack("t-flaky")
                } else {
                    null
                }
            },
            gpxParse = { null },
            lastScanProvider = { 0L },
            processedLedgerFile = ledgerFile,
        )

        // --- FIRST RUN: flaky.fit decodes to Invalid, mutating its own mtime mid-decode. ---
        val firstImporter = makeImporter()
        val firstDone = firstImporter.import(onlyNew = false).toList().last()
        assertEquals(1, firstDone.total)
        assertEquals(1, firstDone.failed)

        // The file's CURRENT mtime is now the mutated one.
        assertEquals(mutatedMtime, flakyFile.lastModified())

        val ledger = ProcessedLedger(ledgerFile)
        val reloaded = ledger.load()
        val entry = reloaded[ledger.key(flakyFile)]
        assertNotNull("flaky.fit must be ledgered", entry)
        assertEquals(
            "ledger must record the DECODE-TIME mtime, not the mid-processing mutated one",
            decodeTimeMtime,
            entry!!.lastModified,
        )

        // Since the ledger holds the pre-mutation mtime but the file's current mtime is the
        // mutated one, isProcessed() must now report false — the file is due for re-decode.
        assertFalse(
            "file's current (mutated) stats must not match the decode-time ledger entry",
            ledger.isProcessed(reloaded, flakyFile),
        )

        // --- SECOND RUN: prove the mismatch actually causes a re-decode (end-to-end). ---
        var flakyDecodedOnSecondRun = false
        val secondImporter = makeImporter(onFlakyDecode = { flakyDecodedOnSecondRun = true })
        val secondDone = secondImporter.import(onlyNew = false).toList().last()

        assertTrue(
            "flaky.fit's stats no longer match the ledger entry, so it must be re-decoded, not silently skipped",
            flakyDecodedOnSecondRun,
        )
        assertEquals(1, secondDone.total)
    }
}
