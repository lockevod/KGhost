package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.engine.GradePace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradePaceStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(): GradePace = GradePace.build(
        listOf(
            RecordedTrack(
                id = "a", startedAtEpoch = 1L,
                points = (0 until 201).map {
                    TrackPointDto(lat = 41.4, lng = 2.1, distanceM = it * 20.0, timeS = it * 2.0, eleM = 0.0)
                },
            )
        )
    )

    @Test fun `save then load round-trips the model`() {
        val dir = tmp.newFolder()
        val store = GradePaceStore(dir)
        val m = model()
        store.save(m)
        val back = store.load()!!
        assertEquals(m.pace(0.0, GhostPick.AVERAGE)!!, back.pace(0.0, GhostPick.AVERAGE)!!, 1e-9)
    }

    @Test fun `load returns null when nothing was ever saved`() {
        assertNull(GradePaceStore(tmp.newFolder()).load())
    }

    @Test fun `a stale schema is discarded rather than mis-read`() {
        val dir = tmp.newFolder()
        File(dir, "gradepace.json").writeText("""{"schemaVersion":0,"bins":[]}""")
        assertNull(GradePaceStore(dir).load())
    }

    @Test fun `a corrupt file is discarded instead of crashing the ride`() {
        val dir = tmp.newFolder()
        File(dir, "gradepace.json").writeText("{ this is not json")
        assertNull(GradePaceStore(dir).load())
    }
}
