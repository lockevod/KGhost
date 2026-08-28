package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.TrackPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradePaceTest {

    /** A straight track: [n] points every [stepM] metres, climbing [gradePct]%, ridden at [speedMs]. */
    private fun track(
        id: String, n: Int, stepM: Double, gradePct: Double, speedMs: Double, startEpoch: Long = 1L,
    ): RecordedTrack {
        val pts = (0 until n).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1,
                distanceM = i * stepM,
                timeS = i * stepM / speedMs,
                eleM = i * stepM * gradePct / 100.0,
            )
        }
        return RecordedTrack(id = id, startedAtEpoch = startEpoch, points = pts)
    }

    @Test fun `flat history answers a flat lookup with the flat pace`() {
        // 4 km of flat at 10 m/s = 0.1 s/m.
        val g = GradePace.build(listOf(track("a", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0)))
        assertEquals(0.1, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3)
    }

    @Test fun `a climb is answered with the climbing pace, not the flat one`() {
        // Flat at 10 m/s, 6% at 3 m/s. Both bins must keep their own pace.
        val g = GradePace.build(
            listOf(
                track("flat", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0),
                track("climb", n = 201, stepM = 20.0, gradePct = 6.0, speedMs = 3.0, startEpoch = 2L),
            )
        )
        assertEquals(0.1, g.pace(0.0, GhostPick.AVERAGE)!!, 1e-3)
        assertEquals(1.0 / 3.0, g.pace(6.0, GhostPick.AVERAGE)!!, 1e-2)
    }

    @Test fun `a gradient with no history returns null so the caller falls through to neutral`() {
        val g = GradePace.build(listOf(track("flat", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0)))
        assertNull(g.pace(-12.0, GhostPick.AVERAGE))
    }

    @Test fun `a gradient with only a sliver of history is not trusted`() {
        // 80 m at 4% — below GRADE_MIN_BIN_M, so it must not answer.
        val g = GradePace.build(listOf(track("sliver", n = 5, stepM = 20.0, gradePct = 4.0, speedMs = 5.0)))
        assertNull(g.pace(4.0, GhostPick.AVERAGE))
    }

    @Test fun `tracks with no elevation contribute nothing`() {
        val noEle = RecordedTrack(
            id = "noele", startedAtEpoch = 1L,
            points = (0 until 201).map {
                TrackPointDto(lat = 41.4 + it * 0.0001, lng = 2.1, distanceM = it * 20.0, timeS = it * 2.0)
            },
        )
        val g = GradePace.build(listOf(noEle))
        assertEquals(0.0, g.coveredM, 1e-9)
        assertNull(g.pace(0.0, GhostPick.AVERAGE))
    }

    @Test fun `BEST is clamped so it can never be more than BEST_MAX_SPEEDUP faster than the average`() {
        val g = GradePace.build(
            listOf(
                track("slow", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 5.0),
                track("fast", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 50.0, startEpoch = 2L),
            )
        )
        val avg = g.pace(0.0, GhostPick.AVERAGE)!!
        val best = g.pace(0.0, GhostPick.BEST)!!
        assertTrue("BEST $best must not beat AVERAGE $avg by more than $BEST_MAX_SPEEDUP", best >= avg / BEST_MAX_SPEEDUP - 1e-9)
    }

    @Test fun `gradient is smoothed over the window so one noisy altitude cannot invent a wall`() {
        // Flat track with a single +15 m altitude spike on one point.
        val pts = (0 until 201).map { i ->
            TrackPointDto(
                lat = 41.4 + i * 0.0001, lng = 2.1, distanceM = i * 20.0, timeS = i * 2.0,
                eleM = if (i == 100) 15.0 else 0.0,
            )
        }
        val g = GradePace.build(listOf(RecordedTrack(id = "spike", startedAtEpoch = 1L, points = pts)))
        // The spike is diluted across the window; the flat bin still holds essentially all the metres.
        assertNotNull(g.pace(0.0, GhostPick.AVERAGE))
        assertTrue(g.coveredM > 3000.0)
    }

    @Test fun `the dto round-trip preserves every bin`() {
        val g = GradePace.build(
            listOf(
                track("flat", n = 201, stepM = 20.0, gradePct = 0.0, speedMs = 10.0),
                track("climb", n = 201, stepM = 20.0, gradePct = 6.0, speedMs = 3.0, startEpoch = 2L),
            )
        )
        val back = GradePace.fromDto(g.toDto())
        assertEquals(g.pace(0.0, GhostPick.AVERAGE)!!, back.pace(0.0, GhostPick.AVERAGE)!!, 1e-9)
        assertEquals(g.pace(6.0, GhostPick.AVERAGE)!!, back.pace(6.0, GhostPick.AVERAGE)!!, 1e-9)
        assertEquals(g.coveredM, back.coveredM, 1e-9)
    }
}
