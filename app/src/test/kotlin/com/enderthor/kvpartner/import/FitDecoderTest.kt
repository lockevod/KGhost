package com.enderthor.kvpartner.import_

import com.enderthor.kvpartner.geo.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FitDecoderTest {

    /** Loads the gitignored real-ride fixture, or skips the test if it is absent. */
    private fun fixtureOrSkip(): File {
        val url = javaClass.getResource("/sample.fit")
        assumeTrue("sample.fit fixture present", url != null)
        val file = File(url!!.toURI())
        assumeTrue("sample.fit fixture present", file.exists())
        return file
    }

    @Test fun `decodes the real ride fixture`() {
        val file = fixtureOrSkip()
        val track = FitDecoder.decode(file, Source.FIT_IMPORT)

        // One-time discovery print of the pinned values.
        println("FIT points=${track?.points?.size} startedAtEpoch=${track?.startedAtEpoch}")
        println("FIT firstTimeS=${track?.points?.first()?.timeS} lastTimeS=${track?.points?.last()?.timeS}")
        println("FIT totalDistanceM=${track?.points?.last()?.distanceM}")

        assertNotNull(track)
        requireNotNull(track)

        assertEquals(Source.FIT_IMPORT, track.source)
        assertTrue(track.points.size >= 2)
        assertTrue(track.startedAtEpoch > 0)

        // First point is the distance origin and the time origin.
        assertEquals(0.0, track.points.first().distanceM, 1e-9)
        assertEquals(0.0, track.points.first().timeS, 1e-9)

        // Distance is monotonic non-decreasing.
        for (i in 1 until track.points.size) {
            assertTrue(track.points[i].distanceM >= track.points[i - 1].distanceM)
        }
        // Time is monotonic non-decreasing.
        for (i in 1 until track.points.size) {
            assertTrue(track.points[i].timeS >= track.points[i - 1].timeS)
        }

        assertEquals(
            sourceKeyOf(track.startedAtEpoch, track.points.last().distanceM),
            track.sourceKey,
        )

        // --- Pinned values (decoded once from the real Karoo ride fixture) ---
        // points=403, startedAtEpoch=1730053187000 (2024-10-27T18:19:47Z),
        // lastTimeS=743.0, totalDistanceM=546.6900024414062 m.
        assertEquals(403, track.points.size)
        assertEquals(1_730_053_187_000L, track.startedAtEpoch)
        assertEquals(0.0, track.points.first().timeS, 1e-9)
        assertEquals(743.0, track.points.last().timeS, 1e-9)
        assertEquals(546.69, track.points.last().distanceM, 546.69 * 0.01) // within +/-1%
    }

    @Test fun `passes the source through`() {
        val file = fixtureOrSkip()
        val track = FitDecoder.decode(file, Source.FITFILES_SCAN)
        assertNotNull(track)
        requireNotNull(track)
        assertEquals(Source.FITFILES_SCAN, track.source)
    }

    @Test fun `corrupt bytes yield null not throw`() {
        val garbage = File.createTempFile("garbage", ".fit").apply {
            deleteOnExit()
            writeBytes(ByteArray(256) { (it * 7 + 3).toByte() })
        }
        assertNull(FitDecoder.decode(garbage, Source.FIT_IMPORT))
    }
}
