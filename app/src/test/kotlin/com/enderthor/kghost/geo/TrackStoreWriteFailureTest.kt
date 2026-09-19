package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression coverage for propagating [atomicWriteText]'s Boolean.
 *
 * `atomicWriteText` deliberately PRESERVES the previous file on an IO error and reports the failure
 * ONLY through its return value. `save`, `add` and both `addAll` bodies used to discard it, so a
 * disk-full / IO-error write still indexed the track, recorded its sourceKey and counted it as
 * added: the rider was told "imported N" while `<id>.json` did not exist, and the sourceKey then
 * deduped every future re-decode of that same ride away — permanently.
 *
 * The failure is injected by occupying `<id>.json.tmp` with a DIRECTORY: `FileOutputStream(tmp)`
 * and the plain-write fallback both throw, so `atomicWriteText` takes its "temp write failed;
 * preserving the previous file" path and returns false — the exact branch production hits on a
 * full disk, with no test hook in production code.
 */
class TrackStoreWriteFailureTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, key: String, baseLat: Double = 40.0): RecordedTrack =
        RecordedTrack(
            id = id,
            startedAtEpoch = 1_000L,
            points = listOf(
                TrackPointDto(baseLat, -3.0, 0.0, 0.0),
                TrackPointDto(baseLat + 0.001, -2.999, 100.0, 20.0),
                TrackPointDto(baseLat + 0.002, -2.998, 200.0, 40.0),
            ),
            sourceKey = key,
            source = Source.FIT_IMPORT,
        )

    /** Makes the next `<id>.json` write fail. */
    private fun jam(dir: File, id: String) {
        assertTrue(File(dir, "$id.json.tmp").mkdirs())
    }

    @Test fun `add reports false and keeps no sourceKey when the track cannot be written`() {
        val dir = tmp.newFolder("tracks")
        val store = TrackStore(dir)
        jam(dir, "A")

        assertFalse("a track that never reached disk is not stored", store.add(track("A", "key-A")))
        assertFalse("<id>.json must not exist", File(dir, "A.json").exists())

        // The decisive part: the key must NOT have been recorded. Otherwise the same ride, re-decoded
        // from its FIT on any later import, is deduped away against a track that does not exist.
        val retry = store.add(track("A2", "key-A"))
        assertTrue("the same sourceKey must still be storable after a failed write", retry)
        assertTrue(File(dir, "A2.json").exists())
    }

    @Test fun `a failed write is not folded into the spatial index`() {
        val dir = tmp.newFolder("tracks2")
        val store = TrackStore(dir)
        val a = track("A", "key-A")
        jam(dir, "A")
        store.add(a)

        // Assert on the INDEX, not on loadCandidates: loadByIds skips ids whose file is missing, so
        // loadCandidates returns empty under the bug too and cannot tell fixed from broken.
        // rankedCandidateIdsFor reads the index snapshot without touching files — ["A"] before the fix.
        val box = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        assertEquals(emptyList<String>(), store.rankedCandidateIdsFor(box, 10))
    }

    @Test fun `the bulk sink counts only what it wrote and names what it could not`() {
        val dir = tmp.newFolder("tracks3")
        val store = TrackStore(dir)
        jam(dir, "B")

        val sink = store.openBulkSink()
        val added = sink.addAll(listOf(track("A", "key-A"), track("B", "key-B"), track("C", "key-C")))

        assertEquals("only the two writable tracks count as added", 2, added)
        assertEquals(setOf("B"), sink.lastFailedIds)
        sink.commit()

        assertTrue(File(dir, "A.json").exists())
        assertFalse(File(dir, "B.json").exists())
        assertTrue(File(dir, "C.json").exists())
        // The key must not have been persisted: assert it through the behaviour it would break —
        // re-importing the same ride must still be storable rather than deduped against nothing.
        assertTrue("the unwritten track's key must not dedup its own re-import", store.add(track("B2", "key-B")))
    }

    @Test fun `the legacy addAll also refuses to count or key an unwritten track`() {
        val dir = tmp.newFolder("tracks5")
        val store = TrackStore(dir)
        jam(dir, "B")

        // The public addAll is a separate implementation from BulkSink.addAll and was changed too;
        // without this, reverting its guard alone passes the whole file.
        val added = store.addAll(listOf(track("A", "key-A"), track("B", "key-B")))

        assertEquals(1, added)
        assertFalse(File(dir, "B.json").exists())
        val box = BBox.around(track("B", "key-B").points.map { LatLng(it.lat, it.lng) })!!
        assertFalse("the unwritten track must not be in the index", "B" in store.rankedCandidateIdsFor(box, 10))
        assertTrue("and its key must not dedup a retry", store.add(track("B2", "key-B")))
    }

    @Test fun `lastFailedIds reports only the most recent addAll`() {
        val dir = tmp.newFolder("tracks4")
        val store = TrackStore(dir)
        jam(dir, "A")

        val sink = store.openBulkSink()
        sink.addAll(listOf(track("A", "key-A")))
        assertEquals(setOf("A"), sink.lastFailedIds)

        // The importer folds `lastFailedIds` into its per-chunk counters and its scan watermark, so a set
        // that is not reset per call would double-count `failed` and over-clamp lastScan on every later
        // chunk of the same run.
        sink.addAll(listOf(track("D", "key-D")))
        assertTrue("a clean chunk must report no failures", sink.lastFailedIds.isEmpty())
    }
}
