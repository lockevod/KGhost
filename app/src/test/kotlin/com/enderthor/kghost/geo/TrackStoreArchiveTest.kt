package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackStoreArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts() = (0..40).map { i -> TrackPointDto(41.0, 2.0 + i * 0.0003, i * 25.0, 600.0 * (i / 40.0)) }

    @Test fun `archive moves files, drops index ids, keeps sourcekeys, excludes from listing`() {
        val dir = tmp.newFolder("tracks")
        val store = TrackStore(dir)
        store.add(RecordedTrack("keep", 1L, pts(), sourceKey = "k:1"))
        store.add(RecordedTrack("gone", 2L, pts(), sourceKey = "k:2"))
        // touch the path-cell index so both are indexed
        store.loadByIds(store.rankedCandidateIdsFor(BBox(40.9, 41.1, 1.9, 2.1), 10))

        val moved = store.archive(listOf("gone"))
        assertEquals(1, moved)

        assertTrue(File(dir, "archive/gone.json").isFile)
        assertFalse(File(dir, "gone.json").isFile)
        assertEquals(listOf("keep"), store.allTrackIds())
        // sourcekeys untouched: re-adding the same keyed ride is still deduped
        assertFalse(store.add(RecordedTrack("gone", 2L, pts(), sourceKey = "k:2")))
        // archived id no longer a match candidate
        assertEquals(listOf("keep"), store.loadByIds(store.rankedCandidateIdsFor(BBox(40.9, 41.1, 1.9, 2.1), 10)).map { it.id })
    }

    @Test fun `archive of empty list is a no-op`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        assertEquals(0, store.archive(emptyList()))
    }
}
