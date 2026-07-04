package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.Source
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FitDecoderSinglePassTest {
    private fun resource(name: String): File =
        File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test fun `decodes a valid FIT into a track with points`() {
        val track = FitDecoder.decode(resource("sergi1.fit"), Source.FIT_IMPORT)
        assertTrue("expected a decoded track", track != null && track.points.size >= 2)
    }

    @Test fun `a truncated FIT yields null, not a throw`() {
        val full = resource("sergi1.fit").readBytes()
        val truncated = File.createTempFile("trunc", ".fit").apply {
            writeBytes(full.copyOf(full.size / 3)); deleteOnExit()
        }
        assertNull(FitDecoder.decode(truncated, Source.FIT_IMPORT))
    }
}
