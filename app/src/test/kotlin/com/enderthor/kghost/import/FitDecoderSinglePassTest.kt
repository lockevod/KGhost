package com.enderthor.kghost.import_

import com.enderthor.kghost.engine.sergi1File
import com.enderthor.kghost.geo.Source
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes the maintainer's real ride. That file is DEV-LOCAL and deliberately NOT committed — it is a
 * real GPS trace of where its owner rides — so this reads it from the repo root (or `$SERGI1_FIT`) via
 * the shared [sergi1File] loader and SKIPS cleanly when absent, exactly like the other replay tests.
 * It used to read it as a classpath resource, which is why a copy ended up tracked under
 * `test/resources`; don't reintroduce that.
 */
class FitDecoderSinglePassTest {
    private fun fixture(): File = sergi1File().also { assumeTrue("sergi1.fit present at ${it.path}", it.exists()) }

    @Test fun `decodes a valid FIT into a track with points`() {
        val track = FitDecoder.decode(fixture(), Source.FIT_IMPORT)
        assertTrue("expected a decoded track", track != null && track.points.size >= 2)
    }

    @Test fun `a truncated FIT yields null, not a throw`() {
        val full = fixture().readBytes()
        val truncated = File.createTempFile("trunc", ".fit").apply {
            writeBytes(full.copyOf(full.size / 3)); deleteOnExit()
        }
        assertNull(FitDecoder.decode(truncated, Source.FIT_IMPORT))
    }
}
