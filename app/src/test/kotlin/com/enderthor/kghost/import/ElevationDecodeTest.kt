package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.TrackPoint
import com.enderthor.kghost.geo.toDto
import com.enderthor.kghost.geo.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ElevationDecodeTest {

    private fun gpxFile(content: String): File =
        File.createTempFile("elevation", ".gpx").apply {
            deleteOnExit()
            writeText(content)
        }

    @Test fun `elevation survives the dto round-trip`() {
        val p = TrackPoint(lat = 41.4, lng = 2.1, distanceM = 100.0, timeS = 10.0, eleM = 235.5)
        assertEquals(235.5, p.toDto().toModel().eleM!!, 1e-9)
    }

    @Test fun `a point with no elevation stays null through the round-trip`() {
        val p = TrackPoint(lat = 41.4, lng = 2.1, distanceM = 100.0, timeS = 10.0)
        assertNull(p.toDto().toModel().eleM)
    }

    @Test fun `a gpx trkpt with ele is parsed into eleM`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="41.4000" lon="2.1000"><ele>100.0</ele><time>2024-01-01T10:00:00Z</time></trkpt>
              <trkpt lat="41.4010" lon="2.1000"><ele>112.5</ele><time>2024-01-01T10:00:30Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val track = GpxParser.parse(gpxFile(gpx))!!
        assertEquals(100.0, track.points.first().eleM!!, 1e-6)
        assertEquals(112.5, track.points.last().eleM!!, 1e-6)
    }

    @Test fun `a gpx trkpt without ele parses with a null elevation`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="41.4000" lon="2.1000"><time>2024-01-01T10:00:00Z</time></trkpt>
              <trkpt lat="41.4010" lon="2.1000"><time>2024-01-01T10:00:30Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val track = GpxParser.parse(gpxFile(gpx))!!
        assertNull(track.points.first().eleM)
    }
}
