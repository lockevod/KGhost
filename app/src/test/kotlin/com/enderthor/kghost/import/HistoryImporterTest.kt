package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackDecimator
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryImporterTest {

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

    @Test fun `imports unique, skips duplicate, counts failure`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        // One unique track from fitFilesDir; one duplicate (.fit in importDir); one null (.gpx).
        val uniqueFit = touch(fitFilesDir, "unique.fit")
        val dupFit = touch(importDir, "dup.fit")
        val badGpx = touch(importDir, "bad.gpx")

        val store = TrackStore(tracksDir)

        val fitDecode: (File, Source) -> RecordedTrack? = { f, _ ->
            when (f.name) {
                "unique.fit" -> track("unique", "key-A")
                "dup.fit" -> track("dup", "key-A") // same sourceKey → duplicate
                else -> null
            }
        }
        val gpxParse: (File) -> RecordedTrack? = { f ->
            when (f.name) {
                "bad.gpx" -> null // failure
                else -> null
            }
        }

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = fitDecode,
            gpxParse = gpxParse,
            lastScanProvider = { 0L },
            lastScanSetter = {},
        )

        val emissions = importer.import(onlyNew = false).toList()
        val last = emissions.last()

        assertEquals(ImportProgress.Phase.DONE, last.phase)
        assertEquals(3, last.total)
        assertEquals(1, last.imported)
        assertEquals(1, last.skippedDuplicates)
        assertEquals(1, last.failed)

        // sanity: the unique track was actually persisted.
        assertEquals(listOf("unique"), store.allTrackIds())
    }

    @Test fun `defaultDecimate keys off decimated total, matching the recorder path`() {
        // DEFECT 1 regression: ② (TrackRecorder) keys off the DECIMATED tail; ③ (FitDecoder/
        // GpxParser) key off the RAW pre-decimation total. When the dropped tail crosses a 10 m
        // bucket boundary the two keys diverge and the same ride is stored twice.
        val startedAt = 1_700_000_000_000L

        // Dense ride: 51 points 0..250 m at 5 m spacing. A TrackDecimator(20.0) keeps
        // 0, 20, 40, ... 240 (decimated tail = 240 m, bucket 24), but the raw tail is 250 m
        // (bucket 25) — so the raw-keyed and decimated-keyed sourceKeys differ.
        val rawPoints = (0..50).map { i ->
            TrackPointDto(lat = 0.0, lng = 0.0, distanceM = i * 5.0, timeS = i.toDouble())
        }

        // What FitDecoder/GpxParser produce: sourceKey from the RAW total.
        val rawLastDistance = rawPoints.last().distanceM
        val rawTrack = RecordedTrack(
            id = "scan",
            startedAtEpoch = startedAt,
            points = rawPoints,
            sourceKey = sourceKeyOf(startedAt, rawLastDistance),
            source = Source.FITFILES_SCAN,
        )

        // ②'s path: run the same raw points through the decimator and key off the decimated tail.
        val decimator = TrackDecimator(20.0)
        val decimatedTotal = rawPoints
            .filter { decimator.shouldKeep(it.lat, it.lng, it.distanceM) }
            .last().distanceM
        val recorderKey = sourceKeyOf(startedAt, decimatedTotal)

        // Guard: the raw-keyed and decimated-keyed sourceKeys MUST differ, otherwise the test
        // would pass even on the buggy code and prove nothing.
        assertEquals(false, rawTrack.sourceKey == recorderKey)

        // ③'s path after the fix: defaultDecimate must recompute the key off the decimated tail.
        val decimatedTrack = HistoryImporter.defaultDecimate(rawTrack)
        assertEquals(recorderKey, decimatedTrack.sourceKey)
    }

    @Test fun `L-F1 a track decimating to under two points is failed and not stored`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        touch(fitFilesDir, "ok.fit")
        touch(fitFilesDir, "tiny.fit")

        val store = TrackStore(tracksDir)

        // Identity decimate; "tiny" returns a 1-point track → must be counted as failed & dropped.
        val onePoint = RecordedTrack(
            id = "tiny",
            startedAtEpoch = 1_000L,
            points = listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0)),
            sourceKey = "tiny",
            source = Source.FITFILES_SCAN,
        )

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ -> if (f.name == "ok.fit") track("ok", "ok-key") else onePoint },
            gpxParse = { null },
            lastScanProvider = { 0L },
            lastScanSetter = {},
        )

        val last = importer.import(onlyNew = false).toList().last()

        assertEquals(ImportProgress.Phase.DONE, last.phase)
        assertEquals(2, last.total)
        assertEquals(1, last.imported)
        assertEquals(0, last.skippedDuplicates)
        assertEquals(1, last.failed)
        assertEquals(listOf("ok"), store.allTrackIds())
    }

    @Test fun `L-F2 lastScan advances only past successfully processed files`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        val okFile = touch(fitFilesDir, "ok.fit").apply { setLastModified(5_000L) }
        touch(fitFilesDir, "boom.fit").apply { setLastModified(9_000L) }

        val store = TrackStore(tracksDir)

        var lastScan = 0L
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                if (f.name == "ok.fit") track("ok", "ok-key") else throw RuntimeException("boom")
            },
            gpxParse = { null },
            lastScanProvider = { lastScan },
            lastScanSetter = { lastScan = it },
        )

        importer.import(onlyNew = false).toList()

        // Advanced to the OK file's lastModified, NOT past the failed file (9_000) and NOT now().
        assertEquals(okFile.lastModified(), lastScan)
    }

    @Test fun `L-F2 lastScan never advances when every file fails`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        touch(fitFilesDir, "a.fit").apply { setLastModified(9_000L) }
        touch(importDir, "b.gpx").apply { setLastModified(9_000L) }

        val store = TrackStore(tracksDir)

        var setCalls = 0
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { _, _ -> null },
            gpxParse = { null },
            lastScanProvider = { 0L },
            lastScanSetter = { setCalls++ },
        )

        importer.import(onlyNew = false).toList()

        assertEquals(0, setCalls)
    }

    @Test fun `cancellation mid-run preserves already-flushed chunks and advances lastScan past them`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        // 30 files (> 1 chunk of FLUSH_EVERY=25) so the first flush lands before the run ends.
        // Increasing lastModified per file so the flushed prefix has a well-defined max.
        val n = 30
        repeat(n) { i ->
            touch(fitFilesDir, "f%02d.fit".format(i)).apply { setLastModified(1_000L + i) }
        }

        val store = TrackStore(tracksDir)
        val ledgerFile = File(tmp.newFolder("bookkeeping"), "processed.json")

        var lastScan = 0L
        // listFiles() order is OS-defined, so drive the cancel off a decode counter, not filenames:
        // the 26th decoded file throws CancellationException. By then the first chunk of 25 has
        // already been flushed; the cancel discards the rest. Track ids/keys are unique per file.
        var decodeCount = 0
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { f, _ ->
                decodeCount++
                if (decodeCount == 26) throw CancellationException("user cancel")
                val id = f.name.removeSuffix(".fit")
                track("t$id", "key-$id")
            },
            gpxParse = { null },
            lastScanProvider = { lastScan },
            lastScanSetter = { lastScan = it },
            processedLedgerFile = ledgerFile,
        )

        // CancellationException propagates out of the flow; the already-executed flush (+ its
        // lastScanSetter call) for the first chunk has taken effect. Catch it so the test can assert
        // on the surviving state rather than failing on the propagated cancel.
        var cancelled = false
        importer.import(onlyNew = false)
            .catch { e -> if (e is CancellationException) cancelled = true else throw e }
            .toList()

        // The run was cancelled mid-flight (no DONE).
        assertEquals(true, cancelled)

        // The first flushed chunk (25 files) MUST be persisted despite the cancel.
        val storedIds = store.allTrackIds()
        assertEquals(25, storedIds.size)

        // lastScan was advanced to the max lastModified among the FLUSHED files (success-only), so a
        // re-run with onlyNew won't reprocess them. (listFiles() order is OS-defined, so derive the
        // expected max from the files that actually got stored rather than hardcoding an index.)
        val expectedMax = storedIds
            .map { File(fitFilesDir, it.removePrefix("t") + ".fit").lastModified() }
            .max()
        assertEquals(expectedMax, lastScan)

        // Task 8: the ProcessedLedger is marked at FLUSH time only (inside flushChunk(), after
        // sink.addAll succeeds), and ledger.save() runs in the finally — so a cancel must leave the
        // ledger containing EXACTLY the flushed files, and NOT the ones merely decoded/buffered into
        // a chunk that never flushed. Reload from disk (not the in-memory map) so this exercises the
        // real save() written in the finally.
        val ledger = ProcessedLedger(ledgerFile)
        val reloaded = ledger.load()
        assertEquals(25, reloaded.size)

        val storedFiles = storedIds.map { File(fitFilesDir, it.removePrefix("t") + ".fit") }.toSet()
        storedFiles.forEach { f ->
            assertTrue("flushed file ${f.name} must be marked processed", ledger.isProcessed(reloaded, f))
        }

        // Every file NOT among the 25 flushed ones was, at best, decoded/buffered into a chunk that
        // never flushed (or never decoded at all) — it must be ABSENT from the ledger so it is
        // re-imported (not silently skipped) on the next run.
        val unflushedFiles = (0 until n)
            .map { File(fitFilesDir, "f%02d.fit".format(it)) }
            .filterNot { it in storedFiles }
        assertEquals(n - 25, unflushedFiles.size)
        unflushedFiles.forEach { f ->
            assertFalse("unflushed file ${f.name} must NOT be marked processed", ledger.isProcessed(reloaded, f))
        }
    }

    @Test fun `onlyNew with future lastScan processes nothing`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        touch(fitFilesDir, "a.fit")
        touch(importDir, "b.fit")
        touch(importDir, "c.gpx")

        val store = TrackStore(tracksDir)

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { _, _ -> track("x", "k") },
            gpxParse = { track("y", "k2") },
            // lastScan in the far future → every file's lastModified() is older → filtered out.
            lastScanProvider = { Long.MAX_VALUE },
            lastScanSetter = {},
        )

        val emissions = importer.import(onlyNew = true).toList()
        val last = emissions.last()

        assertEquals(ImportProgress.Phase.DONE, last.phase)
        assertEquals(0, last.total)
        assertEquals(0, last.imported)
        assertEquals(0, last.skippedDuplicates)
        assertEquals(0, last.failed)
    }
}
