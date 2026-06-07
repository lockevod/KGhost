package com.enderthor.kvpartner.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun track(id: String, baseLat: Double, baseLng: Double): RecordedTrack =
        RecordedTrack(
            id = id,
            startedAtEpoch = 1_000L,
            points = listOf(
                TrackPointDto(baseLat, baseLng, 0.0, 0.0),
                TrackPointDto(baseLat + 0.001, baseLng + 0.001, 100.0, 20.0),
                TrackPointDto(baseLat + 0.002, baseLng + 0.002, 200.0, 40.0),
            ),
        )

    @Test fun `loadCandidates returns only the track whose bbox overlaps the query`() {
        val store = TrackStore(tmp.newFolder("tracks"))

        // Track A near (40.0, -3.0); track B far away near (50.0, 7.0).
        val a = track("A", 40.0, -3.0)
        val b = track("B", 50.0, 7.0)
        store.save(a)
        store.save(b)

        // Query box tightly around track A only.
        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        val candidates = store.loadCandidates(queryA)

        assertEquals(listOf("A"), candidates.map { it.id })
    }

    @Test fun `save plus loadCandidates round-trips points and id`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val a = track("A", 40.0, -3.0)
        store.save(a)

        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        val loaded = store.loadCandidates(queryA).single()

        assertEquals(a.id, loaded.id)
        assertEquals(a.startedAtEpoch, loaded.startedAtEpoch)
        assertEquals(a.points, loaded.points)
    }

    @Test fun `allTrackIds lists saved tracks excluding the index file`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.save(track("A", 40.0, -3.0))
        store.save(track("B", 50.0, 7.0))

        val ids = store.allTrackIds().toSet()
        assertEquals(setOf("A", "B"), ids)
        assertFalse(ids.contains("index"))
    }

    @Test fun `creates the directory if missing`() {
        val missing = tmp.root.resolve("nested/tracks")
        assertFalse(missing.exists())

        val store = TrackStore(missing)
        store.save(track("A", 40.0, -3.0))

        assertTrue(missing.isDirectory)
        assertEquals(listOf("A"), store.allTrackIds())
    }

    @Test fun `saving a second track preserves the first in the index merge`() {
        // Proves the index read-modify-write in save() folds B in WITHOUT dropping A: after saving
        // A then B, a query box around A still returns A (the second save must not overwrite A's
        // index entry).
        val store = TrackStore(tmp.newFolder("tracks"))
        val a = track("A", 40.0, -3.0)
        val b = track("B", 50.0, 7.0)
        store.save(a)
        store.save(b)

        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        assertEquals(listOf("A"), store.loadCandidates(queryA).map { it.id })
    }

    @Test fun `a corrupt index_json yields empty candidates without throwing`() {
        val dir = tmp.newFolder("tracks")
        val store = TrackStore(dir)
        val a = track("A", 40.0, -3.0)
        store.save(a)

        // Corrupt the on-disk index with garbage that cannot parse as the snapshot map.
        dir.resolve("index.json").writeText("{ this is not valid json ]]")

        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        // readSnapshot() must treat the corrupt-but-present index as empty (logged), not crash.
        val candidates = store.loadCandidates(queryA)
        assertTrue(candidates.isEmpty())
    }

    @Test fun `pure updatedSnapshot then candidateIds selects the overlapping track`() {
        val a = track("A", 40.0, -3.0)
        val b = track("B", 50.0, 7.0)
        val bboxA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        val bboxB = BBox.around(b.points.map { LatLng(it.lat, it.lng) })!!

        var snapshot = TrackStore.updatedSnapshot(emptyMap(), "A", bboxA)
        snapshot = TrackStore.updatedSnapshot(snapshot, "B", bboxB)

        assertEquals(setOf("A"), TrackStore.candidateIds(snapshot, bboxA))
        assertEquals(setOf("B"), TrackStore.candidateIds(snapshot, bboxB))
    }
}
