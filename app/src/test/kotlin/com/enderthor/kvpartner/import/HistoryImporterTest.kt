package com.enderthor.kvpartner.import_

import com.enderthor.kvpartner.geo.RecordedTrack
import com.enderthor.kvpartner.geo.Source
import com.enderthor.kvpartner.geo.TrackPointDto
import com.enderthor.kvpartner.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, sourceKey: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.001, 0.001, 100.0, 10.0),
        ),
        sourceKey = sourceKey,
        source = Source.FIT_IMPORT,
    )

    private fun touch(dir: File, name: String): File =
        File(dir, name).apply { writeText("") }

    @Test fun `imports unique, skips duplicate, counts failure`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        // One unique track from fitFilesDir; one duplicate (.fit in importDir); one null (.gpx).
        val uniqueFit = touch(fitFilesDir, "unique.fit")
        val dupFit = touch(importDir, "dup.fit")
        val badGpx = touch(importDir, "bad.gpx")

        val store = TrackStore(tracksDir)

        val fitDecode: (File, Source) -> RecordedTrack? = { f, _ ->
            when (f.name) {
                "unique.fit" -> track("unique", "key-A")
                "dup.fit" -> track("dup", "key-A") // same sourceKey → duplicate
                else -> null
            }
        }
        val gpxParse: (File) -> RecordedTrack? = { f ->
            when (f.name) {
                "bad.gpx" -> null // failure
                else -> null
            }
        }

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = fitDecode,
            gpxParse = gpxParse,
            lastScanProvider = { 0L },
            lastScanSetter = {},
            nowProvider = { 42L },
        )

        val emissions = importer.import(onlyNew = false).toList()
        val last = emissions.last()

        assertEquals(ImportProgress.Phase.DONE, last.phase)
        assertEquals(3, last.total)
        assertEquals(1, last.imported)
        assertEquals(1, last.skippedDuplicates)
        assertEquals(1, last.failed)

        // sanity: the unique track was actually persisted.
        assertEquals(listOf("unique"), store.allTrackIds())
    }

    @Test fun `onlyNew with future lastScan processes nothing`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")

        touch(fitFilesDir, "a.fit")
        touch(importDir, "b.fit")
        touch(importDir, "c.gpx")

        val store = TrackStore(tracksDir)

        val importer = HistoryImporter(
            fitFilesDir = fitFilesDir,
            importDir = importDir,
            trackStore = store,
            decimate = { it },
            fitDecode = { _, _ -> track("x", "k") },
            gpxParse = { track("y", "k2") },
            // lastScan in the far future → every file's lastModified() is older → filtered out.
            lastScanProvider = { Long.MAX_VALUE },
            lastScanSetter = {},
            nowProvider = { 7L },
        )

        val emissions = importer.import(onlyNew = true).toList()
        val last = emissions.last()

        assertEquals(ImportProgress.Phase.DONE, last.phase)
        assertEquals(0, last.total)
        assertEquals(0, last.imported)
        assertEquals(0, last.skippedDuplicates)
        assertEquals(0, last.failed)
    }
}
