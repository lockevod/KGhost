package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackStoreTidyTest {
    @get:Rule val tmp = TemporaryFolder()

    // identical eastbound ride; vary id/epoch/time
    private fun ride(id: String, epoch: Long, time: Double) = RecordedTrack(
        id, epoch,
        (0..40).map { i -> TrackPointDto(41.0, 2.0 + i * 0.0015, i * 125.0, time * (i / 40.0)) },
        sourceKey = "k:$id",
    )

    @Test fun `tidyGroup keeps fastest plus two latest of the new ride's twins`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.add(ride("a", 1, 800.0))    // slow, old → loser
        store.add(ride("b", 2, 500.0))    // fastest → keep
        store.add(ride("c", 3, 650.0))    // loser
        store.add(ride("d", 4, 700.0))    // 2nd latest → keep
        val newest = ride("e", 5, 690.0); store.add(newest)  // latest → keep

        val archived = store.tidyGroup(newest)
        assertEquals(2, archived)
        assertEquals(setOf("b", "d", "e"), store.allTrackIds().toSet())
    }

    @Test fun `tidyGroup is idempotent`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        listOf("a" to 800.0, "b" to 500.0, "c" to 650.0, "d" to 700.0).forEachIndexed { i, (id, t) ->
            store.add(ride(id, (i + 1).toLong(), t))
        }
        val newest = ride("e", 5, 690.0); store.add(newest)
        store.tidyGroup(newest)
        assertEquals(0, store.tidyGroup(newest))
    }

    @Test fun `sweep cleans the whole backlog`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        // 6 twins; r1 is the fastest (early ride), r5/r6 are the two latest → keep {r1, r5, r6}.
        val times = listOf(500.0, 700.0, 700.0, 700.0, 700.0, 700.0)
        (1..6).forEach { store.add(ride("r$it", it.toLong(), times[it - 1])) }
        val n = store.sweep()
        assertEquals(3, n)                         // archive r2, r3, r4
        assertEquals(setOf("r1", "r5", "r6"), store.allTrackIds().toSet())
    }

    @Test fun `sweep skips a library over the hard cap`() {
        val store = TrackStore(tmp.newFolder("tracks"))
        store.add(ride("a", 1, 600.0))
        assertEquals(0, store.sweep(maxTracks = 0))   // cap 0 ⇒ skip
        assertEquals(1, store.allTrackIds().size)
    }
}
