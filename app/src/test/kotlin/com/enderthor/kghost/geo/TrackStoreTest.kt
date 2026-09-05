package com.enderthor.kghost.geo

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

    @Test fun `add skips a track whose sourceKey is already present`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val a = RecordedTrack("a", 1000L, listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0), TrackPointDto(0.0, 0.001, 100.0, 20.0)), sourceKey = "k1")
        val b = RecordedTrack("b", 2000L, listOf(TrackPointDto(1.0, 1.0, 0.0, 0.0), TrackPointDto(1.0, 1.001, 100.0, 20.0)), sourceKey = "k1")
        assertTrue(store.add(a))      // first wins
        assertFalse(store.add(b))     // same sourceKey → skipped
        assertTrue("k1" in store.knownSourceKeys())
        assertEquals(1, store.allTrackIds().size)
    }

    @Test fun `addAll dedups within the batch and against known keys`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val k1 = RecordedTrack("k1", 1000L, listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0), TrackPointDto(0.0, 0.001, 100.0, 20.0)), sourceKey = "k1")
        store.add(k1) // pre-known key "k1"

        val k1dup = RecordedTrack("k1dup", 1100L, listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0), TrackPointDto(0.0, 0.001, 100.0, 20.0)), sourceKey = "k1")
        val k2 = RecordedTrack("k2", 2000L, listOf(TrackPointDto(1.0, 1.0, 0.0, 0.0), TrackPointDto(1.0, 1.001, 100.0, 20.0)), sourceKey = "k2")
        val k2dup = RecordedTrack("k2dup", 2100L, listOf(TrackPointDto(1.0, 1.0, 0.0, 0.0), TrackPointDto(1.0, 1.001, 100.0, 20.0)), sourceKey = "k2")
        val k3 = RecordedTrack("k3", 3000L, listOf(TrackPointDto(2.0, 2.0, 0.0, 0.0), TrackPointDto(2.0, 2.001, 100.0, 20.0)), sourceKey = "k3")

        val added = store.addAll(listOf(k1dup, k2, k2dup, k3))
        assertEquals(2, added) // k2 and k3 stored; k1dup and k2dup skipped

        assertEquals(3, store.allTrackIds().size) // k1 + k2 + k3
        val known = store.knownSourceKeys()
        assertTrue("k1" in known)
        assertTrue("k2" in known)
        assertTrue("k3" in known)
    }

    @Test fun `addAll registers tracks in the spatial index so loadCandidates finds them`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val a = track("A", 40.0, -3.0)
        val b = track("B", 50.0, 7.0)
        store.addAll(listOf(a, b))

        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        assertEquals(listOf("A"), store.loadCandidates(queryA).map { it.id })
    }

    @Test fun `rankCandidateIds ranks by route-cell overlap, excludes non-overlapping, respects cap`() {
        // A long-ish route bbox so it spans several precision-6 cells.
        val routeBBox = BBox(minLat = 41.0, maxLat = 41.05, minLng = 2.0, maxLng = 2.05)
        val routeCells = SpatialIndex(TrackStore.INDEX_PRECISION).cellsFor(routeBBox).toList()
        assertTrue("route must span several cells for this test", routeCells.size >= 3)

        // Pick one cell OUTSIDE the route (Madrid-ish) so track C never overlaps the route.
        val outsideCell = geohash(40.41, -3.70, TrackStore.INDEX_PRECISION)
        assertFalse(routeCells.contains(outsideCell))

        // Build a snapshot by hand: A appears in ALL route cells (max overlap), B in exactly ONE
        // route cell (a little overlap), C only in the outside cell (no overlap).
        val snapshot = HashMap<String, MutableSet<String>>()
        for (cell in routeCells) snapshot.getOrPut(cell) { mutableSetOf() }.add("A")
        snapshot.getOrPut(routeCells.first()) { mutableSetOf() }.add("B")
        snapshot.getOrPut(outsideCell) { mutableSetOf() }.add("C")
        val readonly: Map<String, Set<String>> = snapshot.mapValues { it.value.toSet() }

        // Ranking: A (most overlap) before B (some overlap); C excluded (zero overlap).
        val ranked = TrackStore.rankCandidateIds(
            readonly, routeBBox, TrackStore.INDEX_PRECISION, maxTracks = 10,
        )
        assertEquals(listOf("A", "B"), ranked)

        // Cap respected: only the single most-overlapping track is returned.
        val capped = TrackStore.rankCandidateIds(
            readonly, routeBBox, TrackStore.INDEX_PRECISION, maxTracks = 1,
        )
        assertEquals(listOf("A"), capped)
    }

    @Test fun `rankCandidateIds tie-breaks deterministically by id ascending`() {
        // Two tracks with the SAME overlap (both in every route cell) must order by id ascending.
        val routeBBox = BBox(minLat = 41.0, maxLat = 41.02, minLng = 2.0, maxLng = 2.02)
        val routeCells = SpatialIndex(TrackStore.INDEX_PRECISION).cellsFor(routeBBox).toList()
        val snapshot = HashMap<String, MutableSet<String>>()
        for (cell in routeCells) snapshot.getOrPut(cell) { mutableSetOf() }.apply { add("zzz"); add("aaa") }
        val readonly: Map<String, Set<String>> = snapshot.mapValues { it.value.toSet() }

        val ranked = TrackStore.rankCandidateIds(
            readonly, routeBBox, TrackStore.INDEX_PRECISION, maxTracks = 10,
        )
        assertEquals(listOf("aaa", "zzz"), ranked)
    }

    @Test fun `the ranked candidate set parses only the overlapping tracks`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        // A overlaps the query bbox (near 40.0,-3.0); C is far away (near 50.0,7.0) → not a candidate.
        val a = track("A", 40.0, -3.0)
        val c = track("C", 50.0, 7.0)
        store.save(a)
        store.save(c)

        val queryA = BBox.around(a.points.map { LatLng(it.lat, it.lng) })!!
        val loaded = store.loadByIds(store.rankedCandidateIdsFor(queryA, maxTracks = 24))
        assertEquals(listOf("A"), loaded.map { it.id })
    }

    @Test fun `loadByIds keeps the captured ranking when the index changes`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val original = track("original", 40.0, -3.0)
        store.save(original)
        val route = BBox.around(original.points.map { LatLng(it.lat, it.lng) })!!
        val selectedIds = store.rankedCandidateIdsFor(route, maxTracks = 24).toSet()

        store.save(track("later", 40.0, -3.0))

        assertEquals(listOf("original"), store.loadByIds(selectedIds).map { it.id })
    }

    @Test fun `path-cell ranking ranks a SHORT overlapping track above a LONG bystander`() {
        // EVICTION-FIX PROOF.
        //
        // The route runs north along a thin corridor. SHORT rides the route's corridor for a stretch.
        // LONG is an L-shaped commute: it rides a short common stub of the corridor, then turns away
        // east for a long leg. LONG's BOUNDING BOX blankets many of the route's cells (because the
        // box spans both the north corridor and the east leg), but LONG's PATH only touches a couple
        // of the route's cells. With bbox-cell indexing LONG would (wrongly) outrank SHORT; with
        // path-cell indexing SHORT (which actually rides more of the route) outranks LONG.
        val baseLat = 41.000
        val baseLng = 2.000

        // Route: straight north corridor.
        fun north(fromI: Int, toI: Int) = (fromI..toI).map { LatLng(baseLat + it * 0.0008, baseLng) }
        val routePoints = north(0, 40)
        val routeBBox = BBox.around(routePoints)!!

        // SHORT: rides the route corridor from i=5..25 (a long stretch of real corridor overlap).
        val shortPath = north(5, 25)

        // LONG: a long, DENSE diagonal from the route's SW start up to the far NE. Its BOUNDING BOX is
        // a big rectangle [southLat..northLat] x [baseLng..farEast] that BLANKETS the whole vertical
        // route corridor — but its thin diagonal PATH only crosses the corridor line near the start.
        // (Dense samples model a real ~20 m-decimated ride: each segment bbox ≈ the segment.)
        val northLat = baseLat + 40 * 0.0008
        val farEastLng = baseLng + 60 * 0.0008
        val steps = 400
        val longPath = (0..steps).map { i ->
            val f = i.toDouble() / steps
            LatLng(baseLat + f * (northLat - baseLat), baseLng + f * (farEastLng - baseLng))
        }

        // Sanity: LONG's bbox covers route cells it never rides (the rectangle spans north AND east).
        val longBBox = BBox.around(longPath.map { LatLng(it.lat, it.lng) })!!
        val routeCells = SpatialIndex(TrackStore.INDEX_PRECISION).cellsFor(routeBBox)
        val longBBoxCells = SpatialIndex(TrackStore.INDEX_PRECISION).cellsFor(longBBox)
        val longPathCells = SpatialIndex(TrackStore.INDEX_PRECISION).cellsForPath(longPath)
        // LONG's bbox blankets MORE route cells than LONG's path actually touches.
        assertTrue(
            "LONG bbox must blanket more route cells than its path (the bystander pathology)",
            longBBoxCells.intersect(routeCells).size > longPathCells.intersect(routeCells).size,
        )

        // Build the snapshot the NEW way: path cells.
        var snapshot: Map<String, Set<String>> = emptyMap()
        snapshot = TrackStore.updatedSnapshot(
            snapshot, "SHORT", SpatialIndex(TrackStore.INDEX_PRECISION).cellsForPath(shortPath),
        )
        snapshot = TrackStore.updatedSnapshot(
            snapshot, "LONG", SpatialIndex(TrackStore.INDEX_PRECISION).cellsForPath(longPath),
        )

        val ranked = TrackStore.rankCandidateIds(
            snapshot, routeBBox, TrackStore.INDEX_PRECISION, maxTracks = 10,
        )
        assertEquals("SHORT (real overlap) must outrank LONG (bbox bystander)", "SHORT", ranked.first())
        assertTrue("LONG, if present, ranks below SHORT", ranked.indexOf("SHORT") < ranked.indexOf("LONG"))
    }

    @Test fun `migration rebuilds an old bbox-style index into path cells and writes the marker`() {
        val dir = tmp.newFolder("tracks")

        // A thin diagonal track: its bbox covers a wide rectangle, its path is a thin line.
        val sw = 41.000 to 2.000
        val ne = 41.090 to 2.090
        val n = 200
        val pts = (0..n).map { i ->
            val f = i.toDouble() / n
            TrackPointDto(sw.first + f * (ne.first - sw.first), sw.second + f * (ne.second - sw.second), i * 20.0, i * 4.0)
        }
        val diag = RecordedTrack("DIAG", 1_000L, pts)

        // Write the <id>.json directly (simulating a pre-migration store).
        val store = TrackStore(dir)
        // Use save() to write the file; this also writes a (new, path-cell) index — but we then
        // overwrite index.json with an OLD bbox-style index and DELETE the marker to force a rebuild.
        store.save(diag)

        // Overwrite index.json with the OLD bbox-cell registration and remove the marker.
        val bbox = BBox.around(diag.points.map { LatLng(it.lat, it.lng) })!!
        val bboxSnapshot = SpatialIndex(TrackStore.INDEX_PRECISION).let { idx ->
            idx.add("DIAG", bbox); idx.snapshot()
        }
        dir.resolve("index.json").writeText(
            com.enderthor.kghost.extension.jsonForStorage.encodeToString(
                kotlinx.serialization.serializer<Map<String, Set<String>>>(), bboxSnapshot,
            ),
        )
        dir.resolve(".pathcells").delete()
        assertFalse(dir.resolve(".pathcells").exists())

        // The NW corner of the bbox is OFF the diagonal — present in the old bbox index, must vanish.
        val nwCorner = geohash(bbox.maxLat, bbox.minLng, TrackStore.INDEX_PRECISION)
        assertTrue("old bbox index contains the off-path NW corner", nwCorner in bboxSnapshot.keys)

        // Touch the candidate-read path → triggers the one-time rebuild.
        val queryNw = BBox(bbox.maxLat - 0.001, bbox.maxLat + 0.001, bbox.minLng - 0.001, bbox.minLng + 0.001)
        store.loadCandidates(queryNw)

        // Marker now exists; index rebuilt to path cells (NW corner cell no longer registered).
        assertTrue("marker file must be created after rebuild", dir.resolve(".pathcells").exists())
        val rebuilt = com.enderthor.kghost.extension.jsonWithUnknownKeys.decodeFromString(
            kotlinx.serialization.serializer<Map<String, Set<String>>>(),
            dir.resolve("index.json").readText(),
        )
        assertFalse("off-path NW corner cell must be gone after path-cell rebuild", nwCorner in rebuilt.keys)

        // Recall preserved: a query on the diagonal still finds DIAG.
        val onPath = BBox(41.045 - 0.001, 41.045 + 0.001, 2.045 - 0.001, 2.045 + 0.001)
        assertEquals(listOf("DIAG"), store.loadCandidates(onPath).map { it.id })
    }

    @Test fun `save before the first candidate read migrates the legacy bbox index first`() {
        // PRE-EMPTION-FIX PROOF.
        //
        // On an upgraded device the on-disk index.json is legacy (bbox-cell) and the .pathcells marker
        // is absent. If a ride ends (-> save/addAll) BEFORE any route is loaded, the new track used to
        // be folded onto the LEGACY snapshot and the marker written, so readPathCellSnapshot() later
        // saw the marker and the path-cell rebuild NEVER ran. Now save() builds on the MIGRATED
        // snapshot, so the legacy track is reindexed by its path cells first.
        val dir = tmp.newFolder("tracks")

        // A pre-existing LEGACY track: a thin diagonal whose bbox covers a wide rectangle.
        val sw = 41.000 to 2.000
        val ne = 41.090 to 2.090
        val n = 200
        val diagPts = (0..n).map { i ->
            val f = i.toDouble() / n
            TrackPointDto(sw.first + f * (ne.first - sw.first), sw.second + f * (ne.second - sw.second), i * 20.0, i * 4.0)
        }
        val diag = RecordedTrack("DIAG", 1_000L, diagPts)

        // Write DIAG's <id>.json (so the migration can re-read it) and an OLD bbox-cell index.json,
        // with NO marker — exactly the upgraded-device-before-first-load state.
        dir.resolve("DIAG.json").writeText(
            com.enderthor.kghost.extension.jsonForStorage.encodeToString(
                kotlinx.serialization.serializer<RecordedTrack>(), diag,
            ),
        )
        val diagBBox = BBox.around(diag.points.map { LatLng(it.lat, it.lng) })!!
        val bboxSnapshot = SpatialIndex(TrackStore.INDEX_PRECISION).let { idx ->
            idx.add("DIAG", diagBBox); idx.snapshot()
        }
        dir.resolve("index.json").writeText(
            com.enderthor.kghost.extension.jsonForStorage.encodeToString(
                kotlinx.serialization.serializer<Map<String, Set<String>>>(), bboxSnapshot,
            ),
        )
        assertFalse("precondition: no marker (upgraded device)", dir.resolve(".pathcells").exists())

        // The NW corner cell of DIAG's bbox is OFF its diagonal path: present in the legacy bbox
        // index, must be gone after the path-cell rebuild.
        val nwCorner = geohash(diagBBox.maxLat, diagBBox.minLng, TrackStore.INDEX_PRECISION)
        assertTrue("legacy bbox index contains DIAG's off-path NW corner", nwCorner in bboxSnapshot.keys)

        // A ride ends -> save() a NEW track BEFORE any candidate read.
        val store = TrackStore(dir)
        val fresh = track("FRESH", 40.0, -3.0)
        store.save(fresh)

        // Migration happened: DIAG's off-path NW corner cell is gone (legacy index was reindexed by
        // path cells, not just folded onto).
        val rebuilt = com.enderthor.kghost.extension.jsonWithUnknownKeys.decodeFromString(
            kotlinx.serialization.serializer<Map<String, Set<String>>>(),
            dir.resolve("index.json").readText(),
        )
        assertFalse("off-path NW corner must be gone after migrate-first save", nwCorner in rebuilt.keys)

        // The marker now exists (single marker-creation path via readPathCellSnapshot()).
        assertTrue("marker must exist after migrate-first save", dir.resolve(".pathcells").exists())

        // The legacy track survives the migration and is still findable on its path.
        val onDiag = BBox(41.045 - 0.001, 41.045 + 0.001, 2.045 - 0.001, 2.045 + 0.001)
        assertEquals(listOf("DIAG"), store.loadCandidates(onDiag).map { it.id })

        // The new track is present and findable.
        val queryFresh = BBox.around(fresh.points.map { LatLng(it.lat, it.lng) })!!
        assertEquals(listOf("FRESH"), store.loadCandidates(queryFresh).map { it.id })
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

    @Test fun `allTracksMeta reports id source and sourceKey for every stored track`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.save(track("A", 40.0, -3.0).copy(source = Source.RECORDED, sourceKey = "1:2"))
        store.save(track("B", 50.0, 7.0).copy(source = Source.FITFILES_SCAN, sourceKey = "3:4"))

        assertEquals(
            setOf(
                TrackIdentity("A", Source.RECORDED, "1:2"),
                TrackIdentity("B", Source.FITFILES_SCAN, "3:4"),
            ),
            store.allTracksMeta().toSet(),
        )
    }

    @Test fun `forEachTrack streams every stored track exactly once`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.save(track("A", 40.0, -3.0))
        store.save(track("B", 50.0, 7.0))

        val seen = ArrayList<String>()
        store.forEachTrack { seen.add(it.id) }

        assertEquals(listOf("A", "B"), seen.sorted())
    }

    @Test fun `dropSourceKeys subtracts from what is on disk and keeps the rest`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.add(track("A", 40.0, -3.0).copy(sourceKey = "keep"))
        store.add(track("B", 41.0, -3.0).copy(sourceKey = "drop"))

        assertTrue(store.dropSourceKeys(setOf("drop", "never-there")))

        assertEquals(setOf("keep"), store.knownSourceKeys())
    }

    @Test fun `an imported fit fills missing altitude without replacing recorded identity`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val recorded = track("live", 40.0, -3.0).copy(sourceKey = "1:20", source = Source.RECORDED)
        val fit = recorded.copy(
            id = "fit",
            source = Source.FITFILES_SCAN,
            points = listOf(
                TrackPointDto(0.0, 0.0, 0.0, 0.0, 100.0),
                TrackPointDto(0.0, 0.0, 200.0, 40.0, 140.0),
            ),
        )
        assertTrue(store.add(recorded))
        // An enrichment is NOT a store: add() must stay false, or the ride-end caller runs tidyGroup
        // (archiving real rides) against a track it never saved.
        assertFalse(store.add(fit))
        assertEquals(listOf("live"), store.allTrackIds())
        assertEquals(listOf(100.0, 120.0, 140.0), store.loadByIds(listOf("live")).single().points.map { it.eleM })
    }

    /**
     * The sink caches sourceKey -> RECORDED id across chunks, but `tidyGroup`/`sweep` take `tidyLock`
     * while `archive` takes `indexLock`, so a ride ending mid-import can archive a cached twin between
     * two chunks. Enriching from a cached OBJECT would write it back into the live dir, un-indexed, and
     * `prewarmAndReconcile` would re-adopt the archived near-duplicate at next startup.
     */
    @Test fun `a twin archived mid-import is not resurrected by a later enrichment`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val recorded = track("live", 40.0, -3.0).copy(sourceKey = "1:20", source = Source.RECORDED)
        val fit = recorded.copy(
            id = "fit",
            source = Source.FITFILES_SCAN,
            points = listOf(
                TrackPointDto(0.0, 0.0, 0.0, 0.0, 100.0),
                TrackPointDto(0.0, 0.0, 200.0, 40.0, 140.0),
            ),
        )
        assertTrue(store.add(recorded))

        val sink = store.openBulkSink()
        sink.addAll(listOf(fit))                        // chunk 1: primes the key -> id cache on "live"
        assertEquals(1, store.archive(listOf("live")))  // a ride ends between chunks
        sink.addAll(listOf(fit.copy(id = "fit2")))      // chunk 2: same key, cache still points at "live"

        assertEquals(emptyList<String>(), store.allTrackIds())
    }

    @Test fun `an imported duplicate with no usable altitude stays a duplicate`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        val recorded = track("live", 40.0, -3.0).copy(sourceKey = "1:20", source = Source.RECORDED)
        assertTrue(store.add(recorded))
        assertFalse(store.add(recorded.copy(id = "fit", source = Source.FITFILES_SCAN)))
        assertEquals(listOf("live"), store.allTrackIds())
    }
}
