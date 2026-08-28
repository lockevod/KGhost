package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REGRESSION LOCK — the corrupt-`sourcekeys.json` recovery, in EVERY writer of that file.
 *
 * The recovery used to live in ONE writer, [TrackStore.dropSourceKeys] (the rebuild's reset), while the
 * two writers an ORDINARY import runs — `TrackStore.addAll` and `TrackStore.BulkSink.commit` — still read
 * it leniently (corrupt -> empty set) and then OVERWROTE it. That turned a corrupt keys file into a VALID
 * one having lost every live-recorded ride's key: the exact damage the guard exists to prevent, plus the
 * destruction of the "present but unparseable" evidence the recovery keys off, so it could never fire
 * again. `commit()` runs in the importer's `finally` on EVERY run, including one that stores nothing.
 *
 * All writers now go through `TrackStore.keysForWrite()`.
 */
class Adv2GateCorruptKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 41 points at 25 m spacing — survives the production 20 m decimator intact. */
    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    /** A track exactly as `defaultDecimate` would produce it, so its sourceKey is the real one. */
    private fun decimated(id: String, epoch: Long, source: Source): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    private fun keysFile(dir: File) = File(dir, "sourcekeys.json")
    private fun keysAreParseable(dir: File): Boolean =
        runCatching { kotlinx.serialization.json.Json.decodeFromString<Set<String>>(keysFile(dir).readText()) }.isSuccess

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
    // C1. An ordinary import over a corrupt keys file REBUILDS it from the library instead of
    //     bulldozing it. Even an import that stores nothing runs commit() in its finally.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `an ordinary import rebuilds a corrupt keys file instead of erasing the RECORDED keys`() = runTest {
        val dir = tmp.newFolder("C1-tracks")
        val fits = tmp.newFolder("C1-fitfiles")
        val store = TrackStore(dir)

        val rec = decimated("rec-1", 1_700_000_000_000L, Source.RECORDED)
        store.add(rec)
        assertTrue("precondition: the live ride's key is known", rec.sourceKey in store.knownSourceKeys())

        // Corruption (a torn write, a bad block — the case the recovery was built for).
        keysFile(dir).writeText("{ this is not a json array")
        assertFalse("precondition: the keys file is corrupt", keysAreParseable(dir))

        // An import that finds NOTHING AT ALL still runs BulkSink.commit() in its finally.
        val p = runImport(dir, fits, tmp.newFolder("C1-import"), emptyMap())
        assertEquals(0, p.imported)

        assertTrue("the corrupt keys file was rewritten as valid JSON", keysAreParseable(dir))
        assertTrue(
            "the RECORDED ride's key was recovered off the library, not erased",
            rec.sourceKey in TrackStore(dir).knownSourceKeys(),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C2. THE TWO-GUARD COMBINATION, end to end. Guard 1 (pre-flight refusal) fires -> rebuildAll STILL
    //     runs the ordinary import -> that import must NOT twin the live-recorded rides. Each guard was
    //     correct on its own; together they used to lose the thing they were both protecting.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a REFUSED rebuild runs an import that still cannot twin a live-recorded ride`() = runTest {
        val dir = tmp.newFolder("C2-tracks")
        val fits = tmp.newFolder("C2-fitfiles")
        val imp = tmp.newFolder("C2-import")
        val store = TrackStore(dir)

        // Three rides recorded LIVE on the Karoo. Irreplaceable; each also has the Karoo's own .fit.
        val recorded = (1..3).map { decimated("rec-$it", 1_700_000_000_000L + it * 3_600_000L, Source.RECORDED) }
        recorded.forEach { store.add(it) }
        // The Karoo's .fit for each of those rides, in /sdcard/FitFiles. They decode onto the SAME
        // sourceKey (defaultDecimate recomputes it from epoch+decimated distance) — that key is the only
        // thing stopping them being stored a second time.
        val deviceFits = recorded.associate { r ->
            val f = File(fits, "${r.id}-device.fit").apply { writeText("x") }
            f.name to decimated("fit-${r.id}", r.startedAtEpoch, Source.FITFILES_SCAN)
        }
        // Six rides imported years ago from a phone backup the rider has since deleted: the exact
        // library guard 1 exists to refuse (3 files cannot restore 6 file-sourced tracks).
        val orphaned = (1..6).map { decimated("gpx-$it", 1_600_000_000_000L + it * 60_000L, Source.GPX_IMPORT) }
        orphaned.forEach { store.add(it) }

        // Corruption arrives (add() above rewrote the file, so corrupt it last).
        keysFile(dir).writeText("]]not json[[")

        assertNull(
            "guard 1 must refuse: 3 files cannot restore 6 file-sourced tracks",
            prepareRebuild(dir, fits, imp),
        )
        assertEquals(9, TrackStore(dir).allTracksMeta().size)
        // The refusal returns BEFORE resetImportDedup, so the corruption is still there for the import.
        assertFalse("still corrupt after the refusal", keysAreParseable(dir))

        runImport(dir, fits, imp, deviceFits)

        val meta = TrackStore(dir).allTracksMeta()
        assertEquals("no track was stored twice", 9, meta.size)
        recorded.forEach { r ->
            assertEquals(
                "ride ${r.id} must still be ALONE on its sourceKey — a twin pair is permanent " +
                    "(selectArchivable never breaks up a group of <= 3)",
                1, meta.count { it.sourceKey == r.sourceKey },
            )
        }
        // ...and the recovered keys were persisted, so the next run starts from a healthy file.
        assertTrue(keysAreParseable(dir))
        val keys = TrackStore(dir).knownSourceKeys()
        (recorded + orphaned).forEach { assertTrue("${it.id}'s key survived", it.sourceKey in keys) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C3. The rebuilt set must not be the "overwrite from a sampled snapshot" that dropSourceKeys' own
    //     doc argues against: a track whose <id>.json does not parse AT THAT MOMENT is absent from it,
    //     so writing it back erases that ride's key for good. Bulk-import track writes are fsync=false
    //     by design, so a torn <id>.json after a power cut is a modelled state. CHOICE: refuse to WRITE
    //     (fail closed, corrupt file left in place as evidence) rather than write a lossy set.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a recovery that cannot vouch for itself REFUSES instead of writing a lossy set`() {
        val dir = tmp.newFolder("C3-tracks")
        val store = TrackStore(dir)
        val ok = decimated("rec-ok", 1_700_000_000_000L, Source.RECORDED)
        val torn = decimated("rec-torn", 1_700_000_600_000L, Source.RECORDED)
        store.add(ok)
        store.add(torn)
        // One file-sourced track (with its file, below) so the rebuild gets PAST the file count and all
        // the way to the reset — the refusal under test.
        store.add(decimated("gpx-1", 1_600_000_000_000L, Source.GPX_IMPORT))
        val imp = tmp.newFolder("C3-imp").apply { File(this, "a.gpx").writeText("x") }

        keysFile(dir).writeText("nope")
        // Its json is unreadable at exactly this moment (torn tail, half-written rename, bad block).
        File(dir, "rec-torn.json").writeText("""{"id":"rec-torn","startedAtEpoch":17000006""")

        assertFalse("the reset must REFUSE, not report success", store.dropSourceKeys(emptySet()))
        assertFalse(
            "the corrupt file is left in place — the evidence the recovery keys off, and no key erased",
            keysAreParseable(dir),
        )
        // A refusing reset is what makes the whole rebuild refuse, so nothing is archived either.
        assertNull(prepareRebuild(dir, tmp.newFolder("C3-fits"), imp))
        assertTrue("nothing archived", !File(dir, "archive").isDirectory)

        // NOT a dead end: once the torn track is gone (re-imported, or swept), the recovery vouches
        // for itself again and the surviving ride's key comes back.
        File(dir, "rec-torn.json").delete()
        assertTrue(TrackStore(dir).dropSourceKeys(emptySet()))
        assertTrue("the parseable ride's key was recovered", ok.sourceKey in TrackStore(dir).knownSourceKeys())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C4. Control: corrupt keys, rebuild NOT refused — the recovery runs before the import, keeping the
    //     live ride's key and dropping the archived track's.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `control - a corrupt keys file on a PASSING rebuild is recovered before the import`() {
        val dir = tmp.newFolder("C4-tracks")
        val fits = tmp.newFolder("C4-fitfiles")
        val imp = tmp.newFolder("C4-import")
        val store = TrackStore(dir)
        val rec = decimated("rec-1", 1_700_000_000_000L, Source.RECORDED)
        store.add(rec)
        val orphan = decimated("gpx-1", 1_600_000_000_000L, Source.GPX_IMPORT)
        store.add(orphan)
        keysFile(dir).writeText("corrupt")
        File(imp, "a.gpx").writeText("x")
        File(imp, "b.gpx").writeText("x")

        assertEquals(1, prepareRebuild(dir, fits, imp))
        assertTrue("the live ride's key was recovered", rec.sourceKey in TrackStore(dir).knownSourceKeys())
        assertFalse("the archived track's key was dropped", orphan.sourceKey in TrackStore(dir).knownSourceKeys())
    }
}
