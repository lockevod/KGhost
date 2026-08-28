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
        // MOVING-TIME race clock, mirroring KGhostExtension.kt's B2 tick handler (~line 2077-2089: "MOVING-TIME
        // race clock (option B)"). Production feeds onTick a race-elapsed that FREEZES whenever the rider's
        // odometer hasn't advanced since the previous tick (riderDist <= integLastRiderDist), by pushing the
        // clock origin (moveStart) forward by the same wall-time delta. Feeding raw FIT p.timeS straight into
        // onTick — as this test used to — makes the test's clock keep running through every stop while the
        // ghost's own (odometer-gated) clock does not, so a real stop (this fixture has 2261s of dd==0, incl.
        // one ~19-minute stop) reads as ghost lag that never happened on the ground.
        var moveStart = pts.firstOrNull()?.timeS ?: 0.0
        var prevElapsedS: Double? = null
        var prevDistM = pts.firstOrNull()?.distanceM ?: 0.0
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
            val elapsedS = here.timeS
            val prevEl = prevElapsedS
            if (prevEl != null && elapsedS > prevEl && here.distanceM <= prevDistM) {
                moveStart += (elapsedS - prevEl) // odometer didn't advance: hold race-elapsed at its last value
            }
            prevElapsedS = elapsedS
            prevDistM = here.distanceM
            g.onTick(here.distanceM, here.lat, here.lng, 90.0, elapsedS - moveStart) { _, _, _ -> model.pace(grade, GhostPick.AVERAGE) }
        }

        val elapsed = track.points.last().timeS
        println("GradeFill self-replay: elapsed=${elapsed}s gapTimeS=${g.gapTimeS} coveredM=${model.coveredM}")
        // With the moving-time clock, a self-replay collapses to near a dead heat: observed gap -40.9s on
        // 8551s elapsed (-0.48%), down from -2302s (-27%) with the raw FIT clock — see task-6-report.md.
        // Tight absolute bound (~2.2x the observed magnitude) so a regression in the gradient fill that
        // reintroduces a real drift still trips this test.
        assertTrue(
            "gap ${g.gapTimeS}s must be near a dead heat (self-replay, moving-time clock)",
            kotlin.math.abs(g.gapTimeS) < 90.0,
        )
    }
}
