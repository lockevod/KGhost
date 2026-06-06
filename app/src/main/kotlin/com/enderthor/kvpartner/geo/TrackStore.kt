package com.enderthor.kvpartner.geo

import com.enderthor.kvpartner.extension.jsonForStorage
import com.enderthor.kvpartner.extension.jsonWithUnknownKeys
import kotlinx.serialization.encodeToString
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

        File(dir, track.id + JSON_SUFFIX)
            .writeText(jsonForStorage.encodeToString(track))

        val bbox = BBox.around(track.points.map { LatLng(it.lat, it.lng) }) ?: return
        val newSnapshot = updatedSnapshot(readSnapshot(), track.id, bbox, INDEX_PRECISION)
        writeSnapshot(newSnapshot)
    }

    /**
     * Returns the parsed tracks whose recorded bbox could overlap [routeBBox], loading only the
     * candidate `<id>.json` files. Tracks whose file is missing or unparseable are skipped.
     */
    fun loadCandidates(routeBBox: BBox): List<RecordedTrack> {
        val ids = candidateIds(readSnapshot(), routeBBox, INDEX_PRECISION)
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
        if (!f.isFile) return emptyMap()
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<Map<String, Set<String>>>(f.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeSnapshot(snapshot: Map<String, Set<String>>) {
        ensureDir()
        File(dir, INDEX_FILE).writeText(jsonForStorage.encodeToString(snapshot))
    }

    companion object {
        private const val JSON_SUFFIX = ".json"
        private const val INDEX_FILE = "index.json"

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
