package com.enderthor.kghost.geo

import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File

/**
 * On-disk store for recorded tracks plus a coarse spatial index for candidate pruning.
 *
 * Layout under [dir] (typically `context.filesDir/tracks`):
 *   - `<id>.json`  → one [RecordedTrack] per file.
 *   - `index.json` → the [SpatialIndex] snapshot (`Map<String, Set<String>>`, cell → track ids).
 *
 * Because each track's bounding box is registered against every geohash cell it touches, the index
 * snapshot alone is enough to know which `<id>.json` files could overlap a query box — so
 * [loadCandidates] never has to open the non-overlapping track files.
 *
 * All file IO here is synchronous; callers are expected to run it on `Dispatchers.IO`. The class
 * takes a plain JVM [File] (not an Android `Context`) so it stays unit-testable with a temp folder.
 *
 * The pure parts (index update on a snapshot; candidate-id selection from a snapshot + query box)
 * are factored into [updatedSnapshot] / [candidateIds] so they can be reasoned about and tested
 * without touching the filesystem.
 */
class TrackStore(private val dir: File) {

    /**
     * Serializes the `index.json`/`sourcekeys.json` read→modify→write in [save]/[add]/[addAll]
     * against the read in [loadCandidates] so two overlapping writes can't lose a track from the
     * index and a load can't observe the index mid-modify. The per-track `<id>.json` writes are
     * already isolated (distinct files); only the shared bookkeeping files need guarding.
     *
     * The monitor is keyed by the canonical directory path ([lockFor]) and shared process-wide, so
     * two distinct [TrackStore] instances pointing at the SAME dir (e.g. the extension's ride-finish
     * `add` and RaceScreen's import `addAll`) serialize against each other — a per-instance monitor
     * would not, and concurrent writes would corrupt the bookkeeping files.
     */
    private val indexLock: Any = lockFor(dir)

    /**
     * Serializes tidy passes (ride-end [tidyGroup] vs startup [sweep]) so two can't run at once and
     * double-archive. A SEPARATE monitor from [indexLock] (so the tidy read/meta-build phase doesn't
     * block matching), but still process-wide-per-dir (keyed by canonical path) so two [TrackStore]
     * instances over the same dir can't tidy concurrently — symmetric with [indexLock].
     */
    private val tidyLock: Any = tidyLockFor(dir)

    /** Default cap above which [sweep] skips (a degenerate library; incremental still cleans new rides). */
    private val sweepMaxDefault = 2000

