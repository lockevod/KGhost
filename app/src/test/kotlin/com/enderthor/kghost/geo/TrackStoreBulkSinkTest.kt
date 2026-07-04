package com.enderthor.kghost.geo

import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackStoreBulkSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, key: String): RecordedTrack =
        RecordedTrack(
            id = id,
            startedAtEpoch = 1_000L,
            points = listOf(TrackPoint(41.0, 2.0, 0.0, 0.0).toDto(), TrackPoint(41.001, 2.001, 120.0, 10.0).toDto()),
            sourceKey = key,
            source = Source.FIT_IMPORT,
        )

    /** Reads `index.json` directly from [dir] (bookkeeping file, not exposed by any TrackStore
     *  getter) — empty map if absent, mirroring TrackStore's own cold-start handling. */
    private fun readIndexJson(dir: File): Map<String, Set<String>> {
        val f = File(dir, "index.json")
        if (!f.isFile) return emptyMap()
        return jsonWithUnknownKeys.decodeFromString<Map<String, Set<String>>>(f.readText())
    }

    /** Reads `sourcekeys.json` directly from [dir] — empty set if absent. */
    private fun readSourceKeysJson(dir: File): Set<String> {
        val f = File(dir, "sourcekeys.json")
        if (!f.isFile) return emptySet()
        return jsonWithUnknownKeys.decodeFromString<Set<String>>(f.readText())
    }

    /** Store the SAME tracks two ways and assert identical live id sets AND identical bookkeeping
     *  files (index.json / sourcekeys.json) — allTrackIds() alone only reads `<id>.json` files and
     *  would miss a divergence in the index/sourcekey bookkeeping (e.g. a commit that dropped an
     *  entry the file-level id set never notices). */
    @Test fun `bulk sink stores the same set as chunked addAll, dedup included`() {
        val tracks = (1..60).map { track("t$it", "k${it % 50}") } // forces cross-chunk dup keys

        val dirA = File(tmp.newFolder("A"), "tracks")
        val storeA = TrackStore(dirA)
        tracks.chunked(25).forEach { storeA.addAll(it) }

        val dirB = File(tmp.newFolder("B"), "tracks")
        val storeB = TrackStore(dirB)
        val sink = storeB.openBulkSink()
        tracks.chunked(25).forEach { sink.addAll(it) }
        sink.commit()

        assertEquals(storeA.allTrackIds().toSortedSet(), storeB.allTrackIds().toSortedSet())
        assertEquals(readIndexJson(dirA), readIndexJson(dirB))
        assertEquals(readSourceKeysJson(dirA), readSourceKeysJson(dirB))
    }

    @Test fun `commit is idempotent`() {
        val dir = File(tmp.newFolder("C"), "tracks")
        val store = TrackStore(dir)
        val sink = store.openBulkSink()
        sink.addAll(listOf(track("x", "kx")))
        sink.commit()
        sink.commit() // must not corrupt or duplicate
        assertEquals(setOf("x"), store.allTrackIds().toSortedSet())
    }

    /** Regression for the "concurrent ride-finish during import" clobber: a BulkSink seeds its
     *  in-memory index/known-keys ONCE at openBulkSink() and holds them for the whole import. If a
     *  ride finishes mid-import via a direct store.add() on the SAME store between two sink chunks,
     *  the sink's stale seed doesn't know about it. The old commit() did an unconditional
     *  `writeSnapshot(index.snapshot())` / `writeSourceKeys(known)` OVERWRITE, which would wipe the
     *  concurrent add's index entry and sourceKey out of the bookkeeping files (the `<id>.json`
     *  itself survives — this is about the index/sourcekeys, not data loss). The fix unions the
     *  sink's accumulated entries onto the CURRENT on-disk state at commit time instead of
     *  overwriting it, so the concurrent add's entry survives. */
    @Test fun `commit preserves a concurrent store add that lands between sink chunks`() {
        val dir = File(tmp.newFolder("D"), "tracks")
        val store = TrackStore(dir)
        val sink = store.openBulkSink()

        val chunk1 = (1..10).map { track("c1_$it", "k1_$it") }
        val chunk2 = (1..10).map { track("c2_$it", "k2_$it") }
        val extra = track("extra", "kExtra")

        sink.addAll(chunk1)
        // Simulate a ride finishing concurrently, directly on the same store, BETWEEN sink chunks —
        // this writes extra's index entry + sourceKey to disk before the sink ever sees it.
        store.add(extra)
        sink.addAll(chunk2)
        sink.commit()

        val indexIds = readIndexJson(dir).values.flatten().toSet()
        assertTrue("extra's id must survive in index.json after commit", "extra" in indexIds)
        assertTrue("extra's sourceKey must survive in sourcekeys.json after commit", "kExtra" in readSourceKeysJson(dir))
        // Sanity: the sink's own chunks are also present (the merge didn't drop them either).
        assertTrue("c1_1" in indexIds)
        assertTrue("c2_1" in indexIds)
    }

    /** Regression for the archive-resurrection bug: the OLD commit() unioned the sink's WHOLE
     *  seeded-from-disk index (captured at openBulkSink()) onto the current on-disk snapshot. That
     *  seed still contains a track that a concurrent archive() removes mid-import — a ride-finish
     *  tidy pass (tidyGroup -> archive) that runs while an import is in flight. Since the seed still
     *  has the archived id, the union at commit() re-adds it to index.json even though its
     *  `<id>.json` has already been moved to archive/ — a permanent dangling index entry that
     *  prewarmAndReconcile() never prunes (it only repairs the opposite drift: a live file missing
     *  from the index).
     *
     *  This test opens a sink on a store that already has a LIVE track T, adds a chunk, then calls
     *  store.archive(listOf(T.id)) directly (simulating tidyGroup firing mid-import), adds another
     *  chunk, and commits. It must find T's id ABSENT from index.json afterwards — the archive's
     *  removal must survive the sink's commit.
     *
     *  Fails on the old whole-seed-union commit (see git history: `T.id` was in the seed taken at
     *  openBulkSink() before the archive call, so the union at commit() re-added it to index.json
     *  even though its file had already moved to archive/). */
    @Test fun `commit honors a concurrent archive removal that lands between sink chunks`() {
        val dir = File(tmp.newFolder("E"), "tracks")
        val store = TrackStore(dir)

        // T is a real, already-indexed live track before the sink ever opens.
        val liveTrack = track("T", "kT")
        assertTrue(store.add(liveTrack))
        assertTrue("T must be indexed before the sink opens", "T" in readIndexJson(dir).values.flatten().toSet())

        val sink = store.openBulkSink() // seeds (and, pre-fix, would capture T in its stale seed)

        val chunk1 = (1..5).map { track("g1_$it", "gk1_$it") }
        val chunk2 = (1..5).map { track("g2_$it", "gk2_$it") }

        sink.addAll(chunk1)
        // Simulate a ride-finish tidy pass archiving T DIRECTLY on the same store, mid-import.
        val movedCount = store.archive(listOf("T"))
        assertEquals(1, movedCount)
        assertTrue(
            "T's file must have moved to archive/",
            File(File(dir, TrackStore.ARCHIVE_SUBDIR), "T.json").isFile,
        )
        assertTrue("T must no longer be a live file", "T" !in store.allTrackIds())

        sink.addAll(chunk2)
        sink.commit()

        val indexIdsAfterCommit = readIndexJson(dir).values.flatten().toSet()
        assertTrue(
            "T must NOT be resurrected in index.json after commit — it was archived mid-import",
            "T" !in indexIdsAfterCommit,
        )
        // Sanity: the sink's own chunks (both before and after the archive call) are present.
        assertTrue("g1_1" in indexIdsAfterCommit)
        assertTrue("g2_1" in indexIdsAfterCommit)
    }
}
