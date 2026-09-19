package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackIdentity
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rebuild's data-safety rules. The DESTRUCTIVE half lives in [prepareRebuild] — a plain function
 * over (tracksDir, fitFilesDir, importDir), no `Context` and no Robolectric — so the case that can
 * destroy irreplaceable history (archiving a library the source files can no longer re-import) is
 * asserted end to end on a real temp library, not just on the predicates around it.
 */
class RebuildHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts() = (0..40).map { i -> TrackPointDto(41.0, 2.0 + i * 0.0003, i * 25.0, 600.0 * (i / 40.0)) }

    private fun track(id: String, source: Source, key: String) =
        RecordedTrack(id, 1_000L + id.hashCode(), pts(), sourceKey = key, source = source)

    /** A tracks dir holding [tracks], with the dedup gates both populated as a real library's would be. */
    private fun library(vararg tracks: RecordedTrack): File {
        val dir = tmp.newFolder("tracks-" + tracks.joinToString("-") { it.id })
        val store = TrackStore(dir)
        tracks.forEach { store.add(it) }
        File(dir, "processed.json").writeText("""{"entries":{}}""")
        return dir
    }

    /** A source dir holding [names] (contents irrelevant — only the scan predicate sees them). */
    private fun sources(label: String, vararg names: String): File {
        val dir = tmp.newFolder("src-$label")
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun isLive(dir: File, id: String) = File(dir, "$id.json").isFile
    private fun isArchived(dir: File, id: String) = File(dir, "archive/$id.json").isFile

    // ── C1: the archive is contingent on the files that would re-import it ────

    @Test fun `a rebuild with an EMPTY source directory leaves the library intact`() {
        val dir = library(
            track("scan1", Source.FITFILES_SCAN, "k:1"),
            track("scan2", Source.FITFILES_SCAN, "k:2"),
            track("live", Source.RECORDED, "k:3"),
        )
        val empty = sources("empty")

        val archived = prepareRebuild(dir, fitFilesDir = empty, importDir = sources("empty2"))

        assertNull("nothing may be archived when no source file can re-import it — a REFUSAL, not a no-op", archived)
        assertTrue(isLive(dir, "scan1"))
        assertTrue(isLive(dir, "scan2"))
        assertTrue(isLive(dir, "live"))
        assertFalse(isArchived(dir, "scan1"))
        assertEquals(setOf("k:1", "k:2", "k:3"), TrackStore(dir).knownSourceKeys())
        assertTrue("the ledger must survive a refusal too", File(dir, "processed.json").exists())
    }

    @Test fun `a rebuild with a MISSING source directory leaves the library intact`() {
        val dir = library(track("scan1", Source.FITFILES_SCAN, "k:1"))
        val gone = File(tmp.root, "never-mounted") // listFiles() -> null, the silent "total = 0" case

        assertNull(prepareRebuild(dir, fitFilesDir = gone, importDir = gone))
        assertTrue(isLive(dir, "scan1"))
        assertEquals(setOf("k:1"), TrackStore(dir).knownSourceKeys())
    }

    @Test fun `a source folder that lost even ONE of its files is refused`() {
        val dir = library(
            track("a", Source.FIT_IMPORT, "k:a"),
            track("b", Source.FIT_IMPORT, "k:b"),
            track("c", Source.FIT_IMPORT, "k:c"),
        )
        // 2 files left for 3 tracks. The OLD rule (available * 2 < ids.size) let this through — it
        // tolerated stranding half the library by construction. One file per archived track is the
        // floor: in a healthy library the count is INFLATED relative to the tracks (see prepareRebuild),
        // so a count that has fallen below them means at least one ride has nothing to come back from.
        assertNull(prepareRebuild(dir, sources("two", "a.fit", "b.fit"), sources("none")))
        assertTrue(isLive(dir, "a"))
        assertTrue(isLive(dir, "b"))
        assertTrue(isLive(dir, "c"))
        assertTrue("a refusal never touches the ledger", File(dir, "processed.json").exists())
        assertEquals(setOf("k:a", "k:b", "k:c"), TrackStore(dir).knownSourceKeys())
    }

    @Test fun `exactly one source file per archived track is enough`() {
        val dir = library(
            track("a", Source.FIT_IMPORT, "k:a"),
            track("b", Source.FIT_IMPORT, "k:b"),
            track("live", Source.RECORDED, "k:live"), // never archived, so never counted against
        )
        assertEquals(2, prepareRebuild(dir, sources("two-ok", "a.fit", "b.fit"), sources("none-ok")))
        assertTrue(isArchived(dir, "a"))
        assertTrue(isArchived(dir, "b"))
        assertTrue(isLive(dir, "live"))
    }

    @Test fun `a rebuild with the source files present archives and drops their keys`() {
        val dir = library(
            track("scan1", Source.FITFILES_SCAN, "k:1"),
            track("drop1", Source.GPX_IMPORT, "k:2"),
            track("live", Source.RECORDED, "k:3"),
        )

        val archived = prepareRebuild(
            dir,
            fitFilesDir = sources("fit", "ride1.fit"),
            importDir = sources("import", "ride2.gpx"),
        )

        assertEquals(2, archived)
        assertTrue(isArchived(dir, "scan1"))
        assertTrue(isArchived(dir, "drop1"))
        assertTrue("a live-recorded ride is never archived", isLive(dir, "live"))
        // Only the archived tracks' keys are dropped, so their files re-decode; the live ride's key
        // stays so its own FIT still collapses onto it instead of landing a twin.
        assertEquals(setOf("k:3"), TrackStore(dir).knownSourceKeys())
        assertFalse(File(dir, "processed.json").exists())
    }

    @Test fun `a library with nothing file-sourced archives nothing and reports it`() {
        val dir = library(track("live", Source.RECORDED, "k:3"))
        // 0, NOT null: nothing to do is not a refusal, so the screen must stay quiet about it.
        assertEquals(0, prepareRebuild(dir, sources("fit", "ride1.fit"), sources("import")))
        assertTrue(isLive(dir, "live"))
        assertTrue("an untouched library keeps its ledger", File(dir, "processed.json").exists())
    }

    // ── what may be archived ─────────────────────────────────────────────────

    @Test fun `a live-recorded track is never archived`() {
        val ids = fileSourcedIds(listOf(TrackIdentity("live", Source.RECORDED, "1:2")))
        assertTrue("RECORDED can never be re-created by an import, so it must never be archived", ids.isEmpty())
    }

    @Test fun `every file-sourced variant is archived and only the recorded one is spared`() {
        val fileSources = Source.entries - Source.RECORDED
        // The RECORDED entry sits FIRST so a `tracks.map { it.id }` regression is caught by both the
        // content and the order of the result, not merely by its size.
        val meta = (listOf(Source.RECORDED) + fileSources).map { TrackIdentity(it.name, it, "k-${it.name}") }
        assertEquals(fileSources.map { it.name }, fileSourcedIds(meta))
    }

    @Test fun `the recorded track survives a mixed library`() {
        val meta = listOf(
            TrackIdentity("live", Source.RECORDED, "1:2"),
            TrackIdentity("scan", Source.FITFILES_SCAN, "3:4"),
            TrackIdentity("drop", Source.GPX_IMPORT, "5:6"),
        )
        assertEquals(listOf("scan", "drop"), fileSourcedIds(meta))
    }

    // ── which source keys are dropped ────────────────────────────────────────

    @Test fun `only the archived tracks' source keys are dropped`() {
        val meta = listOf(
            TrackIdentity("live", Source.RECORDED, "1:2"),
            TrackIdentity("scan", Source.FITFILES_SCAN, "3:4"),
        )
        // The archived one must go, or its file never re-decodes. The RECORDED key must NOT be in the
        // drop set: it is the only thing that collapses that ride's FIT onto the live track.
        assertEquals(setOf("3:4"), archivedSourceKeys(meta))
    }

    @Test fun `a file-sourced track with no source key contributes nothing to the drop set`() {
        assertTrue(archivedSourceKeys(listOf(TrackIdentity("legacy", Source.GPX_IMPORT, ""))).isEmpty())
    }

    // ── the gates on disk ────────────────────────────────────────────────────

    @Test fun `reset deletes the ledger and subtracts only the dropped keys`() {
        val dir = tmp.newFolder()
        File(dir, "processed.json").writeText("""{"entries":{}}""")
        File(dir, "sourcekeys.json").writeText("""["1:2","3:4"]""")
        val store = TrackStore(dir)

        assertTrue(resetImportDedup(dir, store, dropKeys = setOf("3:4")))

        assertFalse("the ledger must be gone", File(dir, "processed.json").exists())
        assertTrue("the sourceKey set must survive, not be deleted", File(dir, "sourcekeys.json").exists())
        assertEquals(setOf("1:2"), store.knownSourceKeys())
    }

    @Test fun `a key written after the caller's snapshot survives the reset`() {
        val dir = tmp.newFolder()
        // "late" stands for a ride that finished (or a track whose json was momentarily unparseable)
        // between the meta snapshot the drop set was computed from and this write. Overwriting with a
        // snapshot of survivors would erase it, and its FIT would land a permanent twin.
        File(dir, "sourcekeys.json").writeText("""["archived","live","late"]""")
        val store = TrackStore(dir)

        assertTrue(resetImportDedup(dir, store, dropKeys = setOf("archived")))

        assertEquals(setOf("live", "late"), store.knownSourceKeys())
    }

    @Test fun `reset REPORTS failure and keeps every key when the keys file cannot be written`() {
        val dir = tmp.newFolder()
        File(dir, "sourcekeys.json").writeText("""["1:2","3:4"]""")
        // A read-only dir makes both the atomic temp write and its plain fallback fail, so
        // atomicWriteText preserves the old file — silently. The reset must not claim success.
        assumeTrue("needs a filesystem that honours a read-only dir", dir.setWritable(false))
        try {
            assertFalse(resetImportDedup(dir, TrackStore(dir), dropKeys = setOf("3:4")))
            assertEquals(setOf("1:2", "3:4"), TrackStore(dir).knownSourceKeys())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun `the archive is skipped when the dedup reset does not take`() {
        val dir = library(track("scan1", Source.FITFILES_SCAN, "k:1"))
        val fit = sources("fit-ro", "ride1.fit")
        assumeTrue("needs a filesystem that honours a read-only dir", dir.setWritable(false))
        try {
            assertNull(prepareRebuild(dir, fitFilesDir = fit, importDir = sources("import-ro")))
            assertTrue("the library must stay live when its keys could not be reset", isLive(dir, "scan1"))
            assertFalse(isArchived(dir, "scan1"))
        } finally {
            dir.setWritable(true)
        }
    }
}
