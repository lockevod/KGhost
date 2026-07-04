package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParallelDecodeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A minimal 2-point track with a unique id + sourceKey derived from the file name, so each
     *  decoded file maps to a distinct stored track (no accidental dedup collapsing the count). */
    private fun syntheticTrack(name: String): RecordedTrack = RecordedTrack(
        id = "id-$name",
        startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.001, 0.001, 100.0, 10.0),
        ),
        sourceKey = "key-$name",
        source = Source.FIT_IMPORT,
    )

    @Test fun `all decoded tracks are stored once regardless of worker interleaving`() = runBlocking {
        val fitDir = tmp.newFolder("FitFiles")
        // 40 empty marker files; the injected decoder maps file -> a unique track.
        repeat(40) { File(fitDir, "r$it.fit").writeText("x") }
        val tracksDir = File(tmp.newFolder("store"), "tracks")

        val importer = HistoryImporter(
            fitFilesDir = fitDir,
            importDir = tmp.newFolder("Import"),
            trackStore = TrackStore(tracksDir),
            fitDecode = { f, _ -> syntheticTrack(f.name) }, // deterministic, unique per file
            gpxParse = { null },
            decimate = { it },
            lastScanProvider = { 0L },
        )

        val progress = importer.import(onlyNew = false).toList()
        val done = progress.last()
        assertEquals(40, done.total)
        assertEquals(done.total, done.imported + done.skippedDuplicates + done.failed)
        assertEquals(40, done.imported)
        assertEquals(40, TrackStore(tracksDir).allTrackIds().size)
    }
}
