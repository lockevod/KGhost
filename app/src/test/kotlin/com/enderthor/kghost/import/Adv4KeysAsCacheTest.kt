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
 * `sourcekeys.json` IS A CACHE — the regression locks for the states adversarial round 4 found the
 * three previous "corruption repair" fixes stuck in.
 *
 * Every key in that file is the `sourceKey` of a stored track, so it is always recomputable: ABSENT and
 * CORRUPT mean the same thing (recompute from the library, live + `archive/`), and completeness gates
 * PERSISTENCE, never FUNCTION — an enumeration that could not vouch for itself is still deduped
 * against, it is just not written back, so it cannot erase anything.
 *
 * The six states these lock: corrupt keys file, absent keys file, torn LIVE track json, torn ARCHIVED
 * track json, unreadable `archive/`, stray non-track json in `archive/`. None erases a key, freezes the
 * store, or refuses the rebuild permanently.
 */
class Adv4KeysAsCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts(n: Int = 41, lat0: Double = 41.0) = (0 until n).map { i ->
        TrackPointDto(lat0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source, lat0: Double = 41.0): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(lat0 = lat0), source = source))

    private fun keysFile(dir: File) = File(dir, "sourcekeys.json")

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

    // ═════════════════════════════════════════════════════════════════════════
    // A1. TORN ARCHIVED TRACK JSON — the state that used to be a PERMANENT dead end. The refusal was
    //     escapable only by re-decoding a source file, and a re-decode writes into the LIVE dir, so
    //     nothing could ever rewrite `archive/<id>.json`: keys never written, Rebuild refused forever,
    //     a full live+archive parse under indexLock on every ride end. An undecodable file is simply
    //     not a track now, so there is nothing to be stuck on.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `a torn ARCHIVED track json is no longer a dead end - the rebuild runs on the first press`() = runTest {
        val dir = tmp.newFolder("A1-tracks")
        val fits = tmp.newFolder("A1-fitfiles")
        val imp = tmp.newFolder("A1-import")
        val store = TrackStore(dir)

        val archived = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        val live = decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0)
        store.add(archived)
        store.add(live)
        store.archive(listOf(archived.id))
        File(fits, "a.fit").writeText("aaa")
        File(fits, "b.fit").writeText("bbb")

        // The power cut: keys file corrupt, and the torn fsync=false track file is the ARCHIVED one.
        keysFile(dir).writeText("{{{")
        File(File(dir, "archive"), "fit-1700000000000.json").writeText("""{"id":"fit-1700000000000","star""")

        assertEquals("press 1 is a real rebuild, not a refusal", 1, prepareRebuild(dir, fits, imp))
        assertTrue("the cache was written — the store is not frozen", keysAreParseable(dir))

        runImport(dir, fits, imp, mapOf("a.fit" to archived, "b.fit" to live))
        assertTrue("the library is healthy again", keysAreParseable(dir))
        assertNotNull("and so is every later press", prepareRebuild(dir, fits, imp))
    }

    /**
     * A1b, the blast radius of that state, bounded: only the ride whose archived file CANNOT BE READ
     * loses its dedup protection (its key exists nowhere readable), and re-importing its source file is
     * how that ride comes back at all. Every other archived ride stays archived — the resurrection storm
     * fix 3 was rejected for does not happen.
     */
    @Test fun `only the ride whose archived file is unreadable comes back - the rest stay archived`() = runTest {
        val dir = tmp.newFolder("A1b-tracks")
        val fits = tmp.newFolder("A1b-fitfiles")
        val imp = tmp.newFolder("A1b-import")
        val store = TrackStore(dir)

        val tornArchived = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        val okArchived = decimated("fit-1700007200000", 1_700_007_200_000L, Source.FITFILES_SCAN, lat0 = 43.0)
        val live = decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0)
        listOf(tornArchived, okArchived, live).forEach { store.add(it) }
        store.archive(listOf(tornArchived.id, okArchived.id))
        File(fits, "a.fit").writeText("aaa")
        File(fits, "c.fit").writeText("ccc")
        keysFile(dir).writeText("{{{")
        File(File(dir, "archive"), "fit-1700000000000.json").writeText("""{"id":"fit-170""")

        // An ORDINARY import (no rebuild): the recompute is what dedups.
        val p = runImport(dir, fits, imp, mapOf("a.fit" to tornArchived, "c.fit" to okArchived))

        assertEquals("exactly one ride came back — the unreadable one", 1, p.imported)
        assertTrue(File(dir, "fit-1700000000000.json").isFile)
        assertFalse(
            "the archived ride whose file READS stayed archived — its key was recomputed",
            File(dir, "fit-1700007200000.json").isFile,
        )
        assertTrue("…and its key is on disk", okArchived.sourceKey in keysOnDisk(dir)!!)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A2. UNREADABLE archive/ DIRECTORY. `listFiles()` returns null both for "no such directory"
    //     (nothing hidden) and for "cannot be read" (a WHOLE LEG of the enumeration missing). Vouching
    //     for the second as an empty directory wrote back a set with every archived key erased — the
    //     precise bug fix 2 was rejected for. Only the second blocks the write.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `an UNREADABLE archive dir blocks the write instead of erasing every archived key`() {
        val dir = tmp.newFolder("A2-tracks")
        val store = TrackStore(dir)
        val archived = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        store.add(archived)
        store.add(decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0))
        store.archive(listOf(archived.id))
        assertTrue("precondition", archived.sourceKey in keysOnDisk(dir)!!)

        keysFile(dir).writeText("{{{")
        val archiveDir = File(dir, "archive")
        org.junit.Assume.assumeTrue(
            "needs a filesystem where the owner can drop +r on a directory",
            archiveDir.setReadable(false, false) && archiveDir.listFiles() == null,
        )
        try {
            val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
            store.add(today) // an ordinary ride end — the recompute runs here

            assertFalse("the set could not be vouched for, so nothing was written", keysAreParseable(dir))
            assertFalse("…and no archived key was erased", store.add(today)) // dedup still holds
        } finally {
            archiveDir.setReadable(true, false)
        }
        assertTrue(
            "once archive/ lists again the archived key is still there — nothing was ever lost",
            archived.sourceKey in TrackStore(dir).knownSourceKeys(),
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A3. THE TORN RECORDED RIDE — the other half of the old dead end ("tap Rebuild again", which could
    //     only ever refuse again, because no source file can rewrite a RECORDED ride's `<id>.json`).
    //     Its key living only in an unreadable file is now what lets the Karoo's own .fit RESTORE the
    //     ride instead of being deduped against a track nothing can read.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `a torn RECORDED ride is restored from its own FIT instead of refusing forever`() = runTest {
        val dir = tmp.newFolder("A3-tracks")
        val fits = tmp.newFolder("A3-fitfiles")
        val imp = tmp.newFolder("A3-import")
        val store = TrackStore(dir)

        val recorded = decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED)
        val fileTrack = decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0)
        store.add(recorded)
        store.add(fileTrack)
        File(fits, "recorded.fit").writeText("rrr") // the Karoo's own .fit for the recorded ride
        File(fits, "b.fit").writeText("bbb")

        keysFile(dir).writeText("{{{")
        File(dir, "1700000000000.json").writeText("""{"id":"1700000000000","start""")

        assertEquals("the rebuild RUNS", 1, prepareRebuild(dir, fits, imp))

        val fitTwin = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        assertEquals("precondition: same ride, same key", recorded.sourceKey, fitTwin.sourceKey)
        runImport(dir, fits, imp, mapOf("recorded.fit" to fitTwin, "b.fit" to fileTrack))

        assertEquals(
            "the ride exists exactly ONCE among readable tracks — restored, not twinned (the torn file " +
                "is still counted by allTrackIds(), which lists by NAME, but nothing can read it)",
            1, TrackStore(dir).allTracksMeta().count { it.sourceKey == recorded.sourceKey },
        )
        assertTrue("the cache is healthy", keysAreParseable(dir))
        assertNotNull("and Rebuild is not retired", prepareRebuild(dir, fits, imp))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A6. COST OF BOTH PATHS, with archive/ in the walk. On a library of heavily-repeated routes
    //     archive/ is bigger than the live set (that is the auto-clean's whole point). The walk runs
    //     under indexLock — the SAME monitor loadCandidates() takes — which is why it must stay on the
    //     cache-miss path only, and why the miss must be able to HEAL (A1/A3) instead of repeating.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `cost of the recompute when archive is larger than the live library`() {
        val dir = tmp.newFolder("A6-tracks")
        val store = TrackStore(dir)
        fun bulky(id: String, i: Int) = RecordedTrack(
            id, 1_600_000_000_000L + i * 3_600_000L,
            (0 until 400).map { j -> TrackPointDto(41.0 + j * 0.0002, 2.0 + i * 0.01, j * 25.0, j * 5.0) },
            sourceKey = "k-$i",
        )
        val liveN = 200
        val archivedN = 600
        repeat(liveN) { i ->
            File(dir, "t-$i.json").writeText(
                com.enderthor.kghost.extension.jsonForStorage.encodeToString(RecordedTrack.serializer(), bulky("t-$i", i)),
            )
        }
        val archiveDir = File(dir, "archive").apply { mkdirs() }
        repeat(archivedN) { i ->
            File(archiveDir, "a-$i.json").writeText(
                com.enderthor.kghost.extension.jsonForStorage.encodeToString(RecordedTrack.serializer(), bulky("a-$i", 10_000 + i)),
            )
        }
        val liveBytes = dir.listFiles()!!.filter { it.isFile }.sumOf { it.length() }
        val archiveBytes = archiveDir.listFiles()!!.sumOf { it.length() }

        keysFile(dir).writeText("[]")
        store.add(decimated("warm", 1_899_000_000_000L, Source.RECORDED)) // pay the one-time index rebuild

        val healthy = kotlin.system.measureTimeMillis {
            store.add(decimated("h", 1_900_000_000_000L, Source.RECORDED))
        }
        keysFile(dir).writeText("{{{")
        val miss = kotlin.system.measureTimeMillis {
            store.add(decimated("c", 1_900_003_600_000L, Source.RECORDED))
        }
        // The next ride end after the miss: the cache healed, so it is back on the healthy path.
        val afterHeal = kotlin.system.measureTimeMillis {
            store.add(decimated("c2", 1_900_007_200_000L, Source.RECORDED))
        }
        println(
            "Adv4 cost: $liveN live (${liveBytes / 1024} KiB) + $archivedN archived " +
                "(${archiveBytes / 1024} KiB) — healthy add() $healthy ms, cache-miss add() $miss ms, " +
                "next add() after the heal $afterHeal ms",
        )
        assertTrue("the archived keys are in the recomputed set", "k-10000" in keysOnDisk(dir)!!)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A7. The healthy path must never walk the library — re-checked with a torn file in archive/, which
    //     is exactly what the walk would trip over.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `a healthy keys file short-circuits before the archive walk`() {
        val dir = tmp.newFolder("A7-tracks")
        val store = TrackStore(dir)
        store.add(decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED))
        File(dir, "archive").mkdirs()
        File(File(dir, "archive"), "torn.json").writeText("""{"id":"to""")

        val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        assertTrue(store.add(today))
        assertTrue("the write went through — archive/ was never read", today.sourceKey in keysOnDisk(dir)!!)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A8. archive/ in odd shapes: a FILE named `archive`, and a stray non-track .json inside archive/
    //     (a manual backup, a leftover). The stray used to read as a TORN TRACK and pin every key write
    //     forever — the live leg filtered bookkeeping names, the archive leg did not.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `neither a FILE named archive nor a stray json inside it can block a key write`() {
        val dirA = tmp.newFolder("A8a-tracks")
        val storeA = TrackStore(dirA)
        storeA.add(decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED))
        keysFile(dirA).writeText("{{{")
        File(dirA, "archive").writeText("not a directory")
        val todayA = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        storeA.add(todayA)
        assertTrue("a FILE named archive/ hides nothing → the write proceeds", keysAreParseable(dirA))
        assertTrue(todayA.sourceKey in keysOnDisk(dirA)!!)

        val dirB = tmp.newFolder("A8b-tracks")
        val storeB = TrackStore(dirB)
        val old = decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED)
        storeB.add(old)
        keysFile(dirB).writeText("{{{")
        File(dirB, "archive").mkdirs()
        File(File(dirB, "archive"), "sourcekeys.json").writeText("""["stray"]""") // filtered by name
        File(File(dirB, "archive"), "notes.json").writeText("""{"note":"hi"}""")  // decodes to no track
        val todayB = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED)
        storeB.add(todayB)
        val onDisk = keysOnDisk(dirB)
        assertNotNull("a stray json in archive/ no longer pins the store un-writable", onDisk)
        assertTrue("and nothing was lost", onDisk!!.containsAll(setOf(old.sourceKey, todayB.sourceKey)))
        assertFalse("the stray's contents are not keys", "stray" in onDisk)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A9. THE HOLE THE THREE FIXES NEVER CLOSED: an ABSENT sourcekeys.json over a NON-EMPTY library was
    //     read as a cold start, so the very next import stored the Karoo's own .fit for every live ride
    //     as a PERMANENT twin (selectArchivable never breaks up a group of <= 3). Absent and corrupt are
    //     the same cache miss now.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `an ABSENT keys file over a full library recomputes instead of twinning every ride`() = runTest {
        val dir = tmp.newFolder("A9-tracks")
        val fits = tmp.newFolder("A9-fitfiles")
        val imp = tmp.newFolder("A9-import")
        val store = TrackStore(dir)

        val rideA = decimated("1700000000000", 1_700_000_000_000L, Source.RECORDED)
        val rideB = decimated("1700003600000", 1_700_003_600_000L, Source.RECORDED, lat0 = 42.0)
        store.add(rideA)
        store.add(rideB)
        File(fits, "a.fit").writeText("aaa")
        File(fits, "b.fit").writeText("bbb")

        keysFile(dir).delete() // not corrupt — GONE.

        assertEquals(
            "a full library is not a cold start",
            setOf(rideA.sourceKey, rideB.sourceKey), TrackStore(dir).knownSourceKeys(),
        )

        // The Karoo's own .fit for each ride decodes onto the SAME sourceKey the recorder wrote.
        val p = runImport(
            dir, fits, imp,
            mapOf(
                "a.fit" to decimated("fit-1700000000000", rideA.startedAtEpoch, Source.FITFILES_SCAN),
                "b.fit" to decimated("fit-1700003600000", rideB.startedAtEpoch, Source.FITFILES_SCAN, lat0 = 42.0),
            ),
        )
        assertEquals("both were deduped, not twinned", 0, p.imported)
        assertEquals(2, TrackStore(dir).allTrackIds().size)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A10. HOW A TORN FILE GETS INTO archive/ IN THE FIRST PLACE — i.e. why A1 is reachable and not just
    //      modelled. TrackStorage.migrateIfNeeded copies internal `archive/*.json` into external
    //      `archive/` with File.copyTo — a plain stream copy, NOT atomicWriteText — and the
    //      `if (!dest.exists())` guard means a power cut mid-copy leaves a truncated file that is never
    //      re-copied. STILL TRUE (and left alone), but no longer able to freeze the store.
    // ═════════════════════════════════════════════════════════════════════════
    @Test fun `a migration truncated by a power cut is never re-copied, and is now harmless`() {
        val internal = tmp.newFolder("A10-internal")
        val external = tmp.newFolder("A10-external")
        val store = TrackStore(internal)
        val archived = decimated("fit-1700000000000", 1_700_000_000_000L, Source.FITFILES_SCAN)
        val live = decimated("fit-1700003600000", 1_700_003_600_000L, Source.FITFILES_SCAN, lat0 = 42.0)
        store.add(archived)
        store.add(live)
        store.archive(listOf(archived.id))

        // The power cut: the copy of this one file had written only its first bytes.
        val extArchive = File(external, "archive").apply { mkdirs() }
        val whole = File(File(internal, "archive"), "fit-1700000000000.json").readText()
        File(extArchive, "fit-1700000000000.json").writeText(whole.take(20))
        // External already has a track file, so the split-brain heal regime runs.
        File(external, "seed.json").writeText(File(internal, "fit-1700003600000.json").readText())

        com.enderthor.kghost.geo.TrackStorage.migrateIfNeeded(internal, external)
        com.enderthor.kghost.geo.TrackStorage.migrateIfNeeded(internal, external) // every dir resolution

        assertEquals(
            "the truncated copy is never retried — dest.exists() is the only guard",
            whole.take(20), File(extArchive, "fit-1700000000000.json").readText(),
        )

        keysFile(external).writeText("{{{")
        val today = decimated("1800000000000", 1_800_000_000_000L, Source.RECORDED, lat0 = 44.0)
        TrackStore(external).add(today)
        val onDisk = keysOnDisk(external)
        assertNotNull("an ordinary ride end still persists its keys", onDisk)
        assertTrue("including every readable track's", onDisk!!.containsAll(setOf(live.sourceKey, today.sourceKey)))
    }
}
