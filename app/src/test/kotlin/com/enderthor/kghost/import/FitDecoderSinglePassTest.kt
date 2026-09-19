package com.enderthor.kghost.import_

import com.enderthor.kghost.engine.sergi1File
import com.enderthor.kghost.geo.Source
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import com.garmin.fit.File as FitFileType

/**
 * Single-pass decode: the integrity re-read was dropped, so a corrupt/partial file must be caught by
 * the one decode pass rather than by a prior validation sweep.
 *
 * The TRUNCATION guard is the regression lock for that change and runs UNCONDITIONALLY, on a FIT this
 * test encodes itself. It used to read a real ride committed under `test/resources`; that file is a
 * GPS trace of where its owner lives and is no longer in the repo, and simply repointing the guard at
 * the now-private copy would have made the only test of that path skip silently on every machine but
 * the maintainer's — with no CI to notice. Synthesising the file keeps the lock real everywhere.
 *
 * The real ride is still decoded (below) because a hand-built file cannot stand in for the quirks of
 * one written by an actual Karoo; that case alone skips when the fixture is absent.
 */
class FitDecoderSinglePassTest {

    @Test fun `a truncated FIT yields null, not a throw`() {
        val full = writeMinimalFit().readBytes()
        val truncated = File.createTempFile("trunc", ".fit").apply {
            writeBytes(full.copyOf(full.size / 3)); deleteOnExit()
        }
        assertNull(FitDecoder.decode(truncated, Source.FIT_IMPORT))
    }

    @Test fun `the synthetic FIT this test truncates is itself valid`() {
        // Guards the guard: if the encoder ever produced something undecodable, the truncation test
        // above would pass for the wrong reason (null from a file that was never valid to begin with).
        val track = FitDecoder.decode(writeMinimalFit(), Source.FIT_IMPORT)
        assertNotNull("the synthetic fixture must decode", track)
        assertTrue(track!!.points.size >= 2)
    }

    @Test fun `decodes a real ride into a track with points`() {
        val f = sergi1File()
        assumeTrue("sergi1.fit present at ${f.path} (dev-local, not committed)", f.exists())
        val track = FitDecoder.decode(f, Source.FIT_IMPORT)
        assertTrue("expected a decoded track", track != null && track.points.size >= 2)
    }

    /** A minimal but valid cycling activity FIT: 200 positioned records, same shape as the one
     *  [FitSportGateTest] encodes. Truncating this to a third cuts mid-record, which is exactly the
     *  partially-written file the single-pass decoder has to survive. */
    private fun writeMinimalFit(): File {
        val out = File.createTempFile("singlepass", ".fit").apply { deleteOnExit() }
        val startMs = 1_730_053_187_000L
        val enc = FileEncoder(out, Fit.ProtocolVersion.V2_0)
        enc.write(
            FileIdMesg().apply {
                type = FitFileType.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                serialNumber = 1L
                timeCreated = DateTime(java.util.Date(startMs))
            },
        )
        for (i in 0 until 200) {
            val d = i * 20.0
            enc.write(
                RecordMesg().apply {
                    timestamp = DateTime(java.util.Date(startMs + (d / 5.0 * 1000).toLong()))
                    positionLat = (41.4 * (1L shl 31) / 180.0).toInt()
                    positionLong = ((2.1 + i * 0.0002) * (1L shl 31) / 180.0).toInt()
                    distance = d.toFloat()
                    altitude = (100.0 + d * 0.06).toFloat()
                },
            )
        }
        enc.write(SessionMesg().apply { sport = Sport.CYCLING })
        enc.close()
        return out
    }
}
