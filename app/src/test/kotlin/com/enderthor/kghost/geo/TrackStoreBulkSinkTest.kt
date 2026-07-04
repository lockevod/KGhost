package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
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

    /** Store the SAME tracks two ways and assert identical live id sets. */
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
}
