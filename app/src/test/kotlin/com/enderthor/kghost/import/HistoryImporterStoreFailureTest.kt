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
 * Regression coverage for the two halves of "a failed write is recorded as a successful import":
 *
 *  - H7: the store discarded [com.enderthor.kghost.geo.atomicWriteText]'s Boolean, so a track that
 *    never reached disk was still counted, keyed and — here — LEDGERED, which stops it ever being
 *    retried.
 *  - M6: `lastScan` is applied as an mtime cutoff during the directory scan, BEFORE the ledger
 *    partition. So advancing it past a file that failed removes that file from every subsequent
 *    `onlyNew` run, whether or not it was ledgered. The watermark must stay below the oldest
 *    transient failure of the run.
 *
 * The write failure is injected by occupying `<id>.json.tmp` with a directory — the same real
 * disk-full branch used by TrackStoreWriteFailureTest, with no hook in production code.
 */
class HistoryImporterStoreFailureTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, key: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.001, 0.001, 100.0, 10.0),
        ),
        sourceKey = key,
        source = Source.FIT_IMPORT,
    )

    @Test fun `a track the store cannot write is neither ledgered nor passed by the watermark`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")
        val ledgerFile = File(tmp.newFolder("bookkeeping"), "processed.json")

        // Three rides, ASCENDING mtime. The middle one is rigged to fail its store write, so the
        // newest file (1002) flushes successfully in the same chunk — that is what used to drag
        // lastScan up to 1002 and hide the failed 1001 from every future onlyNew scan.
        listOf(10_000L, 11_000L, 12_000L).forEachIndexed { i, mtime ->
            File(fitFilesDir, "f$i.fit").apply { writeText(""); setLastModified(mtime) }
        }
        assertTrue(File(tracksDir, "t1.json.tmp").mkdirs())

        var lastScan = 0L
        val store = TrackStore(tracksDir)
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                val i = f.name.removeSuffix(".fit").removePrefix("f")
                track("t$i", "key-$i")
            },
            gpxParse = { null },
            lastScanProvider = { lastScan },
            lastScanSetter = { lastScan = it },
            processedLedgerFile = ledgerFile,
        )

        val done = importer.import(onlyNew = false).toList().last()

        assertEquals(3, done.total)
        assertEquals(2, done.imported)
        assertEquals("the unwritable track is a failure, not a duplicate", 1, done.failed)
        assertEquals(0, done.skippedDuplicates)
        assertEquals(done.total, done.imported + done.skippedDuplicates + done.failed)

        assertFalse("the unwritten track's file must not exist", File(tracksDir, "t1.json").exists())

        val ledger = ProcessedLedger(ledgerFile)
        val marks = ledger.load()
        assertTrue(ledger.isProcessed(marks, File(fitFilesDir, "f0.fit")))
        assertTrue(ledger.isProcessed(marks, File(fitFilesDir, "f2.fit")))
        assertFalse("the failed file must stay unmarked", ledger.isProcessed(marks, File(fitFilesDir, "f1.fit")))

        // M6: the watermark must NOT have advanced past the failed file, even though a newer file
        // flushed successfully after it.
        assertTrue("lastScan ($lastScan) must stay below the failed file's mtime (11000)", lastScan < 11_000L)

        // And the whole point of that: the next onlyNew run still SEES it. Un-rig the write first so
        // the retry can succeed.
        File(tracksDir, "t1.json.tmp").delete()
        val decoded = mutableListOf<String>()
        val retry = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                decoded += f.name
                val i = f.name.removeSuffix(".fit").removePrefix("f")
                track("t$i", "key-$i")
            },
            gpxParse = { null },
            lastScanProvider = { lastScan },
            lastScanSetter = { lastScan = it },
            processedLedgerFile = ledgerFile,
        ).import(onlyNew = true).toList().last()

        assertEquals("only the previously-failed file is re-decoded", listOf("f1.fit"), decoded)
        assertEquals(1, retry.imported)
        assertTrue("the retry stores it for real", File(tracksDir, "t1.json").exists())
        assertTrue("with nothing failing, the watermark finally passes it", lastScan >= 11_000L)
    }

    @Test fun `a failure in a later chunk LOWERS a watermark an earlier flush already advanced`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles2")
        val importDir = tmp.newFolder("import2")
        val tracksDir = tmp.newFolder("tracks2")
        val ledgerFile = File(tmp.newFolder("bookkeeping2"), "processed.json")

        // 30 files against FLUSH_EVERY = 25, with the rigged file carrying the OLDEST mtime of the set.
        // The watermark STARTS above every file (50 s), so honouring the failure requires LOWERING it —
        // a forward-only implementation cannot pass, whatever order the parallel workers deliver results
        // in. Seeding the start value rather than relying on an earlier flush to raise it is what makes
        // that deterministic: worker ordering could otherwise clamp low from the very first result and
        // let a forward-only implementation through.
        val n = 30
        repeat(n) { i -> File(fitFilesDir, "f%02d.fit".format(i)).apply { writeText(""); setLastModified(20_000L + i * 1_000L) } }
        val oldFailing = File(fitFilesDir, "f%02d.fit".format(n - 1)).apply { setLastModified(1_000L) }
        assertTrue(File(tracksDir, "t${n - 1}.json.tmp").mkdirs())

        var lastScan = 50_000L
        val scans = mutableListOf<Long>()
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = TrackStore(tracksDir),
            decimate = { it },
            fitDecode = { f, _ ->
                val i = f.name.removeSuffix(".fit").removePrefix("f").trimStart('0').ifEmpty { "0" }
                track("t$i", "key-$i")
            },
            gpxParse = { null },
            lastScanProvider = { lastScan },
            lastScanSetter = { lastScan = it; scans += it },
            processedLedgerFile = ledgerFile,
        )

        val done = importer.import(onlyNew = false).toList().last()
        assertEquals(n, done.total)
        assertEquals(1, done.failed)
        assertEquals(n - 1, done.imported)
        assertTrue("the watermark started at 50000 and must be LOWERED below the failed file", lastScan < 1_000L)
        assertTrue("the lowering must actually have been written", scans.isNotEmpty())
        assertFalse(ProcessedLedger(ledgerFile).let { it.isProcessed(it.load(), oldFailing) })
    }
}
