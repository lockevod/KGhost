package com.enderthor.kghost.geo

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.reconcileOwed
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.cancellation.CancellationException

class LibraryPrimitivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun track(id: String, lat: Double) = RecordedTrack(
        id = id, startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(lat, 2.0, 0.0, 0.0),
            TrackPointDto(lat + 0.001, 2.001, 100.0, 20.0),
            TrackPointDto(lat + 0.002, 2.002, 200.0, 40.0),
        ),
    )

    private fun threeTrackStore(): TrackStore = TrackStore(tmp.newFolder("tracks")).also {
        it.save(track("A", 40.0)); it.save(track("B", 41.0)); it.save(track("C", 42.0))
    }

    /** Throws on its 2nd call, counting calls. */
    private class CancelOnSecond {
        var calls = 0
        fun check() { if (++calls == 2) throw CancellationException("stop") }
    }

    @Test fun `model save reports a failed write`() {
        val model = GradePace.build(
            listOf(
                RecordedTrack(
                    id = "a", startedAtEpoch = 1L,
                    points = (0 until 201).map { TrackPointDto(41.4, 2.1, it * 20.0, it * 2.0, 0.0) },
                )
            )
        )
        assertFalse(GradePaceStore(tmp.newFile("notADir")).save(model))
        val dir = tmp.newFolder("ok")
        assertTrue(GradePaceStore(dir).save(model))
        assertNotNull(GradePaceStore(dir).load()?.pace(0.0, GhostPick.AVERAGE))
    }

    @Test fun `forEachTrack stops at the cancel check`() {
        val store = threeTrackStore()
        val c = CancelOnSecond()
        var actions = 0
        try {
            store.forEachTrack(checkCancel = c::check) { actions++ }
            fail("expected CancellationException")
        } catch (_: CancellationException) {}
        assertEquals(1, actions)
    }

    @Test fun `sweep stops at the cancel check`() {
        val store = threeTrackStore()
        val c = CancelOnSecond()
        try {
            store.sweep(checkCancel = c::check)
            fail("expected CancellationException")
        } catch (_: CancellationException) {}
        assertEquals(2, c.calls)
    }

    @Test fun `new config fields default and round-trip`() {
        val d = KGhostConfig()
        assertEquals(listOf(false, 0L, 0L, 0), listOf(d.gradeModelDirty, d.reconcileGen, d.reconcileAckGen, d.pendingFoundRides))
        assertFalse(d.reconcileOwed())

        val c = KGhostConfig(gradeModelDirty = true, reconcileGen = 5, reconcileAckGen = 3, pendingFoundRides = 7)
        val back = jsonWithUnknownKeys.decodeFromString(KGhostConfig.serializer(), jsonForStorage.encodeToString(KGhostConfig.serializer(), c))
        assertEquals(c, back)
        assertTrue(back.reconcileOwed())
    }
}
