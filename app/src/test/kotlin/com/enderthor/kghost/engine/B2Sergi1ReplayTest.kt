package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.import_.FitDecoder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

/**
 * Regression proof for MUST-RESOLVE #1: the B2 gap NUMBER does NOT teleport on a reroute/shortcut.
 *
 * The 1D route-distance race jumped `routeDist` +1549 m in one tick at Sergi1's km-20 shortcut (→ a
 * +2953 m frozen-AHEAD garbage finish — see the memory). B2 accrues the rider's historical pace over
 * the metres ACTUALLY ridden, so by construction the gap can only move by `(pace − actualPace)·Δd`
 * per tick — bounded by the metres ridden that tick. This drives the REAL Sergi1 trace (the same FIT
 * that broke the 1D, INCLUDING the shortcut) through the actual [GhostIntegrator] + [PacePatch] and
 * asserts no single-tick teleport in either the distance or time gap.
 *
 * Skips cleanly if `sergi1.fit` is absent (it is gitignored / dev-local). Pull it to the repo root:
 *   adb pull /sdcard/FitFiles/<ride>.fit /Users/sergi/AndroidStudioProjects/KGhost/sergi1.fit
 */
class B2Sergi1ReplayTest {

    private fun sergi1OrSkip() =
        File(System.getenv("SERGI1_FIT") ?: "/Users/sergi/AndroidStudioProjects/KGhost/sergi1.fit")
            .also { assumeTrue("sergi1.fit present at ${it.path}", it.exists()) }

    @Test fun `the gap never teleports across Sergi1's shortcut`() {
        val track = FitDecoder.decode(sergi1OrSkip(), Source.FIT_IMPORT)
        assumeTrue("sergi1.fit decoded", track != null)
        requireNotNull(track)
        assertTrue("need a real ride", track.points.size > 100)

        // Historical pace = the ride itself (the ghost rides the rider's own path at their own pace);
        // the point of this test is that the SHORTCUT can't teleport the gap regardless of the source.
        val patch = PacePatch.build(listOf(track))
        // VP fill at 12 km/h (the product default) for any novel ground.
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 1.0 / (12_000.0 / 3600.0), decimateM = 20.0)

        var prevGapD = 0.0
        var prevGapT = 0.0
        var prevT = 0.0
        var maxDGap = 0.0
        var maxTGapMoving = 0.0
        var seeded = false
        val pts = track.points
        for (i in pts.indices) {
            val p = pts[i]
            val brg = if (i > 0) {
                Polyline.bearingDeg(LatLng(pts[i - 1].lat, pts[i - 1].lng), LatLng(p.lat, p.lng))
            } else {
                0.0
            }
            g.onTick(p.distanceM, p.lat, p.lng, brg, p.timeS) { la, ln, b -> patch.pace(la, ln, b, GhostPick.AVERAGE) }
            if (seeded) {
                // gapDistM can NEVER teleport — checked on EVERY tick (even across a pause, where Δd≈0
                // and the distance gap simply doesn't move). This is the exact metric the 1D blew up.
                maxDGap = maxOf(maxDGap, kotlin.math.abs(g.gapDistM - prevGapD))
                // gapTimeS: only check NORMAL moving ticks. A large Δt is a PAUSE the FIT collapsed into
                // one step (auto-pause drops stationary points, leaving an ~18-min timestamp gap); on a
                // real ride RideState.Paused freezes ELAPSED_TIME so the tick never sees that jump. Those
                // pause steps are the FIT replay's artifact, not a B2 teleport, so they're excluded.
                if (p.timeS - prevT <= 10.0) {
                    maxTGapMoving = maxOf(maxTGapMoving, kotlin.math.abs(g.gapTimeS - prevGapT))
                }
            }
            prevGapD = g.gapDistM
            prevGapT = g.gapTimeS
            prevT = p.timeS
            seeded = true
        }

        println(
            "B2 Sergi1 replay: ticks=${pts.size} finalGapD=${"%.1f".format(g.gapDistM)}m " +
                "finalGapT=${"%.1f".format(g.gapTimeS)}s maxOneTickΔgapD=${"%.1f".format(maxDGap)}m " +
                "maxOneTickΔgapT(moving)=${"%.1f".format(maxTGapMoving)}s",
        )
        // No teleport: a real 1 Hz bike tick advances a few metres — cap the one-tick DISTANCE jump well
        // under the 1D's +1549 m (150 m allows a GPS-dropout coast snap but rejects a route-pass teleport).
        // On the actual Sergi1 trace (shortcut included) this is ~2-3 m; the 1D blew far past it.
        assertTrue("gap-distance teleported by ${"%.0f".format(maxDGap)}m (1D did +1549m)", maxDGap < 150.0)
        // The TIME gap on a normal moving tick can only move by (pace − actualPace)·Δd — a few seconds.
        assertTrue("gap-time (moving) teleported by ${"%.0f".format(maxTGapMoving)}s", maxTGapMoving < 30.0)
        assertTrue("final gap finite", g.gapDistM.isFinite() && g.gapTimeS.isFinite())
    }
}
