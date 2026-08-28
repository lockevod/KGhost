package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ADVERSARIAL PASS 2 — attacks the FIT sport gate (60525eb) and its interaction with the rebuild's
 * pre-flight file count (5b9f8c0).
 *
 * The gate used to make the decode return null for a present-and-non-cycling file, and the importer maps
 * a null decode to the TRANSIENT bucket: never ledgered, always retried, always counted in the rider's
 * "N not valid". A file that decoded fully but decimated to <2 points gets the OTHER bucket, which IS
 * ledgered precisely because that verdict is deterministic. A sport rejection is every bit as
 * deterministic and got the wrong bucket.
 *
 * FIXED by [FitDecoder.notARide]: the gate's verdict comes back from `decodeForImport` as an EMPTY track,
 * which lands in the same deterministic bucket as the <2-point one and is ledgered after ONE decode.
 * (`FitDecoder.decode` still folds it to null for its other callers.) A gate that later WIDENS is picked
 * up by "Rebuild history", which deletes `processed.json`.
 *
 * The rebuild interaction is NOT fixed and is not fixable by counting: [prepareRebuild] used to justify
 * `available >= ids.size` partly on "files ... that fail to decode are counted but store no track". A file
 * that DID store a track under the previous build and is refused by the new gate is counted as available
 * while being unable to bring its track back — so the pre-flight passes and the archive strands it. The
 * KDoc now says so, and [rebuildShortfall] is what catches it after the fact (S3 below).
 */
