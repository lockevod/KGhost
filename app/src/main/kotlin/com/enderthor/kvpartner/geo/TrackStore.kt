package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.extension.jsonForStorage
import com.enderthor.kvpartner.extension.jsonWithUnknownKeys
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
            val index = SpatialIndex(INDEX_PRECISION, readSnapshot())
            var added = 0
            for (t in tracks) {
                if (t.sourceKey.isNotEmpty() && t.sourceKey in known) continue
                atomicWriteText(File(dir, t.id + JSON_SUFFIX), jsonForStorage.encodeToString(t))
                BBox.around(t.points.map { LatLng(it.lat, it.lng) })?.let { index.add(t.id, it) }
                if (t.sourceKey.isNotEmpty()) known.add(t.sourceKey)
                added++
            }
            writeSnapshot(index.snapshot())
            writeSourceKeys(known)
            added
        }
    }

    /**
     * Writes `<id>.json` for [track] and folds its bbox into `index.json`.
     *
     * If the track has no points its bbox is undefined; the track file is still written (so it can
     * be round-tripped) but it is not added to the index — it can never be a spatial candidate.
     */
    fun save(track: RecordedTrack) {
        ensureDir()

        atomicWriteText(File(dir, track.id + JSON_SUFFIX), jsonForStorage.encodeToString(track))

        val bbox = BBox.around(track.points.map { LatLng(it.lat, it.lng) }) ?: return
        // Read-modify-write of the shared index must be atomic against concurrent saves/loads.
        synchronized(indexLock) {
            val newSnapshot = updatedSnapshot(readSnapshot(), track.id, bbox, INDEX_PRECISION)
            writeSnapshot(newSnapshot)
        }
    }

    /**
     * Returns the parsed tracks whose recorded bbox could overlap [routeBBox], loading only the
     * candidate `<id>.json` files. Tracks whose file is missing or unparseable are skipped.
     */
    fun loadCandidates(routeBBox: BBox): List<RecordedTrack> {
        // Snapshot the index under the lock so we never read it mid-modify (torn file → empty).
        val ids = synchronized(indexLock) { candidateIds(readSnapshot(), routeBBox, INDEX_PRECISION) }
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
        // Snapshot the index under the lock so we never read it mid-modify (torn file → empty).
        val ids = synchronized(indexLock) {
            rankCandidateIds(readSnapshot(), routeBBox, INDEX_PRECISION, maxTracks)
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

    // --- file helpers -------------------------------------------------------

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
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

    /**
     * Writes [text] to [target] atomically: serialize to a temp file in the same directory, then
     * [File.renameTo] (an atomic move on the same filesystem) so a reader never observes a
     * half-written file. If the rename fails (returns false — e.g. some exotic filesystem) we fall
     * back to a direct truncate-then-write as best effort and log the degradation via Timber.
     */
    private fun atomicWriteText(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            Timber.w("atomic rename failed for %s; falling back to direct write", target.name)
            runCatching { target.writeText(text) }
                .onFailure { Timber.w(it, "fallback direct write failed for %s", target.name) }
            tmp.delete()
        }
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

        private const val JSON_SUFFIX = ".json"
        private const val INDEX_FILE = "index.json"

        /** Stores the serialized `Set<String>` of source keys ingested via [add] (dedup state). */
        private const val SOURCEKEYS_FILE = "sourcekeys.json"

        /** Suffix for the same-dir temp file used by the atomic write-then-rename. */
        private const val TMP_SUFFIX = ".tmp"

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
