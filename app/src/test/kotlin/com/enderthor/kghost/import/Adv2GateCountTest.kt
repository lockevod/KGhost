package com.enderthor.kghost.import_

import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ADVERSARIAL PASS 2 — the number the rider reads to decide whether the rebuild worked, and the
 * all-or-nothing shape of the pre-flight refusal.
 *
 * [TrackStore]'s BOOKKEEPING_FILES doc states the stored-rides count "must not be one too high for the
 * life of the install" — MainActivity renders `allTrackIds().size`. But `HistoryImportRunner.runImport`
 * writes `gradepace.json` into that SAME directory after every import that stored anything, and that
 * filename was not in BOOKKEEPING_FILES. So from the first successful import onward the count read one
 * too high, permanently — including immediately after a rebuild, which is precisely when the rider is
 * counting to check nothing was lost. [GradePaceStore.FILE_NAME] is now in the set.
 */
class Adv2GateCountTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    @Test fun `the grade-pace model file is NOT counted as a stored ride`() {
        val dir = tmp.newFolder("N1-tracks")
        val store = TrackStore(dir)
        (1..5).forEach { store.add(decimated("fit-$it", 1_700_000_000_000L + it * 60_000L, Source.FITFILES_SCAN)) }
        assertEquals(5, store.allTrackIds().size)

        // Exactly what runImport does after any import that stored something.
        GradePaceStore(dir).save(GradePace.Builder().build())

        assertEquals(
            "MainActivity's 'stored rides' must still read 5 for a 5-ride library",
            5, TrackStore(dir).allTrackIds().size,
        )
        assertEquals(5, TrackStore(dir).allTracksMeta().size)
    }

    /**
     * The pre-flight is all-or-nothing over the WHOLE library: one missing source file out of fifty
     * disables the rebuild for every ride, with no partial mode and no override. That arithmetic is
     * deliberately NOT loosened — it is the thing standing between the rebuild and an unrecoverable
     * archive. What IS fixed is the dead end: the refusal now reports (files found, rides needing them)
     * through `onShortOfFiles`, so the screen can tell the rider how many files to put back instead of
     * "the ride files aren't all there", which retires the button for good after the ordinary workflow
     * (drop a backup folder in /sdcard/KGhost, import it, delete it to free space).
     */
    @Test fun `a refusal reports how many files are missing so the rider can act on it`() {
        val dir = tmp.newFolder("N2-tracks")
        val fits = tmp.newFolder("N2-fitfiles")
        val imp = tmp.newFolder("N2-import")
        val store = TrackStore(dir)
        (1..50).forEach { store.add(decimated("fit-$it", 1_700_000_000_000L + it * 60_000L, Source.FITFILES_SCAN)) }
        (1..50).forEach { File(fits, "src-$it.fit").writeText("x") }

        assertEquals("all 50 present -> passes", 50, prepareRebuild(dir, fits, imp))
        // Undo the archive so the library is back to 50 live tracks (the rider's next tap).
        File(dir, "archive").listFiles()!!.forEach { it.renameTo(File(dir, it.name)) }
        assertEquals(50, TrackStore(dir).allTracksMeta().size)

        File(fits, "src-50.fit").delete()
        var reported: Pair<Int, Int>? = null
        assertNull(
            "49 files -> the whole 50-ride rebuild is refused",
            prepareRebuild(dir, fits, imp) { available, tracks -> reported = available to tracks },
        )
        assertEquals(
            "the refusal must hand the screen the numbers to put on the line: 49 files, 50 rides",
            49 to 50, reported,
        )
        // And it stays refused until the rider either restores that file or loses a ride.
        assertNull(prepareRebuild(dir, fits, imp))
        File(imp, "anything.gpx").writeText("x")
        assertNotNull(
            "any unrelated file restores the count — the guard vouches for arithmetic, not for content",
            prepareRebuild(dir, fits, imp),
        )
    }
}
