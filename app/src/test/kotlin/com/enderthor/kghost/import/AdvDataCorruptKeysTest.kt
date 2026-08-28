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
 * REGRESSION LOCK: a corrupt `sourcekeys.json` must FAIL the rebuild's dedup reset, not pass it.
 *
 * [TrackStore.dropSourceKeys] proves the reset took by re-reading the file and comparing it to what it
 * meant to write. The lenient read maps a PRESENT-but-unparseable file to the empty set, so on
 * corruption that comparison was empty-vs-empty → SUCCESS, after writing `[]` over every RECORDED
 * ride's key. [prepareRebuild] then deleted `processed.json` on the strength of it, opening BOTH dedup
 * gates at once: every device `.fit` re-imported as a PERMANENT twin of the live ride it came from
 * (permanent because `selectArchivable` never touches a twin group of <= 3, and self-reproducing
 * because the twin is file-sourced, so the next rebuild archives and re-imports it again).
 *
 * The reset now reads through a corruption-aware path and fails CLOSED: nothing is written, the reset
 * reports failure, and [prepareRebuild] takes its REFUSE branch with the ledger intact.
 */
class AdvDataCorruptKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source) =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    private fun liveIds(dir: File) = TrackStore(dir).allTracksMeta().map { it.id }.toSet()

    // ─────────────────────────────────────────────────────────────────────────
    // B1. The unit: a corrupt keys file must not be reported as a successful reset, and must not be
    // overwritten — the keys it held are unknowable, so refusing is the only answer that cannot twin.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `dropSourceKeys fails closed on a corrupt keys file and writes nothing`() {
        val dir = tmp.newFolder("B1-tracks")
        val store = TrackStore(dir)
        store.add(decimated("rec-1", 1_700_000_000_000L, Source.RECORDED))
        store.add(decimated("rec-2", 1_700_000_060_000L, Source.RECORDED))
        assertEquals(2, store.knownSourceKeys().size)

        // A torn write / half-flushed page on a device that power-cycles daily.
        val corrupt = """["a","b"""
        File(dir, "sourcekeys.json").writeText(corrupt)

        assertFalse("the reset must NOT claim success", store.dropSourceKeys(setOf("some:archived:key")))
        assertEquals("the corrupt file is left exactly as found, not replaced by []",
            corrupt, File(dir, "sourcekeys.json").readText())
    }

    @Test fun `an ABSENT keys file is still a normal reset, not a corruption`() {
        val dir = tmp.newFolder("B1b-tracks")
        val store = TrackStore(dir)
        assertFalse(File(dir, "sourcekeys.json").exists())
        assertTrue("a cold start has nothing to drop and that is success", store.dropSourceKeys(setOf("k")))
        assertEquals(emptySet<String>(), store.knownSourceKeys())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B2. End to end: corrupt keys file + Rebuild → REFUSED, ledger intact, no twins.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a rebuild over a corrupt keys file refuses and twins nothing`() = runTest {
        val dir = tmp.newFolder("B2-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("B2-FitFiles")
        val importDir = tmp.newFolder("B2-KGhost")
        val decode = HashMap<String, RecordedTrack>()

        // 10 rides ridden on the Karoo: a live RECORDED track each, plus the Karoo's own .fit.
        (1..10).forEach { i ->
            val epoch = 1_800_000_000_000L + i * 60_000L
            store.add(decimated("rec-$i", epoch, Source.RECORDED))
            File(fitFiles, "ride$i.fit").writeText("x")
            decode["ride$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }
        // 4 imported rides whose files are still present, so the rebuild has something to do (and the
        // pre-flight file count passes: 14 files for 4 archivable tracks).
        (1..4).forEach { i ->
            val epoch = 1_700_000_000_000L + i * 60_000L
            store.add(decimated("fit-$epoch", epoch, Source.FITFILES_SCAN))
            File(fitFiles, "old$i.fit").writeText("x")
            decode["old$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }
        // A full import has already run, so the ledger knows every file (the second dedup gate).
        val ledgerFile = File(dir, "processed.json")
        ProcessedLedger(ledgerFile).let { l ->
            val m = l.load()
            (fitFiles.listFiles() ?: emptyArray()).forEach { l.mark(m, it, it.length(), it.lastModified()) }
            l.save(m)
        }
        assertEquals(14, liveIds(dir).size)

        // The corruption.
        File(dir, "sourcekeys.json").writeText("""["a","b""")

        assertEquals("the destructive phase REFUSES", 0, prepareRebuild(dir, fitFiles, importDir))
        assertTrue("the ledger — the surviving dedup gate — is untouched", ledgerFile.isFile)
        assertFalse("nothing was archived", File(dir, "archive").exists())

        HistoryImporter(
            fitFilesDir = fitFiles, importDir = importDir, trackStore = TrackStore(dir),
            fitDecode = { f, s -> decode[f.name]?.copy(source = s) },
            processedLedgerFile = ledgerFile,
        ).import(onlyNew = false).toList()

        val live = liveIds(dir)
        assertEquals("still 10 recorded + 4 imported, no twins", 14, live.size)
        (1..10).forEach { i ->
            val epoch = 1_800_000_000_000L + i * 60_000L
            assertTrue("the live ride survives", "rec-$i" in live)
            assertFalse("...and its .fit did NOT land a second copy", "fit-$epoch" in live)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B3. Control: the SAME corruption with an ordinary "Import all" was always harmless, because the
    // processed ledger still skips every unchanged file. It must stay that way — the fix is in the
    // rebuild's reset, not in the lenient read every other caller depends on.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `the same corruption without a rebuild is harmless`() = runTest {
        val dir = tmp.newFolder("B3-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("B3-FitFiles")
        val importDir = tmp.newFolder("B3-KGhost")
        val decode = HashMap<String, RecordedTrack>()
        (1..10).forEach { i ->
            val epoch = 1_800_000_000_000L + i * 60_000L
            store.add(decimated("rec-$i", epoch, Source.RECORDED))
            File(fitFiles, "ride$i.fit").writeText("x")
            decode["ride$i.fit"] = RecordedTrack("fit-$epoch", epoch, pts(), source = Source.FITFILES_SCAN)
        }
        val ledgerFile = File(dir, "processed.json")
        ProcessedLedger(ledgerFile).let { l ->
            val m = l.load()
            (fitFiles.listFiles() ?: emptyArray()).forEach { l.mark(m, it, it.length(), it.lastModified()) }
            l.save(m)
        }
        File(dir, "sourcekeys.json").writeText("""["a","b""")

        HistoryImporter(
            fitFilesDir = fitFiles, importDir = importDir, trackStore = TrackStore(dir),
            fitDecode = { f, s -> decode[f.name]?.copy(source = s) },
            processedLedgerFile = ledgerFile,
        ).import(onlyNew = false).toList()

        assertEquals("no twins — the ledger held", 10, liveIds(dir).size)
    }
}
