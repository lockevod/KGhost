package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REGRESSION LOCK: the rebuild must not strand a library it cannot re-import.
 *
 * Counting FILES in the two scan dirs can never PROVE that those files re-create the tracks about to be
 * archived — the count is inflated by the Karoo's own `.fit` per RECORDED ride, by a rider's backup copy
 * of a FIT folder, and by files that decode to nothing. So the rebuild now defends on two sides:
 *
 *  - BEFORE: [prepareRebuild] demands at least one source file per track it would archive (the old rule
 *    asked for half, and its slack was consumed by that inflation before it protected anything).
 *  - AFTER: [rebuildShortfall] compares what was archived against what the import stored, so the residue
 *    the file count cannot foresee reaches the rider instead of hiding behind a cheerful "0 imported".
 */
class AdvDataRebuildStrandTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 41 points at 25 m spacing (survives the production 20 m decimator intact). */
    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    /** A track exactly as `defaultDecimate` would produce it (so its sourceKey is the real one). */
    private fun decimated(id: String, epoch: Long, source: Source): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    private fun tracksDir(name: String) = tmp.newFolder(name)

    private fun liveIds(dir: File) = TrackStore(dir).allTracksMeta().map { it.id }.toSet()
    private fun archivedIds(dir: File) =
        (File(dir, "archive").listFiles() ?: emptyArray()).map { it.name.removeSuffix(".json") }.toSet()

    /** Runs the REAL importer the rebuild runs, with a decode that maps `<id>.fit` back to its track. */
    private suspend fun runImport(
        tracksDir: File,
        fitFilesDir: File,
        importDir: File,
        decodeByName: Map<String, RecordedTrack>,
    ): ImportProgress {
        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = TrackStore(tracksDir),
            fitDecode = { f, src -> decodeByName[f.name]?.copy(source = src) },
            processedLedgerFile = File(tracksDir, "processed.json"),
        )
        return importer.import(onlyNew = false).toList().last()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A1. The exact library the button's hint describes, and the case the old guard waved through:
    //   - 120 rides imported years ago from a phone backup dropped in /sdcard/KGhost. The rider later
    //     cleared that folder to free space. Their tracks are the only copy.
    //   - 70 rides ridden on the Karoo since installing KGhost. Each is a live RECORDED track AND has
    //     the Karoo's own .fit in /sdcard/FitFiles. Those FITs re-decode onto a sourceKey the RECORDED
    //     track already owns, so they import to NOTHING — pure duplicates padding the count.
    //
    // available = 70, ids = 120. The old rule (70 * 2 < 120) passed and archived all 120 for a
    // "0 imported · 70 duplicates" run. The new rule needs a file per track and refuses.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a library whose source files are gone is REFUSED, not archived`() = runTest {
        val dir = tracksDir("A1-tracks")
        val store = TrackStore(dir)

        // 120 file-sourced rides whose source files are GONE.
        val orphaned = (1..120).map { decimated("gpx-$it", 1_700_000_000_000L + it * 60_000L, Source.GPX_IMPORT) }
        orphaned.forEach { store.add(it) }

        // 70 live-recorded rides, each with its Karoo .fit still in /sdcard/FitFiles.
        val fitFiles = tmp.newFolder("A1-FitFiles")
        val importDir = tmp.newFolder("A1-KGhost") // the backup folder the rider emptied
        val decodeByName = HashMap<String, RecordedTrack>()
        (1..70).forEach { i ->
            val epoch = 1_800_000_000_000L + i * 60_000L
            store.add(decimated("rec-$i", epoch, Source.RECORDED))
            File(fitFiles, "ride$i.fit").writeText("x")
            decodeByName["ride$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }

        assertEquals("the count is 70 files for 120 archivable tracks", 70, HistoryImporter.sourceFileCount(fitFiles, importDir))
        assertEquals(190, liveIds(dir).size)
        val ledger = File(dir, "processed.json").also { it.writeText("""{"entries":{}}""") }

        assertEquals("REFUSED: nothing may be archived", 0, prepareRebuild(dir, fitFiles, importDir))

        assertEquals("the whole library is still live", 190, liveIds(dir).size)
        assertEquals("nothing was archived", emptySet<String>(), archivedIds(dir))
        assertTrue("a refusal keeps the ledger", ledger.isFile)
        assertEquals("...and every dedup key", 190, TrackStore(dir).knownSourceKeys().size)

        // The ordinary import that runs after a refusal changes nothing: every device FIT still
        // collapses onto the RECORDED ride it came from.
        val done = runImport(dir, fitFiles, importDir, decodeByName)
        assertEquals(0, done.imported)
        assertEquals(70, done.skippedDuplicates)
        assertEquals("no ride lost, no twin created", 190, liveIds(dir).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A2. The old rule's floor was "half": 50 files for 100 tracks passed on the exact boundary and
    // stranded 50 rides. One file per track is the floor now.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `half the files present is refused, not tolerated`() = runTest {
        val dir = tracksDir("A2-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("A2-FitFiles")
        val importDir = tmp.newFolder("A2-KGhost")

        // 100 imported rides; only the first 50 still have their .fit on disk.
        (1..100).forEach { i ->
            val epoch = 1_700_000_000_000L + i * 60_000L
            store.add(decimated("fit-$epoch", epoch, Source.FITFILES_SCAN))
            if (i <= 50) File(fitFiles, "r$i.fit").writeText("x")
        }

        assertEquals(50, HistoryImporter.sourceFileCount(fitFiles, importDir))
        assertEquals("50 files can never bring back 100 rides", 0, prepareRebuild(dir, fitFiles, importDir))
        assertEquals(100, liveIds(dir).size)
        assertEquals(emptySet<String>(), archivedIds(dir))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A3. `sourceFileCount` still counts the SAME ride two or three times when the rider keeps a backup
    // copy of their FIT folder. That is WHY the count can only ever be a floor test and never a proof —
    // it errs upward, so it can disprove recoverability but not establish it. Locked so the asymmetry
    // the new rule is justified by stays true.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `the availability count errs upward — one ride can count as three files`() {
        val fitFiles = tmp.newFolder("A3-FitFiles")
        val importDir = tmp.newFolder("A3-KGhost")
        File(fitFiles, "ride.fit").writeText("x")
        File(importDir, "ride.fit").writeText("x")     // same ride, backed up
        File(importDir, "ride.gpx").writeText("x")     // and exported as GPX
        assertEquals("one ride counts as three source files", 3, HistoryImporter.sourceFileCount(fitFiles, importDir))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A4. THE residue: files that pass the pre-flight count but decode to nothing. The guard cannot see
    // this coming — only the after-the-fact comparison can, and the rider is told to go look in archive/.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a rebuild whose files no longer decode reports the shortfall`() = runTest {
        val dir = tracksDir("A4-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("A4-FitFiles")
        val importDir = tmp.newFolder("A4-KGhost")
        val decode = HashMap<String, RecordedTrack>()

        // 100 imported rides, ALL 100 files present — so the pre-flight guard passes — but half of
        // them are truncated and decode to null.
        (1..100).forEach { i ->
            val epoch = 1_700_000_000_000L + i * 60_000L
            store.add(decimated("fit-$epoch", epoch, Source.FITFILES_SCAN))
            File(fitFiles, "r$i.fit").writeText("x")
            if (i <= 50) decode["r$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }

        val archived = prepareRebuild(dir, fitFiles, importDir)
        assertEquals("100 files for 100 tracks: the guard cannot know they are unreadable", 100, archived)

        val done = runImport(dir, fitFiles, importDir, decode)
        assertEquals(50, done.imported)
        assertEquals(50, done.failed)
        assertEquals(
            "the 50 rides that did not come back are REPORTED, not silently left in archive/",
            50, rebuildShortfall(archived, done.imported),
        )
        assertEquals("and they are still recoverable from archive/", 100, archivedIds(dir).size)
    }

    @Test fun `a healthy rebuild reports no shortfall`() = runTest {
        val dir = tracksDir("A5-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("A5-FitFiles")
        val importDir = tmp.newFolder("A5-KGhost")
        val decode = HashMap<String, RecordedTrack>()
        (1..20).forEach { i ->
            val epoch = 1_700_000_000_000L + i * 60_000L
            store.add(decimated("fit-$epoch", epoch, Source.FITFILES_SCAN))
            File(fitFiles, "r$i.fit").writeText("x")
            decode["r$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }

        val archived = prepareRebuild(dir, fitFiles, importDir)
        assertEquals(20, archived)
        val done = runImport(dir, fitFiles, importDir, decode)
        assertEquals(20, done.imported)
        assertEquals("nothing to warn about", 0, rebuildShortfall(archived, done.imported))
        assertEquals("every ride is back, live", 20, liveIds(dir).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A6. `allTrackIds()` must exclude processed.json — the ledger HistoryImportRunner writes INTO the
    // tracks dir. MainActivity's "stored rides" count is `allTrackIds().size`, and that is the number a
    // rider reads to check whether a rebuild brought their rides back.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `the processed ledger is not counted as a stored ride`() = runTest {
        val dir = tracksDir("A6-tracks")
        val fitFiles = tmp.newFolder("A6-FitFiles")
        val epoch = 1_700_000_000_000L
        File(fitFiles, "r.fit").writeText("x")
        runImport(dir, fitFiles, tmp.newFolder("A6-KGhost"),
            mapOf("r.fit" to RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)))

        assertTrue(File(dir, "processed.json").isFile)
        assertEquals("one real ride", 1, liveIds(dir).size)
        assertEquals("...and the UI's count agrees", 1, TrackStore(dir).allTrackIds().size)
    }
}
