package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxParserTest {

    private fun resource(name: String): File =
        File(javaClass.getResource("/$name")!!.toURI())

    @Test fun `parses a valid GPX with three timed trackpoints`() {
        val track = GpxParser.parse(resource("sample.gpx"))
        assertNotNull(track)
        requireNotNull(track)

        assertEquals(3, track.points.size)
        assertEquals(Source.GPX_IMPORT, track.source)

        // 2023-11-14T22:13:20Z == 1700000000000 ms
        assertEquals(1_700_000_000_000L, track.startedAtEpoch)

        // timeS relative to the first point.
        assertEquals(0.0, track.points[0].timeS, 1e-9)
        assertEquals(20.0, track.points[1].timeS, 1e-9)
        assertEquals(40.0, track.points[2].timeS, 1e-9)

        // Cumulative distance, strictly increasing.
        assertEquals(0.0, track.points[0].distanceM, 1e-9)
        assertTrue(track.points[1].distanceM > 0.0)
        assertTrue(track.points[2].distanceM > track.points[1].distanceM)

        assertEquals(
            sourceKeyOf(track.startedAtEpoch, track.points.last().distanceM),
            track.sourceKey,
        )
    }

    @Test fun `returns null when any trackpoint lacks a time`() {
        assertNull(GpxParser.parse(resource("sample_notime.gpx")))
    }

    @Test fun `returns null for a single-trackpoint GPX (parity with FitDecoder's two-point rule)`() {
        assertNull(GpxParser.parse(resource("sample_single.gpx")))
    }
}
