package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.Source
import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SportMesg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.garmin.fit.File as FitFileType

/**
 * REGRESSION LOCK for the activity-type gate (was: ATTACK 4, "the drive home", in AdvModelPoisonTest).
 *
 * The rider racks the bike, drives home over a col and only presses SAVE at home. `AGG_MAX_SPEED_MS`
 * is 30 m/s, so every metre of that drive used to import as riding: 5 km at 6% at 55 km/h made the 6%
 * bin's LAST and BEST read 23.6 km/h against a true 12.0 — +97%, and PERMANENT, because `GradePace`
 * has no recency decay. `GradePace` itself cannot defend against this (a car climbing at 6% is a
 * perfectly plausible bicycle sample), so the lock has to live here, at the import boundary.
 *
 * No real car FIT file was available, so the drive is SYNTHESISED with the SDK's own `FileEncoder`:
 * the same records, encoded twice, differing only in `session.sport`.
 */
class FitSportGateTest {

    // ------------------------------------------------------------------ the pure predicate

    @Test fun `only a present, non-cycling sport rejects a file`() {
        val act = FitFileType.ACTIVITY
        // Accepted: cycling, however it is declared.
        assertTrue(isCyclingActivity(act, setOf(Sport.CYCLING)))
        // Accepted with the sport ABSENT — some legitimate re-exports omit it and dropping real rides
        // is worse than the leak. Rejecting on missing data is NOT the rule here.
        assertTrue("a re-export with no sport must still import", isCyclingActivity(act, emptySet()))
        assertTrue("a file with no file-id type must still import", isCyclingActivity(null, emptySet()))
        // Accepted: a multisport/brick file whose bike leg is real history.
        assertTrue(isCyclingActivity(act, setOf(Sport.RUNNING, Sport.CYCLING)))

        // Rejected: present and not cycling.
        assertFalse("the drive home", isCyclingActivity(act, setOf(Sport.MOTORCYCLING)))
        assertFalse(isCyclingActivity(act, setOf(Sport.RUNNING)))
        assertFalse(isCyclingActivity(act, setOf(Sport.DRIVING)))
        // Rejected: not an activity at all (a course/route file also carries positioned records).
        assertFalse(isCyclingActivity(FitFileType.COURSE, setOf(Sport.CYCLING)))
    }

    // ------------------------------------------------------------------ end-to-end, synthetic FIT

    /**
     * Encodes a minimal but valid activity FIT: 200 positioned records climbing 6% at [speedMs].
     * A null [sessionSport]/[sportMesgSport] omits that field entirely (the "legitimate re-export" case).
     */
    private fun writeFit(
        name: String, sessionSport: Sport?, speedMs: Double, sportMesgSport: Sport? = null,
    ): File {
        val out = File.createTempFile(name, ".fit")
        out.deleteOnExit()
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
        if (sportMesgSport != null) enc.write(SportMesg().apply { sport = sportMesgSport })
        for (i in 0 until 200) {
            val d = i * 20.0
            enc.write(
                RecordMesg().apply {
                    timestamp = DateTime(java.util.Date(startMs + (d / speedMs * 1000).toLong()))
                    // ~41.4N 2.1E in semicircles, walking east so the points are distinct.
                    positionLat = (41.4 * (1L shl 31) / 180.0).toInt()
                    positionLong = ((2.1 + i * 0.0002) * (1L shl 31) / 180.0).toInt()
                    distance = d.toFloat()
                    altitude = (100.0 + d * 0.06).toFloat()
                },
            )
        }
        enc.write(SessionMesg().apply { if (sessionSport != null) sport = sessionSport })
        enc.close()
        return out
    }

    @Test fun `a cycling activity decodes and a driven one is rejected`() {
        // Same road, same records — only session.sport differs.
        val ride = FitDecoder.decode(writeFit("ride", Sport.CYCLING, speedMs = 3.3), Source.FIT_IMPORT)
        assertNotNull("a cycling activity must still import", ride)
        assertTrue(ride!!.points.size > 100)

        val drive = FitDecoder.decode(writeFit("drive", Sport.MOTORCYCLING, speedMs = 15.3), Source.FIT_IMPORT)
        assertNull("the drive home must not import as a ride", drive)

        // Also proves the SDK gotcha is handled: MesgBroadcaster's GENERIC MesgListener hands back the
        // raw unconverted Mesg, so an `is SessionMesg` check there never matches and `drive` would
        // decode. This passing means the TYPED listeners are the ones registered.
    }

    @Test fun `a file with no sport at all still imports`() {
        val noSport = FitDecoder.decode(writeFit("nosport", null, speedMs = 3.3), Source.FIT_IMPORT)
        assertNotNull("a re-export without a sport field must not be dropped", noSport)
    }

    @Test fun `a sport message alone is enough to reject`() {
        // SportMesg was present in most, but not all, of the 14 measured files — so it is read as well
        // as session, and either one saying "not a bicycle" is enough.
        val control = writeFit("sportmesg", sessionSport = null, speedMs = 15.3)
        assertNotNull("control: no sport anywhere imports", FitDecoder.decode(control, Source.FIT_IMPORT))
        // Same file, sport declared ONLY by the sport message (session omits it).
        val driven = writeFit("sportmesg-drive", sessionSport = null, speedMs = 15.3, sportMesgSport = Sport.MOTORCYCLING)
        assertNull(FitDecoder.decode(driven, Source.FIT_IMPORT))
    }
}