class Adv2GateSportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pts(n: Int = 41) = (0 until n).map { i ->
        TrackPointDto(41.0 + i * 0.0002, 2.0, i * 25.0, i * 5.0)
    }

    private fun decimated(id: String, epoch: Long, source: Source): RecordedTrack =
        HistoryImporter.defaultDecimate(RecordedTrack(id, epoch, pts(), source = source))

    private fun archivedIds(dir: File) =
        (File(dir, "archive").listFiles() ?: emptyArray()).map { it.name.removeSuffix(".json") }.toSet()

    // ─────────────────────────────────────────────────────────────────────────
    // S1. A sport-gate rejection is DETERMINISTIC, so it is ledgered after one decode — same treatment
    //     as the <2-point rejection right next to it — instead of being re-decoded on every import for
    //     the life of the install and padding "N not valid" forever.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `a sport-gate rejection is decoded once and then ledgered`() = runTest {
        val fits = tmp.newFolder("S1-fitfiles")
        val dir = tmp.newFolder("S1-tracks")
        File(fits, "car-commute.fit").writeText("x")   // present + non-cycling -> gate returns null
        File(fits, "real-ride.fit").writeText("x")

        val decodes = HashMap<String, Int>()
        val ledger = File(dir, "processed.json")
        suspend fun once(): ImportProgress {
            val importer = HistoryImporter(
                fitFilesDir = fits,
                importDir = tmp.newFolder(),
                trackStore = TrackStore(dir),
                fitDecode = { f, src ->
                    decodes[f.name] = (decodes[f.name] ?: 0) + 1
                    // Exactly what FitDecoder.decodeForImport returns for a non-cycling file: the
                    // deterministic "decoded fine, not a ride" verdict, told apart from a truncated
                    // file's transient null.
                    if (f.name == "car-commute.fit") FitDecoder.notARide(src)
                    else decimated("fit-real", 1_700_000_000_000L, src)
                },
                processedLedgerFile = ledger,
            )
            return importer.import(onlyNew = false).toList().last()
        }

        val first = once()
        assertEquals(1, first.imported)
        assertEquals("the gated file is 'not valid' for this run", 1, first.failed)

        val second = once()
        // Both files are ledgered out of the work list now — the gated one included.
        assertEquals("nothing left to decode", 0, second.total)
        assertEquals("the car file was NOT decoded again", 1, decodes["car-commute.fit"])
        assertEquals("the real ride was decoded only once", 1, decodes["real-ride.fit"])

        val third = once()
        assertEquals(1, decodes["car-commute.fit"])
        assertEquals("...and it stops padding 'N not valid' on every run", 0, third.failed)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S2. Control: the sibling verdict — decoded fine, decimated to <2 points — IS ledgered, so it is
    //     decoded once and never again. This is the treatment the sport rejection should have had.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `control - the sibling under-2-point rejection is ledgered after one decode`() = runTest {
        val fits = tmp.newFolder("S2-fitfiles")
        val dir = tmp.newFolder("S2-tracks")
        File(fits, "trainer.fit").writeText("x")
        val decodes = HashMap<String, Int>()
        val ledger = File(dir, "processed.json")
        suspend fun once(): ImportProgress {
            val importer = HistoryImporter(
                fitFilesDir = fits,
                importDir = tmp.newFolder(),
                trackStore = TrackStore(dir),
                fitDecode = { f, src ->
                    decodes[f.name] = (decodes[f.name] ?: 0) + 1
                    RecordedTrack("indoor", 1_700_000_000_000L, listOf(TrackPointDto(41.0, 2.0, 0.0, 0.0)), source = src)
                },
                processedLedgerFile = ledger,
            )
            return importer.import(onlyNew = false).toList().last()
        }
        assertEquals(1, once().failed)
        once()
        assertEquals("ledgered as permanently Invalid; never decoded again", 1, decodes["trainer.fit"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S3. GUARD 1 + GUARD 3. A library imported BEFORE the sport gate holds tracks whose source files
    //     the gate now refuses. Every track has its file, so the pre-flight passes on a straight count
    //     — and the archive strands exactly those rides in archive/, which is unreachable on a Karoo
    //     without all-files access. The shortfall line does catch it (guard 4 works), but the pre-flight
    //     was supposed to be the half that prevents it, and its "undecodable files are a CUSHION"
    //     argument is false for a file that previously produced a track.
    // ─────────────────────────────────────────────────────────────────────────
    @Test fun `pre-flight PASSES on a library the new sport gate can no longer restore`() = runTest {
        val dir = tmp.newFolder("S3-tracks")
        val fits = tmp.newFolder("S3-fitfiles")
        val imp = tmp.newFolder("S3-import")
        val store = TrackStore(dir)

        // 10 rides imported by the PREVIOUS build from /sdcard/FitFiles. 3 of the source files are
        // hikes / a run / an unstopped drive home — real tracks today, refused by the gate tomorrow.
        val gated = setOf(3, 6, 9)
        val tracks = (1..10).map { decimated("fit-$it", 1_700_000_000_000L + it * 3_600_000L, Source.FITFILES_SCAN) }
        tracks.forEach { store.add(it) }
        val decode = tracks.mapIndexed { i, t -> "src-${i + 1}.fit" to t }.toMap()
        (1..10).forEach { File(fits, "src-$it.fit").writeText("x") }

        // GUARD 1: available = 10, ids = 10 -> passes on the nose.
        val archived = prepareRebuild(dir, fits, imp)
        assertNotNull("the pre-flight found a file for every track", archived)
        assertEquals(10, archived)
        assertEquals("the whole library is in archive/", 10, archivedIds(dir).size)

        // The re-import now runs the NEW decoder.
        val importer = HistoryImporter(
            fitFilesDir = fits,
            importDir = imp,
            trackStore = TrackStore(dir),
            fitDecode = { f, src ->
                val n = f.name.removePrefix("src-").removeSuffix(".fit").toInt()
                if (n in gated) FitDecoder.notARide(src) else decode[f.name]?.copy(source = src)
            },
            processedLedgerFile = File(dir, "processed.json"),
        )
        val p = importer.import(onlyNew = false).toList().last()
        assertEquals(7, p.imported)

        // Three rides now exist ONLY in archive/ — on a Karoo, off the device's reach.
        assertEquals(3, rebuildShortfall(archived!!, p.imported))
        gated.forEach {
            assertTrue("fit-$it stranded in archive/", "fit-$it" in archivedIds(dir))
        }
        assertEquals(7, TrackStore(dir).allTracksMeta().size)
    }

    // S4 (the gate's own truth table) is already locked by FitSportGateTest; not duplicated here.
    // Worth recording though: the gate is FIT-only. A car journey exported as GPX into /sdcard/KGhost
    // still reaches GradePace unchallenged — GpxParser has no sport concept and is not gated.
}
