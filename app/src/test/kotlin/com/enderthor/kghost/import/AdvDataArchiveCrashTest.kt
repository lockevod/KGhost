package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ADVERSARIAL: the crash / partial-failure / interleaving surface around [TrackStore.archive] and
 * the rebuild's non-suspending destructive phase. These are the attacks that did NOT land — each
 * one is carried through to the on-disk state it actually produces.
 */
class AdvDataArchiveCrashTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source) =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    private fun liveIds(dir: File) = TrackStore(dir).allTracksMeta().map { it.id }.toSet()
    private fun indexedIds(dir: File): Set<String> {
        val f = File(dir, "index.json")
        if (!f.isFile) return emptySet()
        return Regex("\"([a-z]+-[0-9]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C1. archive() writes the cleaned index BEFORE it knows the archive dir is usable.
    // Occupy `archive` with a regular file so mkdirs() can never succeed.
    // Outcome: index emptied, ZERO files moved — and prepareRebuild still reports success.
    // This is FAIL-SAFE for the data (nothing left the active dir) but the tracks are invisible to
    // matching until the extension's next startup reconcile. Proven healed below.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `an unusable archive dir empties the index but loses no file — reconcile heals it`() {
        val dir = tmp.newFolder("C1-tracks")
        val store = TrackStore(dir)
        (1..5).forEach { store.add(decimated("fit-$it", 1_700_000_000_000L + it * 60_000L, Source.FITFILES_SCAN)) }
        store.add(decimated("rec-9", 1_800_000_000_000L, Source.RECORDED))
        File(dir, "archive").writeText("not a directory")

        assertEquals(6, indexedIds(dir).size)
        val moved = store.archive((1..5).map { "fit-$it" })

        assertEquals("no file could be moved", 0, moved)
        assertTrue("every track file is still live", (1..5).all { File(dir, "fit-$it.json").isFile })
        assertEquals("...but the index was already cleaned of them", setOf("rec-9"), indexedIds(dir))

        // Next extension startup.
        assertEquals(5, TrackStore(dir).prewarmAndReconcile())
        assertEquals(6, indexedIds(dir).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C2. A corrupt index.json turns archive() into an index WIPE: readSnapshot() maps corruption to
    // the empty map, so the "cleaned" snapshot it writes back is empty — dropping every RECORDED
    // ride out of matching, not just the archived ones. Recoverable at the next startup reconcile.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `archiving over a corrupt index wipes the RECORDED rides out of the index too`() {
        val dir = tmp.newFolder("C2-tracks")
        val store = TrackStore(dir)
        (1..3).forEach { store.add(decimated("rec-$it", 1_800_000_000_000L + it * 60_000L, Source.RECORDED)) }
        store.add(decimated("fit-1", 1_700_000_000_000L, Source.FITFILES_SCAN))
        File(dir, "index.json").writeText("""{"cell": ["a"""")

        store.archive(listOf("fit-1"))

        assertEquals("the whole index is gone, RECORDED rides included", emptySet<String>(), indexedIds(dir))
        assertTrue("...the files are all still there", (1..3).all { File(dir, "rec-$it.json").isFile })
        assertEquals(3, TrackStore(dir).prewarmAndReconcile())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C3. Process kill halfway through archive()'s file moves, then the rider runs a plain
    // "Import all" instead of retrying the rebuild. Both dedup gates are already open (the keys were
    // dropped and the ledger deleted before any file moved), so every source file re-decodes and
    // re-stores. It does NOT duplicate: decoder ids are deterministic (`fit-<firstEpochMs>`), so a
    // re-import of a still-live track overwrites its own file.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a kill mid-archive followed by a plain import produces no duplicates`() = runTest {
        val dir = tmp.newFolder("C3-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("C3-FitFiles")
        val decode = HashMap<String, RecordedTrack>()
        val epochs = (1..6).map { 1_700_000_000_000L + it * 60_000L }
        epochs.forEachIndexed { i, e ->
            store.add(decimated("fit-$e", e, Source.FITFILES_SCAN))
            File(fitFiles, "r$i.fit").writeText("x")
            decode["r$i.fit"] = RecordedTrack("fit-$e", e, pts(), source = Source.FITFILES_SCAN)
        }
        // prepareRebuild's order: keys dropped + ledger deleted FIRST, then the moves.
        resetImportDedup(dir, store, epochs.map { sourceKeyOf(it, 1000.0) }.toSet())
        // ...killed after 3 of 6 moves.
        store.archive(epochs.take(3).map { "fit-$it" })

        HistoryImporter(
            fitFilesDir = fitFiles, importDir = tmp.newFolder("C3-KGhost"), trackStore = TrackStore(dir),
            fitDecode = { f, s -> decode[f.name]?.copy(source = s) },
            processedLedgerFile = File(dir, "processed.json"),
        ).import(onlyNew = false).toList()

        assertEquals("all six back, none twinned", 6, liveIds(dir).size)
        assertEquals(epochs.map { "fit-$it" }.toSet(), liveIds(dir))
        assertEquals("the 3 archived copies are stale leftovers, not extra rides",
            3, (File(dir, "archive").listFiles() ?: emptyArray()).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C4. A ride finishing between prepareRebuild's meta snapshot and its dedup reset. The reset is
    // SUBTRACTIVE and re-reads under the lock, so the new ride's key survives and its .fit will
    // still be deduped on the next scan. (The window is real — the snapshot is taken first — but
    // the subtractive rewrite closes it.)
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a ride finishing mid-prepare keeps its dedup key`() {
        val dir = tmp.newFolder("C4-tracks")
        val store = TrackStore(dir)
        store.add(decimated("fit-1", 1_700_000_000_000L, Source.FITFILES_SCAN))
        val snapshotKeys = archivedSourceKeys(store.allTracksMeta())   // what the rebuild sampled

        val live = decimated("rec-1", 1_800_000_000_000L, Source.RECORDED)  // ride finishes NOW
        store.add(live)

        assertTrue(store.dropSourceKeys(snapshotKeys))
        assertEquals("only the archived track's key went", setOf(live.sourceKey), store.knownSourceKeys())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C5. Two rebuilds. The second archives the freshly re-imported copies onto the first run's
    // archive files (renameTo overwrites), so archive/ holds the NEWER copy — never a lost ride and
    // never a duplicated one. Rides stranded by run 1 are no longer in allTracksMeta, so run 2 can
    // neither re-archive nor clobber them.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a second rebuild neither duplicates nor clobbers what the first stranded`() = runTest {
        val dir = tmp.newFolder("C5-tracks")
        val store = TrackStore(dir)
        val fitFiles = tmp.newFolder("C5-FitFiles")
        val importDir = tmp.newFolder("C5-KGhost")
        val decode = HashMap<String, RecordedTrack>()
        val epochs = (1..4).map { 1_700_000_000_000L + it * 60_000L }
        epochs.forEachIndexed { i, e ->
            store.add(decimated("fit-$e", e, Source.FITFILES_SCAN))
            // Every ride still HAS its file, so prepareRebuild's one-file-per-track floor passes — but
            // only the first two decode; the other two are truncated and strand. (Pre-flight counting
            // cannot foresee this: it is exactly the residue rebuildShortfall reports afterwards.)
            File(fitFiles, "r$i.fit").writeText("x")
            if (i < 2) decode["r$i.fit"] = RecordedTrack("fit-$e", e, pts(), source = Source.FITFILES_SCAN)
        }
        suspend fun rebuild() {
            prepareRebuild(dir, fitFiles, importDir)
            HistoryImporter(
                fitFilesDir = fitFiles, importDir = importDir, trackStore = TrackStore(dir),
                fitDecode = { f, s -> decode[f.name]?.copy(source = s) },
                processedLedgerFile = File(dir, "processed.json"),
            ).import(onlyNew = false).toList()
        }

        rebuild()
        assertEquals(setOf("fit-${epochs[0]}", "fit-${epochs[1]}"), liveIds(dir))
        val strandedBefore = File(dir, "archive/fit-${epochs[3]}.json").readText()

        rebuild()
        assertEquals("no growth, no twins", setOf("fit-${epochs[0]}", "fit-${epochs[1]}"), liveIds(dir))
        assertEquals("the stranded ride's only copy is untouched",
            strandedBefore, File(dir, "archive/fit-${epochs[3]}.json").readText())
        assertEquals(4, (File(dir, "archive").listFiles() ?: emptyArray()).size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C6. A RECORDED track is never archived, whatever else is true — including when its json
    // predates the `source` field entirely (the serializer defaults it to RECORDED, fail-safe).
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a legacy track file with no source field is treated as RECORDED and never archived`() {
        val dir = tmp.newFolder("C6-tracks")
        val store = TrackStore(dir)
        store.add(decimated("fit-1", 1_700_000_000_000L, Source.FITFILES_SCAN))
        // A pre-Source-field track json, exactly as an old build would have written it.
        File(dir, "legacy-1.json").writeText(
            """{"id":"legacy-1","startedAtEpoch":1,"points":[{"lat":41.0,"lng":2.0,"distanceM":0.0,"timeS":0.0},""" +
                """{"lat":41.1,"lng":2.0,"distanceM":100.0,"timeS":10.0}]}"""
        )
        val meta = store.allTracksMeta()
        assertEquals(Source.RECORDED, meta.first { it.id == "legacy-1" }.source)
        assertFalse("legacy-1" in fileSourcedIds(meta))
        assertNotEquals(0, fileSourcedIds(meta).size)
    }
}
