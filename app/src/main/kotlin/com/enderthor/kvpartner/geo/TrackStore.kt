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
     * Serializes the `index.json` read→modify→write in [save] against the read in [loadCandidates]
     * so two overlapping saves can't lose a track from the index and a load can't observe the index
     * mid-modify. The per-track `<id>.json` writes are already isolated (distinct files); only the
     * shared index needs guarding.
     */
    private val indexLock = Any()

    /** Reads all stored track ids by listing the `<id>.json` files (excludes `index.json`). */
    fun allTrackIds(): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(JSON_SUFFIX) && it.name != INDEX_FILE }
            .map { it.name.removeSuffix(JSON_SUFFIX) }
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
        return ids.mapNotNull { id ->
            val f = File(dir, id + JSON_SUFFIX)
            if (!f.isFile) return@mapNotNull null
            runCatching { jsonWithUnknownKeys.decodeFromString<RecordedTrack>(f.readText()) }
                .getOrNull()
        }
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
        private const val JSON_SUFFIX = ".json"
        private const val INDEX_FILE = "index.json"

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
    }
}