    /**
     * Reads all stored track ids by listing the `<id>.json` files (excludes `index.json` and
     * `sourcekeys.json`, which are bookkeeping files, not tracks).
     */
    fun allTrackIds(): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter {
                it.isFile && it.name.endsWith(JSON_SUFFIX) &&
                    it.name != INDEX_FILE && it.name != SOURCEKEYS_FILE
            }
            .map { it.name.removeSuffix(JSON_SUFFIX) }
    }

    /**
     * Returns the set of source keys already ingested via [add]. An absent `sourcekeys.json` is a
     * normal cold-start state → empty, no log; a present-but-unparseable file is treated as empty
     * and logged (mirrors [readSnapshot]'s corruption handling).
     */
    fun knownSourceKeys(): Set<String> = synchronized(indexLock) { readSourceKeys() }

    /**
     * Pays the index's LAZY one-time costs up front and repairs index/file drift. Call from service
     * startup (off Main) so neither cost lands on the first route match of a ride:
     *  1. [readPathCellSnapshot] runs the one-time legacy bbox→path-cell rebuild if still pending —
     *     on a freshly-imported library that rebuild parses every track and would otherwise block
     *     the first match for seconds under [indexLock].
     *  2. Any live `<id>.json` missing from the index — a crash between the track write and the
     *     index write in [save], or an [archive] whose file move failed — is re-indexed, so a
     *     recorded ride can never silently drop out of matching forever.
     * Returns the number of orphan files re-indexed.
     */
    fun prewarmAndReconcile(): Int = synchronized(indexLock) {
        val snapshot = readPathCellSnapshot()
        val indexed = HashSet<String>().apply { snapshot.values.forEach { addAll(it) } }
        val orphans = allTrackIds().filterNot { it in indexed }
        if (orphans.isEmpty()) return@synchronized 0
        // ONE index folds all orphans, snapshotted/written once — rebuilding the index per orphan
        // (updatedSnapshot in a loop) would be quadratic and could hold indexLock for seconds on the
        // first start after a split-brain heal brings hundreds of files across.
        val index = SpatialIndex(INDEX_PRECISION, snapshot)
        var repaired = 0
        for (id in orphans) {
            val track = loadTrack(id) ?: continue // unparseable → recovered by a future re-import
            val cells = index.cellsForPath(track.points.map { LatLng(it.lat, it.lng) })
            if (cells.isEmpty()) continue // < 2 points: legitimately unindexed (can't be a candidate)
            index.add(id, cells)
            repaired++
        }
        if (repaired > 0) {
            writeSnapshot(index.snapshot())
            Timber.i("KVP index: re-indexed %d orphan track file(s)", repaired)
        }
        repaired
    }

    /**
     * Ingests [track] with sourceKey-based de-duplication and returns whether it was stored.
     *
     * If [track] has a non-empty [RecordedTrack.sourceKey] that was already ingested, the track is
     * skipped (not saved) and `false` is returned — first writer wins. Otherwise the track is
     * [save]d; if its sourceKey is non-empty it is recorded in `sourcekeys.json` so future calls
     * with the same key are deduped. An empty sourceKey is never deduped but is still saved
     * (defensive for legacy tracks). `true` is returned when the track was stored.
     */
    fun add(track: RecordedTrack): Boolean {
        synchronized(indexLock) {
            val key = track.sourceKey
            val known = readSourceKeys()
            if (key.isNotEmpty() && key in known) return false

            save(track)

            if (key.isNotEmpty()) {
                writeSourceKeys(known + key)
            }
            return true
        }
    }

    /**
     * Batch insert. Reads index.json/sourcekeys.json once, folds all non-duplicate tracks into a
     * single SpatialIndex and one source-key set, then writes both files once. O(n) IO/CPU vs add()'s
     * O(n^2) when importing many tracks. Dedups within the batch AND against already-known keys
     * (first occurrence of a key wins). Returns the number of tracks actually stored.
     */
    fun addAll(tracks: List<RecordedTrack>): Int {
        if (tracks.isEmpty()) return 0
        ensureDir()
        return synchronized(indexLock) {
            val known = readSourceKeys().toMutableSet()
            // Build on the MIGRATED snapshot (see save()): readPathCellSnapshot() does the one-time
            // legacy bbox→path-cell rebuild + marker if needed, so a batch import before the first
            // candidate read migrates the legacy index first instead of folding onto (and pinning) it.
            val index = SpatialIndex(INDEX_PRECISION, readPathCellSnapshot())
            var added = 0
            for (t in tracks) {
                if (t.sourceKey.isNotEmpty() && t.sourceKey in known) continue
                // Bulk import: skip the per-track fsync (a fsync storm over N files). Each track json is
                // re-importable from its FitFiles/GPX source, so a torn one after power loss is recovered
                // on the next scan; the durability-critical bookkeeping (index + sourcekeys, written once
                // below) is still fsynced.
                atomicWriteText(File(dir, t.id + JSON_SUFFIX), jsonForStorage.encodeToString(t), fsync = false)
                val cells = index.cellsForPath(t.points.map { LatLng(it.lat, it.lng) })
                if (cells.isNotEmpty()) index.add(t.id, cells)
                if (t.sourceKey.isNotEmpty()) known.add(t.sourceKey)
                added++
            }
            writeSnapshot(index.snapshot())
            writeSourceKeys(known)
            added
        }
    }

    /**
     * Archive (do not delete) the given tracks: under [indexLock], FIRST rewrite `index.json` with the
     * ids removed (from the RAW [readSnapshot], so no path-cell rebuild runs under the lock), THEN move
     * each `<id>.json` into [ARCHIVE_SUBDIR]. Index-first ordering is crash-safe: a crash after the
     * index write but before a move leaves an un-indexed *live* file (excluded from matching, re-archived
     * by the next tidy) rather than a permanent stale index entry. `sourcekeys.json` is left untouched:
     * a keyed track's sourceKey stays known (a re-scan won't re-add it); a manual move-back is intentional
     * recovery. Returns the number of files actually moved.
     */
    fun archive(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val idSet = ids.toHashSet()
        return synchronized(indexLock) {
            val cleaned = readSnapshot()
                .mapValues { (_, v) -> v - idSet }
                .filterValues { it.isNotEmpty() }
            writeSnapshot(cleaned)
            val archiveDir = File(dir, ARCHIVE_SUBDIR)
            archiveDir.mkdirs()
            if (!archiveDir.isDirectory) {
                // The index was already cleaned of these ids (still safe — they drop out of matching),
                // but the files can't be moved. Surface it: a silent no-op archive is hard to diagnose
                // on a device that power-cycles daily.
                Timber.w("KVP tidy: archive dir unavailable at %s; files not moved", archiveDir.path)
                return@synchronized 0
            }
            var moved = 0
            for (id in idSet) {
                val src = File(dir, id + JSON_SUFFIX)
                if (src.isFile && src.renameTo(File(archiveDir, id + JSON_SUFFIX))) moved++
            }
            moved
        }
    }

    /**
     * Incremental cleanup after a new ride is saved: evaluate ONLY the new ride's twin group. Find
     * coarse candidates from the precision-6 index (the new track is already indexed by [add]), build
     * [TrackMeta] for that handful, run [selectArchivable], and [archive] the losers. The just-saved
     * ride is always the most recent → always a survivor. Serialized by [tidyLock]. Returns the count
     * archived.
     */
    fun tidyGroup(newTrack: RecordedTrack): Int = synchronized(tidyLock) {
        val coarseCells = SpatialIndex(INDEX_PRECISION)
            .cellsForPath(newTrack.points.map { LatLng(it.lat, it.lng) })
        val candidateIds = synchronized(indexLock) {
            val snap = readSnapshot()
            coarseCells.flatMapTo(HashSet()) { snap[it] ?: emptySet() }
        } + newTrack.id
        val metas = candidateIds.mapNotNull { id -> loadTrack(id)?.let { trackMetaOf(it) } }
        val toArchive = selectArchivable(metas)
        archive(toArchive)
        toArchive.size
    }

    /**
     * One-time backlog pass over the WHOLE active library: stream-build [TrackMeta] for every active
     * track (small peak memory — one [RecordedTrack] parsed at a time), run [selectArchivable] over all
     * (group-correct), [archive] the result. Skips (returns 0) when the library exceeds [maxTracks]
     * (a degenerate library; ride-end [tidyGroup] still cleans new rides). Serialized by [tidyLock].
     */
    fun sweep(maxTracks: Int = sweepMaxDefault): Int = synchronized(tidyLock) {
        val ids = allTrackIds()
        if (ids.size > maxTracks) {
            Timber.i("KVP tidy: sweep skipped (%d tracks > cap %d)", ids.size, maxTracks)
            return@synchronized 0
        }
        val metas = ids.mapNotNull { id -> loadTrack(id)?.let { trackMetaOf(it) } }
        val toArchive = selectArchivable(metas)
        archive(toArchive)
        toArchive.size
    }

    /**
     * Writes `<id>.json` for [track] and folds its PATH cells into `index.json`.
     *
     * The track is registered against the cells its path actually passes through
     * ([SpatialIndex.cellsForPath]), NOT its rectangular bounding box, so pruning and overlap ranking
     * reflect real route overlap. A track with fewer than two points yields no path cells; its file
     * is still written (so it round-trips) but it is not indexed — it can never be a spatial
     * candidate (same as the old null-bbox case).
     */
    fun save(track: RecordedTrack) {
        ensureDir()

        atomicWriteText(File(dir, track.id + JSON_SUFFIX), jsonForStorage.encodeToString(track))

        val cells = SpatialIndex(INDEX_PRECISION).cellsForPath(track.points.map { LatLng(it.lat, it.lng) })
        if (cells.isEmpty()) return
        // Read-modify-write of the shared index must be atomic against concurrent saves/loads.
        synchronized(indexLock) {
            // Build on the MIGRATED snapshot, not the raw one: readPathCellSnapshot() performs the
            // one-time legacy bbox→path-cell rebuild (and writes the marker) if needed BEFORE we fold
            // in the new track. Otherwise a save before the first candidate read would fold onto the
            // legacy bbox index and leave the marker in place, pre-empting the rebuild forever.
            val newSnapshot = updatedSnapshot(readPathCellSnapshot(), track.id, cells, INDEX_PRECISION)
            writeSnapshot(newSnapshot)
        }
    }

    /**
     * Returns the parsed tracks whose recorded bbox could overlap [routeBBox], loading only the
     * candidate `<id>.json` files. Tracks whose file is missing or unparseable are skipped.
     */
    fun loadCandidates(routeBBox: BBox): List<RecordedTrack> {
        // Snapshot the (path-cell, migrated) index under the lock so we never read it mid-modify.
        val ids = synchronized(indexLock) { candidateIds(readPathCellSnapshot(), routeBBox, INDEX_PRECISION) }
        return loadByIds(ids)
    }

    /**
     * Returns the parsed candidate tracks RANKED by ROUTE OVERLAP and capped at [maxTracks], parsing
     * ONLY the chosen files.
     *
     * "Race your own on THIS route" wants the tracks that cover the most of the route — not the most
     * recent ones. Using the spatial index snapshot we score each candidate by how many of the
     * route's cells it appears in (its overlap with the route), rank by that score, take the top
     * [maxTracks], and only THEN open + parse those files. This avoids parsing all candidates before
     * the matcher's own cap (which used recency, the wrong cap here) and stops the relevant old ride
     * from being silently dropped by a recency cut.
     */
    fun loadTopCandidates(routeBBox: BBox, maxTracks: Int): List<RecordedTrack> {
        // Snapshot the (path-cell, migrated) index under the lock so we never read it mid-modify.
        val ids = synchronized(indexLock) {
            rankCandidateIds(readPathCellSnapshot(), routeBBox, INDEX_PRECISION, maxTracks)
        }
        return loadByIds(ids)
    }

    /** Loads + parses the `<id>.json` files for [ids]; missing/unparseable files are skipped. */
    private fun loadByIds(ids: Iterable<String>): List<RecordedTrack> =
        ids.mapNotNull { id ->
            val f = File(dir, id + JSON_SUFFIX)
            if (!f.isFile) return@mapNotNull null
            runCatching { jsonWithUnknownKeys.decodeFromString<RecordedTrack>(f.readText()) }
                .getOrNull()
        }

    /** Loads + parses one `<id>.json`; null if missing/unparseable. */
    private fun loadTrack(id: String): RecordedTrack? = loadByIds(listOf(id)).firstOrNull()

    // --- file helpers -------------------------------------------------------

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    /**
     * Returns the spatial-index snapshot guaranteed to be PATH-CELL based, performing a one-time,
     * lazy migration if needed. MUST be called under [indexLock] (both candidate-read callers hold
     * it), so the rebuild runs at most once even under concurrent loads.
     *
     * The persisted `index.json` may have been written by an older build that registered tracks by
     * their rectangular bounding box. The presence of [INDEX_PATHCELLS_MARKER] records that the index
     * is already path-cell based. If the marker is absent, every `<id>.json` is re-read, its path
     * cells computed via [SpatialIndex.cellsForPath], a fresh snapshot built and written, and the
     * marker created — after which subsequent reads skip straight to [readSnapshot].
     *
     * This re-parses all tracks once, off-Main: the only callers ([loadCandidates] /
     * [loadTopCandidates]) run on `Dispatchers.Default`/`IO` (route load / matcher), never on Main.
     */
    private fun readPathCellSnapshot(): Map<String, Set<String>> {
        val marker = File(dir, INDEX_PATHCELLS_MARKER)
        if (marker.isFile) return readSnapshot()

        // Marker absent → rebuild from path cells (once, under the lock the callers already hold).
        val index = SpatialIndex(INDEX_PRECISION)
        var n = 0
        for (id in allTrackIds()) {
            val f = File(dir, id + JSON_SUFFIX)
            if (!f.isFile) continue
            val track = runCatching { jsonWithUnknownKeys.decodeFromString<RecordedTrack>(f.readText()) }
                .getOrNull() ?: continue
            val cells = index.cellsForPath(track.points.map { LatLng(it.lat, it.lng) })
            if (cells.isNotEmpty()) index.add(track.id, cells)
            n++
        }
        val rebuilt = index.snapshot()
        writeSnapshot(rebuilt)
        ensureDir()
        runCatching { marker.writeText("1") }
            .onFailure { Timber.w(it, "failed to write path-cells marker; rebuild may repeat") }
        Timber.i("rebuilt spatial index with path cells for %d tracks", n)
        return rebuilt
    }

    private fun readSnapshot(): Map<String, Set<String>> {
        val f = File(dir, INDEX_FILE)
        // An absent index is a normal cold-start state → empty, no log.
        if (!f.isFile) return emptyMap()
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<Map<String, Set<String>>>(f.readText())
        }.getOrElse { e ->
            // A PRESENT-but-unparseable index is a corruption (e.g. a torn write or disk damage).
            // Treating it silently as empty would drop every candidate; surface it instead.
            Timber.w(e, "index.json present but failed to parse; treating as empty (corrupt index?)")
            emptyMap()
        }
    }

    private fun writeSnapshot(snapshot: Map<String, Set<String>>) {
        ensureDir()
        atomicWriteText(File(dir, INDEX_FILE), jsonForStorage.encodeToString(snapshot))
    }

    private fun readSourceKeys(): Set<String> {
        val f = File(dir, SOURCEKEYS_FILE)
        // An absent file is a normal cold-start state → empty, no log.
        if (!f.isFile) return emptySet()
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<Set<String>>(f.readText())
        }.getOrElse { e ->
            // A PRESENT-but-unparseable file is corruption; treat as empty (re-dedup) but surface it.
            Timber.w(e, "sourcekeys.json present but failed to parse; treating as empty (corrupt?)")
            emptySet()
        }
    }

    private fun writeSourceKeys(keys: Set<String>) {
        ensureDir()
        atomicWriteText(File(dir, SOURCEKEYS_FILE), jsonForStorage.encodeToString(keys))
    }

    companion object {
        /**
         * Process-wide locks keyed by canonical directory path so all [TrackStore] instances over
         * the same dir share one monitor. Without this, per-instance monitors would let concurrent
         * writers (extension `add` + RaceScreen `addAll`) corrupt the shared bookkeeping files.
         */
        private val dirLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(dir: File): Any {
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            return dirLocks.computeIfAbsent(key) { Any() }
        }

        /** Process-wide tidy locks, keyed by canonical dir path (separate monitor from [dirLocks]). */
        private val dirTidyLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun tidyLockFor(dir: File): Any {
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            return dirTidyLocks.computeIfAbsent(key) { Any() }
        }

        private const val JSON_SUFFIX = ".json"
        private const val INDEX_FILE = "index.json"

        /** Stores the serialized `Set<String>` of source keys ingested via [add] (dedup state). */
        private const val SOURCEKEYS_FILE = "sourcekeys.json"

        /** Subdirectory holding archived (pruned) tracks; excluded from listing/index/matching. */
        const val ARCHIVE_SUBDIR = "archive"

        /**
         * Marker file whose presence records that `index.json` is PATH-CELL based (not the legacy
         * bbox-cell layout). Absent → [readPathCellSnapshot] performs the one-time rebuild.
         */
        const val INDEX_PATHCELLS_MARKER = ".pathcells"

        /** Geohash precision for the on-disk index. Matches [SpatialIndex]'s default (≈ 1.2 km cells). */
        const val INDEX_PRECISION: Int = 6

        /**
         * Pure: folds [trackId]/[bbox] into [snapshot], returning a new snapshot. Rebuilds a
         * [SpatialIndex] from [snapshot], adds the track, and snapshots it back — no filesystem.
         */
        fun updatedSnapshot(
            snapshot: Map<String, Set<String>>,
            trackId: String,
            bbox: BBox,
            precision: Int = INDEX_PRECISION,
        ): Map<String, Set<String>> {
            val index = SpatialIndex(precision, snapshot)
            index.add(trackId, bbox)
            return index.snapshot()
        }

        /**
         * Pure: folds [trackId] registered against the precomputed [cells] (e.g. from
         * [SpatialIndex.cellsForPath]) into [snapshot], returning a new snapshot — no filesystem.
         */
        fun updatedSnapshot(
            snapshot: Map<String, Set<String>>,
            trackId: String,
            cells: Set<String>,
            precision: Int = INDEX_PRECISION,
        ): Map<String, Set<String>> {
            val index = SpatialIndex(precision, snapshot)
            index.add(trackId, cells)
            return index.snapshot()
        }

        /**
         * Pure: candidate track ids whose cells in [snapshot] overlap [routeBBox] — no filesystem.
         */
        fun candidateIds(
            snapshot: Map<String, Set<String>>,
            routeBBox: BBox,
            precision: Int = INDEX_PRECISION,
        ): Set<String> = SpatialIndex(precision, snapshot).candidates(routeBBox)

        /**
         * Pure: candidate track ids RANKED by route overlap, capped at [maxTracks] — no filesystem.
         *
         * The overlap score for a track is the number of the ROUTE's geohash cells in which that
         * track appears (from [snapshot]). A track that ran the whole route shares many cells; a
         * track that only clipped a corner shares few; a track outside the route shares none and is
         * excluded entirely. Ranking is descending by overlap score, tie-broken deterministically by
         * track id (ascending) so the result is stable and reproducible for tests. Returns at most
         * [maxTracks] ids (the whole ranked list when [maxTracks] is non-positive — defensive).
         */
        fun rankCandidateIds(
            snapshot: Map<String, Set<String>>,
            routeBBox: BBox,
            precision: Int = INDEX_PRECISION,
            maxTracks: Int,
        ): List<String> {
            // The route's cells. Reuse SpatialIndex's cell geometry so ranking and candidate
            // selection agree exactly on what "the route's cells" are.
            val routeCells = SpatialIndex(precision).cellsFor(routeBBox)
            // Tally, per track id, how many route cells it appears in (overlap score).
            val overlap = HashMap<String, Int>()
            for (cell in routeCells) {
                val ids = snapshot[cell] ?: continue
                for (id in ids) overlap[id] = (overlap[id] ?: 0) + 1
            }
            // Score-descending, then id-ascending for a deterministic, stable order.
            val ranked = overlap.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
            return if (maxTracks > 0) ranked.take(maxTracks) else ranked
        }
    }
}
