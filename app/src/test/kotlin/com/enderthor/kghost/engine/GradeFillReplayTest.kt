package com.enderthor.kghost.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end proof for the gradient fill (Tasks 1-5): on ground with NO local history, the ghost must
 * stay bounded instead of absurd.
 *
 * The field failure this model exists for: a route with NO local history, ridden well above the
 * 12 km/h Ghost-Pace target. The old fixed fill made the ghost hours slow (gap > the rider's own
 * elapsed time). The gradient fill must land in a plausible band instead.
 *
 * Shares [loadSergi1] with [B2Sergi1ReplayTest] rather than a second copy of the fixture loader.
 */
class GradeFillReplayTest {

    @Test fun `a novel route filled by gradient gives a plausible, bounded gap`() {
        val track = loadSergi1()
        assumeTrue("sergi1.fit carries altitude", track.points.any { it.eleM != null })

        val model = GradePace.build(listOf(track)) // the rider's own history IS the model here
        val g = GhostIntegrator(GhostPick.AVERAGE, vpTimePerM = 0.3, decimateM = 20.0)

        // Replay the same ride against a model built from it, with NO positional history (PacePatch null
        // everywhere) — so every metre is filled by gradient. A self-replay must come out near a dead heat.
        //
        // The lookup gradient MUST mirror GradePace.Builder.add()'s trailing-window walk (a monotonic
        // index `j`, advanced only while the point after it is still >= GRADE_WINDOW_M behind `here`, and
        // reset to `i` on a dropout step > DROPOUT_GAP_M) — otherwise ticks query a different bin than
        // the one their own pace trained into, and the model answers with another gradient's pace.
        val pts = track.points
        var j = 0
        for (i in pts.indices) {
            val here = pts[i]
            if (i > 0) {
                val prev = pts[i - 1]
                while (j < i - 1 && here.distanceM - pts[j + 1].distanceM >= GRADE_WINDOW_M) j++
                val stepM = here.distanceM - prev.distanceM
                if (stepM > TrackSamples.DROPOUT_GAP_M) j = i // device-off/tunnel jump: restart the window
            }
            val back = pts[j]
            val dd = here.distanceM - back.distanceM
            val grade = if (dd > 0.0) {
                val e = here.eleM
                val backEle = back.eleM
                if (e != null && backEle != null) (e - backEle) / dd * 100.0 else 0.0
            } else 0.0
            g.onTick(here.distanceM, here.lat, here.lng, 90.0, here.timeS) { _, _, _ -> model.pace(grade, GhostPick.AVERAGE) }
        }

        val elapsed = track.points.last().timeS
        println("GradeFill self-replay: elapsed=${elapsed}s gapTimeS=${g.gapTimeS} coveredM=${model.coveredM}")
        // Band: matching the lookup gradient to build()'s trailing window (above) barely moves the gap
        // (-2315.7s -> -2301.9s, i.e. ~-27% both times) — so the build/lookup window mismatch this test
        // used to blame is NOT the dominant driver. BLOCKED: see task-6-report.md for the root-cause
        // analysis (the dwell/stop time in this fixture, not the gradient model). Band left UNCHANGED
        // (not widened, not tightened) pending a maintainer decision — see the report.
        assertTrue(
            "gap ${g.gapTimeS}s must stay well inside the ride's own elapsed ${elapsed}s",
            kotlin.math.abs(g.gapTimeS) < elapsed * 0.35,
        )
    }
}
