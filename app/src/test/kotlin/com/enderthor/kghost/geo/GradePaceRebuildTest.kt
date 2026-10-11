package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.extension.jsonForStorage
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The whole-history (live + archive) GradePace rebuild: dedup, fold cache, reduce parity. */
class GradePaceRebuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L
    private val day = 86_400_000L

    /** A straight eastbound ride, a point every 20 m, at [speed] m/s, flat (bin 0) when [ele]. */
    private fun ride(
        id: String,
        start: Long,
        durS: Double = 600.0,
        speed: Double = 6.0,
        ele: Boolean = true,
        source: Source = Source.FIT_IMPORT,
        key: String = "k:$id",
    ): RecordedTrack {
        val n = (durS * speed / 20.0).toInt()
        return RecordedTrack(
            id, start,
            (0..n).map { i ->
                val d = i * 20.0
                TrackPointDto(41.0, 2.0 + d / 83_000.0, d, d / speed, if (ele) 0.0 else null)
            },
            sourceKey = key,
            source = source,
        )
    }

    private fun write(dir: File, t: RecordedTrack) {
        dir.mkdirs()
        File(dir, t.id + ".json").writeText(jsonForStorage.encodeToString(t))
    }

    private fun archiveDir(dir: File) = File(dir, TrackStore.ARCHIVE_SUBDIR)
    private fun countAt0(m: GradePace) = m.toDto().bins.single { it.bin == 0 }.count
    private fun fresh(vararg ts: RecordedTrack) = GradePace.build(ts.toList())

    // --- duplicate suppression ------------------------------------------------------------------------

    @Test fun `an enriched recording and its FIT twin count once`() {
        val dir = tmp.newFolder()
        val fit = ride("fit", t0, durS = 600.0, source = Source.FITFILES_SCAN)
        // The recording's interval is ELAPSED time: same start, shorter — still >= 50 % inside the FIT's.
        val rec = ride("rec", t0 + 20_000, durS = 480.0, source = Source.RECORDED)
        write(dir, rec)
        write(archiveDir(dir), fit)
        val m = GradePaceStore(dir).rebuild().model
        assertEquals(1, countAt0(m))
        assertEquals(fresh(fit).toDto(), m.toDto())
    }

    @Test fun `the same id live and archived counts once, the copy with more metres`() {
        val dir = tmp.newFolder()
        // Epoch 0: rule (b) never suppresses it, so only the same-id rule (a) can collapse the pair.
        val small = ride("x", 0L, durS = 300.0)
        val big = ride("x", 0L, durS = 600.0)
        write(dir, small)
        write(archiveDir(dir), big)
        val m = GradePaceStore(dir).rebuild().model
        assertEquals(fresh(big).toDto(), m.toDto())
    }

    @Test fun `two laps that do not overlap stay two`() {
        val dir = tmp.newFolder()
        write(dir, ride("lap1", t0, durS = 600.0))
        write(dir, ride("lap2", t0 + 700_000, durS = 600.0))
        assertEquals(2, countAt0(GradePaceStore(dir).rebuild().model))
    }

    @Test fun `an overlapping group-ride FIT keeps only the one with more metres`() {
        val dir = tmp.newFolder()
        val mine = ride("mine", t0, speed = 6.0)
        val friend = ride("friend", t0 + 60_000, speed = 7.0) // same hour, more metres
        write(dir, mine)
        write(dir, friend)
        val r = GradePaceStore(dir).rebuild()
        assertEquals(1, r.suppressed)
        assertEquals(fresh(friend).toDto(), r.model.toDto())
    }

    @Test fun `epoch-0 identical copies under two ids both count`() {
        val dir = tmp.newFolder()
        write(dir, ride("a", 0L))
        write(dir, ride("b", 0L))
        assertEquals(2, countAt0(GradePaceStore(dir).rebuild().model))
    }

    // Pure rule (b): synthetic folds, identified by their epoch.
    private fun entry(id: String, startMin: Long, endMin: Long, metres: Double) = GradePaceStore.FoldEntry(
        GradePaceStore.LIVE, id, 0, 0, t0 + startMin * 60_000, t0 + endMin * 60_000,
        mapOf(0 to metres / 6.0), mapOf(0 to metres),
    )

    private fun survivors(vararg e: GradePaceStore.FoldEntry): Set<Long> =
        GradePaceStore.dedupe(e.toList()).first.map { (it.epoch - t0) / 60_000 }.toSet()

    // A=[08:00,09:00], B=[08:30,10:30], C=[10:00,11:00] (minutes from 08:00). A and C never overlap; B
    // overlaps exactly 50 % of each one's own duration.
    @Test fun `A-B-C with B largest - exactly half inside B is not a duplicate, all three survive`() {
        // The rule is STRICTLY more than 50 %: a real duplicate overlaps ~100 %, two rides sharing half an hour don't.
        assertEquals(setOf(0L, 30L, 120L), survivors(entry("A", 0, 60, 1000.0), entry("B", 30, 150, 3000.0), entry("C", 120, 180, 900.0)))
    }

    @Test fun `A-B-C with A then C largest - all three survive`() {
        // B overlaps only 25 % of its own 2 h with A and with C.
        assertEquals(setOf(0L, 30L, 120L), survivors(entry("A", 0, 60, 3000.0), entry("B", 30, 150, 1000.0), entry("C", 120, 180, 2000.0)))
    }

    @Test fun `A-B-C with A then B largest - all three survive at the 50 % boundary`() {
        assertEquals(setOf(0L, 30L, 120L), survivors(entry("A", 0, 60, 3000.0), entry("B", 30, 150, 2000.0), entry("C", 120, 180, 1000.0)))
    }

    @Test fun `a candidate suppressed by an accepted ride cannot suppress a third - no transitive chain`() {
        // A=[0,60] accepted; B=[20,90] is 57 % inside A -> suppressed; C=[50,110] is 67 % inside B but 17 % inside
        // A. A cluster/chain rule would lose C through B; pairwise-against-ACCEPTED keeps it.
        assertEquals(setOf(0L, 50L), survivors(entry("A", 0, 60, 3000.0), entry("B", 20, 90, 2000.0), entry("C", 50, 110, 1000.0)))
    }

    // An implausible interval (a FIT whose wall clock jumped forward, e.g. the FIT-replay simulator moving the
    // system clock; or backward, end < start) is exempt from rule (b) in BOTH directions, like epoch 0.
    @Test fun `a fold spanning months never suppresses the real rides inside its span`() {
        val dayMin = 24 * 60L
        val sim = entry("sim", 0, 140 * dayMin, 50_000.0)
        val real = (1..5).map { entry("real$it", it * 10 * dayMin, it * 10 * dayMin + 60, 1000.0 + it) }
        val r = GradePaceStore.dedupe(listOf(sim) + real)
        assertEquals(6, r.first.size)
        assertEquals(0, r.second)
    }

    @Test fun `a fold spanning more than a day is never suppressed`() {
        // A = 20 h, most metres; S = 30 h starting with it: overlap 20 h > half of S's 30 h.
        assertEquals(setOf(0L, 1L), survivors(entry("A", 0, 20 * 60, 3000.0), entry("S", 1, 30 * 60 + 1, 1000.0)))
    }

    @Test fun `a backward clock jump - end before start - neither suppresses nor is suppressed`() {
        // B's end lies before its start; it sits on top of A whichever of the two has more metres.
        assertEquals(setOf(0L, 1L), survivors(entry("A", 0, 60, 3000.0), entry("B", 1, -60, 1000.0)))
        assertEquals(setOf(0L, 1L), survivors(entry("A", 0, 60, 1000.0), entry("B", 1, -60, 3000.0)))
    }

    // --- archive included ----------------------------------------------------------------------------

    @Test fun `a pruned ride still counts - four rides give the same model before and after the sweep`() {
        val dir = tmp.newFolder()
        val store = TrackStore(dir)
        listOf(500.0, 700.0, 650.0, 690.0).forEachIndexed { i, durS ->
            store.add(ride("r$i", t0 + i * day, durS = durS, speed = 3000.0 / durS))
        }
        val before = GradePaceStore(dir).rebuild().model
        assertEquals(4, countAt0(before))
        assertTrue("fixture must prune", (store.sweep() ?: 0) > 0)
        assertTrue(archiveDir(dir).listFiles()!!.isNotEmpty())
        val after = GradePaceStore(dir).rebuild()
        assertEquals(before.toDto(), after.model.toDto())
        assertEquals("a live -> archive move reuses its fold", 0, after.parsed)
    }

    @Test fun `end to end - a recording plus its FIT twin, swept, keep the coverage`() {
        val dir = tmp.newFolder()
        val store = TrackStore(dir)
        assertTrue(store.add(ride("rec", t0, ele = false, source = Source.RECORDED, key = "rec")))
        assertTrue(store.add(ride("fit", t0 + 30_000, source = Source.FITFILES_SCAN, key = "fit")))
        val before = GradePaceStore(dir).rebuild().model.coveredM
        assertTrue(before > 0)
        assertEquals(1, store.sweep())
        assertEquals(listOf("rec"), store.allTrackIds())
        assertEquals(before, GradePaceStore(dir).rebuild().model.coveredM, 0.0)
    }

    // --- fold cache ----------------------------------------------------------------------------------

    private fun threeRides(dir: File) = (0 until 3).map { ride("r$it", t0 + it * day, speed = 5.0 + it) }
        .onEach { write(dir, it) }

    @Test fun `an unchanged file is not re-parsed`() {
        val dir = tmp.newFolder()
        threeRides(dir)
        val first = GradePaceStore(dir).rebuild()
        assertEquals(3, first.parsed)
        val second = GradePaceStore(dir).rebuild()
        assertEquals(0, second.parsed)
        assertEquals(3, second.cached)
        assertEquals(first.model.toDto(), second.model.toDto())
    }

    @Test fun `a changed mtime is re-parsed`() {
        val dir = tmp.newFolder()
        threeRides(dir)
        GradePaceStore(dir).rebuild()
        val f = File(dir, "r1.json")
        assertTrue(f.setLastModified(f.lastModified() - 10_000))
        assertEquals(1, GradePaceStore(dir).rebuild().parsed)
    }

    @Test fun `a vanished file is dropped from the model and the cache`() {
        val dir = tmp.newFolder()
        val rides = threeRides(dir)
        GradePaceStore(dir).rebuild()
        assertTrue(File(dir, "r1.json").delete())
        val r = GradePaceStore(dir).rebuild()
        assertEquals(fresh(rides[0], rides[2]).toDto(), r.model.toDto())
        assertFalse(File(dir, GradePaceStore.FOLDS_FILE_NAME).readText().contains("\"r1\""))
    }

    @Test fun `a corrupt cache is recomputed`() {
        val dir = tmp.newFolder()
        val rides = threeRides(dir)
        GradePaceStore(dir).rebuild()
        File(dir, GradePaceStore.FOLDS_FILE_NAME).writeText("{ not json")
        val r = GradePaceStore(dir).rebuild()
        assertEquals(3, r.parsed)
        assertEquals(fresh(*rides.toTypedArray()).toDto(), r.model.toDto())
    }

    @Test fun `reduce parity - a fresh Builder over the deduped tracks equals the fresh and the cached path`() {
        val dir = tmp.newFolder()
        val kept = threeRides(dir) + ride("old", t0 - 3 * day, speed = 4.0).also { write(archiveDir(dir), it) }
        write(dir, ride("dupOfR0", t0 + 1_000, speed = 4.0)) // fewer metres than r0 -> suppressed
        val expected = GradePace.Builder().apply { kept.forEach(::add) }.build().toDto()
        assertEquals(expected, GradePaceStore(dir).rebuild().model.toDto())
        assertEquals(expected, GradePaceStore(dir).rebuild().model.toDto())
    }

    @Test fun `the fold cache is never mistaken for a track`() {
        val dir = tmp.newFolder()
        threeRides(dir)
        GradePaceStore(dir).rebuild()
        assertTrue(File(dir, GradePaceStore.FOLDS_FILE_NAME).isFile)
        assertEquals(setOf("r0", "r1", "r2"), TrackStore(dir).allTrackIds().toSet())
    }
}
