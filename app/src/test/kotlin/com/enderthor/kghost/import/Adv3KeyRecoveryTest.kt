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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REGRESSION LOCKS on the `keysForWrite()` recovery — the faults adversarial round 3 found in it, each
 * now asserting the FIXED behaviour:
 *
 *  K1. the rebuilt set is read off live **and** archived tracks, so an archived ride's sourceKey
 *      survives a corrupt `sourcekeys.json` (and is not resurrected by the next scan);
 *  K2. a torn `<id>.json` still refuses the rebuild, but with its OWN message and WITHOUT the ledger,
 *      so the follow-up import re-decodes, repairs the torn file and heals the keys file;
 *  K3. the shortfall report is honest again once K1 stops inflating `imported` with resurrections;
 *  K4. the healthy path still short-circuits before the library walk.
 */
class Adv3KeyRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 41 points at 25 m spacing — survives the production 20 m decimator intact. */
    private fun pts(n: Int = 41, lat0: Double = 41.0) = (0 until n).map { i ->
        TrackPointDto(lat0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source, lat0: Double = 41.0): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(lat0 = lat0), source = source))

    private fun keysFile(dir: File) = File(dir, "sourcekeys.json")

    /** The keys ON DISK. Deliberately not [TrackStore.knownSourceKeys]: that answers from the library
     *  when the file is corrupt, which is exactly what these tests must be able to see past. */
    private fun keysOnDisk(dir: File): Set<String>? =
        runCatching { kotlinx.serialization.json.Json.decodeFromString<Set<String>>(keysFile(dir).readText()) }
            .getOrNull()

    private fun keysAreParseable(dir: File): Boolean = keysOnDisk(dir) != null

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
    // K1. The rebuilt set is read off the WHOLE library, `archive/` included. archive() deliberately
    //     KEEPS an archived track's sourceKey ("a re-scan won't re-add it" — its own doc); a recovery
    //     blind to archive/ erased exactly those keys, on an ORDINARY RIDE END, and vouched for itself.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `the recovery keeps every ARCHIVED track's key`() {
        val dir = tmp.newFolder("K1-tracks")
        val store = TrackStore(dir)

        // A ride the auto-clean archived long ago (a twin group of >3 on a route ridden many times).
        val archivedRide = decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED)
        store.add(archivedRide)
        store.add(decimated("1700003600000", 1_700_003_600_000L, Source.RECORDED))
        store.archive(listOf(archivedRide.id))
        assertTrue(
            "precondition (archive()'s documented invariant): an archived track's key STAYS known",
            archivedRide.sourceKey in keysOnDisk(dir)!!,
        )

        keysFile(dir).writeText("{ not a json array")

        // An ordinary ride finishing. One add(), no import in sight.
        val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        assertTrue(store.add(today))

        assertTrue("the recovery rewrote the file as VALID", keysAreParseable(dir))
        val onDisk = keysOnDisk(dir)!!
        assertTrue("today's key is there", today.sourceKey in onDisk)
        assertTrue("the ARCHIVED ride's key survived the recovery", archivedRide.sourceKey in onDisk)
    }

    /** The consequence of K1: the archived ride's own file stays deduped, so a re-scan does NOT
     *  re-add it as a live track — which is what archive()'s doc says keeping the key prevents. */
    @Test fun `after the recovery a re-scan does NOT resurrect a ride the auto-clean had archived`() = runTest {
        val dir = tmp.newFolder("K1b-tracks")
        val fits = tmp.newFolder("K1b-fitfiles")
        val imp = tmp.newFolder("K1b-import")
        val store = TrackStore(dir)

        val archivedRide = decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED)
        store.add(archivedRide)
        store.add(decimated("1700003600000", 1_700_003_600_000L, Source.RECORDED))
        store.archive(listOf(archivedRide.id))
        keysFile(dir).writeText("corrupt")
        store.add(decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)) // fires the recovery

        val liveBefore = TrackStore(dir).allTracksMeta().size
        // The Karoo's own .fit for the archived ride, still in /sdcard/FitFiles. It decodes onto the
        // SAME sourceKey; the surviving key is what stops it being stored again.
        File(fits, "old.fit").writeText("x")
        val p = runImport(
            dir, fits, imp,
            mapOf("old.fit" to decimated("fit-1700000000000", archivedRide.startedAtEpoch, Source.FITFILES_SCAN)),
        )

        assertEquals("it was deduped, not stored", 0, p.imported)
        val meta = TrackStore(dir).allTracksMeta()
        assertEquals("the live library is unchanged", liveBefore, meta.size)
        assertFalse(
            "the archived ride did NOT come back as a live track",
            meta.any { it.sourceKey == archivedRide.sourceKey },
        )
        assertTrue("…it is still only in archive/", File(File(dir, "archive"), archivedRide.id + ".json").isFile)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // K2. A torn <id>.json (fsync=false bulk write + power cut — the modelled state) still makes the
    //     rebuild REFUSE (fail-closed: writing a set missing that ride's key would erase it). But the
    //     refusal is no longer a trap: it clears the ledger on the way out, so the ordinary import that
    //     follows re-decodes every source file, rewrites the torn track, and heals the keys file.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a torn track json refuses the rebuild with its OWN message and clears the dead end`() = runTest {
        val dir = tmp.newFolder("K2-tracks")
        val fits = tmp.newFolder("K2-fitfiles")
        val imp = tmp.newFolder("K2-import")
        val store = TrackStore(dir)

        val ok = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        val torn = decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0)
        store.add(ok)
        store.add(torn)

        val okFit = File(fits, "a.fit").apply { writeText("aaa") }
        val tornFit = File(fits, "b.fit").apply { writeText("bbb") }
        // Both files were decoded+stored on the import that preceded the power cut, so both are ledgered.
        val ledgerFile = File(dir, "processed.json")
        val ledger = ProcessedLedger(ledgerFile)
        val map = ledger.load()
        ledger.mark(map, okFit)
        ledger.mark(map, tornFit)
        ledger.save(map)

        // The power cut: sourcekeys.json corrupt AND one fsync=false track file torn.
        keysFile(dir).writeText("{{{")
        File(dir, "fit-1700003600000.json").writeText("""{"id":"fit-1700003600000","started""")

        var shortOfFiles: Pair<Int, Int>? = null
        var damaged = false
        assertNull(
            "the rebuild still refuses — nothing may be archived while a key cannot be vouched for",
            prepareRebuild(dir, fits, imp, { damaged = true }) { a, t -> shortOfFiles = a to t },
        )
        assertNull("it is NOT the file-count refusal — every file IS there", shortOfFiles)
        assertTrue("it reports the DAMAGED-file refusal, which has its own message", damaged)
        assertFalse("nothing was archived", File(dir, "archive").isDirectory)
        assertFalse("the corrupt keys file is left in place as evidence", keysAreParseable(dir))
        assertFalse("…and the ledger is gone, so the next import can re-decode", ledgerFile.exists())

        // rebuildAll runs the ordinary import after a refusal. With the ledger cleared it re-decodes
        // both files: `ok` is deduped, the torn track is re-stored — which REPAIRS its <id>.json.
        val p = runImport(dir, fits, imp, mapOf("a.fit" to ok, "b.fit" to torn))
        assertEquals("both files were re-decoded", 2, p.total)
        assertEquals("only the torn one needed storing", 1, p.imported)

        assertTrue("the keys file healed itself once every track parsed again", keysAreParseable(dir))
        val onDisk = keysOnDisk(dir)!!
        assertTrue("both keys are back", onDisk.containsAll(setOf(ok.sourceKey, torn.sourceKey)))
        assertNotNull("…and the next Rebuild RUNS. Not a dead end.", prepareRebuild(dir, fits, imp))
    }

    /** The residue: a torn track with NO source file (a RECORDED ride) cannot be re-decoded, so while
     *  it sits there the keys file is deliberately left corrupt and no key is persisted. Dedup still
     *  holds — the recovery re-derives it from the library on every pass — and the rider is told the
     *  file is damaged rather than told to restore ride files they already have. */
    @Test fun `while un-writable a finished ride's key is never persisted, and dedup still holds`() {
        val dir = tmp.newFolder("K2b-tracks")
        val store = TrackStore(dir)
        store.add(decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN))
        keysFile(dir).writeText("{{{")
        File(dir, "torn.json").writeText("""{"id":"torn",""")

        val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        assertTrue("the track itself is saved", store.add(today))
        assertFalse("the file is left corrupt (by design — the evidence the recovery keys off)", keysAreParseable(dir))
        assertTrue("the track file exists", File(dir, "1800000000000.json").isFile)
        assertFalse("a second add of the same ride is deduped from the library", store.add(today))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // K3. The shortfall report. rebuildShortfall compares archived vs imported. It used to be masked by
    //     K1's key loss: previously-archived rides came back and counted towards `imported`, paying for
    //     a genuinely stranded ride. With their keys surviving, they stay archived and the strand shows.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a real rebuild shortfall is reported, not paid for by resurrections`() = runTest {
        val dir = tmp.newFolder("K3-tracks")
        val fits = tmp.newFolder("K3-fitfiles")
        val imp = tmp.newFolder("K3-import")
        val store = TrackStore(dir)

        // Three rides archived by the auto-clean long ago; their .fit files are all still on the device.
        val oldArchived = (1..3).map { decimated("fit-${1_600_000_000_000L + it * 3_600_000L}", 1_600_000_000_000L + it * 3_600_000L, Source.FITFILES_SCAN, lat0 = 40.0 + it) }
        oldArchived.forEach { store.add(it) }
        store.archive(oldArchived.map { it.id })
        oldArchived.forEachIndexed { i, _ -> File(fits, "old-$i.fit").writeText("x") }

        // One live file-sourced track — the one THIS rebuild archives. Its file is present (so the
        // count guard passes) but no longer decodes to a ride (a hike the sport gate now refuses).
        val doomed = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN, lat0 = 45.0)
        store.add(doomed)
        File(fits, "doomed.fit").writeText("x")

        keysFile(dir).writeText("corrupt")

        val archived = prepareRebuild(dir, fits, imp)
        assertEquals("this run archived exactly the one live file-sourced track", 1, archived)

        // The re-import: `doomed.fit` no longer decodes (strand), the three old files do.
        val decodes = (0..2).associate { i -> "old-$i.fit" to oldArchived[i].copy(source = Source.FITFILES_SCAN) }
        val p = runImport(dir, fits, imp, decodes)

        assertEquals("the archived rides stayed archived — their keys survived the recovery", 0, p.imported)
        assertEquals("the stranded ride is reported", 1, rebuildShortfall(archived!!, p.imported))
        assertTrue(
            "…and it really is only in archive/",
            File(File(dir, "archive"), "fit-1700000000000.json").isFile,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // K4. COST CONTROL: the library walk must stay on the corrupt path only. A HEALTHY keys file must
    //     short-circuit before recoveredSourceKeys(), even with an unparseable <id>.json present (which
    //     would otherwise make the writer declare itself un-writable and drop the write).
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a healthy keys file never walks the library - an unparseable track cannot block the write`() {
        val dir = tmp.newFolder("K4-tracks")
        val store = TrackStore(dir)
        store.add(decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED))
        File(dir, "torn.json").writeText("""{"id":"torn",""")

        val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        assertTrue(store.add(today))
        assertTrue(
            "the healthy read short-circuits, so the torn file is irrelevant",
            today.sourceKey in keysOnDisk(dir)!!,
        )
    }

    /** Rough cost of the corrupt path relative to the healthy one, on THIS machine. Printed, not
     *  asserted (a Karoo is far slower); the shape — O(live + archived) per write vs O(1) — is the
     *  point, and archive/ is now part of that walk. */
    @Test fun `cost of one recovery pass versus one healthy write`() {
        val dir = tmp.newFolder("K4b-tracks")
        val store = TrackStore(dir)
        val n = 300
        val archivedN = 100
        fun bulky(id: String, i: Int) = RecordedTrack(
            id, 1_600_000_000_000L + i * 3_600_000L,
            (0 until 400).map { j -> TrackPointDto(41.0 + j * 0.0002, 2.0 + i * 0.01, j * 25.0, j * 5.0) },
            sourceKey = "k-$i",
        )
        repeat(n) { i ->
            File(dir, "t-$i.json").writeText(
                com.enderthor.kghost.extension.jsonForStorage.encodeToString(
                    com.enderthor.kghost.geo.RecordedTrack.serializer(), bulky("t-$i", i),
                ),
            )
        }
        val archiveDir = File(dir, "archive").apply { mkdirs() }
        repeat(archivedN) { i ->
            File(archiveDir, "a-$i.json").writeText(
                com.enderthor.kghost.extension.jsonForStorage.encodeToString(
                    com.enderthor.kghost.geo.RecordedTrack.serializer(), bulky("a-$i", 10_000 + i),
                ),
            )
        }
        val bytes = (dir.listFiles()!! + archiveDir.listFiles()!!).filter { it.isFile }.sumOf { it.length() }

        // Warm-up add: pays the one-time legacy bbox→path-cell index rebuild (itself a whole-library
        // parse), so it doesn't get charged to the healthy measurement below.
        File(dir, "sourcekeys.json").writeText("[]")
        store.add(decimated("warm", 1_899_000_000_000L, Source.RECORDED))

        val healthyTrack = decimated("h", 1_900_000_000_000L, Source.RECORDED)
        val healthy = kotlin.system.measureTimeMillis { store.add(healthyTrack) }

        File(dir, "sourcekeys.json").writeText("{{{")
        val corruptTrack = decimated("c", 1_900_003_600_000L, Source.RECORDED)
        val corrupt = kotlin.system.measureTimeMillis { store.add(corruptTrack) }

        println(
            "Adv3 cost: $n live + $archivedN archived tracks / ${bytes / 1024} KiB — " +
                "healthy add() $healthy ms, corrupt add() $corrupt ms",
        )
        assertTrue("the archived keys are in the recovered set", "k-10000" in keysOnDisk(dir)!!)
        assertTrue(corruptTrack.sourceKey in keysOnDisk(dir)!!)
    }
}
