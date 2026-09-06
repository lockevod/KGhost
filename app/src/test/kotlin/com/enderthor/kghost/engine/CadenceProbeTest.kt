package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe exists to settle a question a previous ride log got wrong, so its own arithmetic has to
 * be beyond doubt: a bucket boundary off by one would move a 1000 ms cadence into the "<1000" bin
 * and re-tell the same kind of lie the throttled log told.
 */
class CadenceProbeTest {

    @Test fun `the first mark establishes an origin and records no gap`() {
        val p = CadenceProbe("x")
        p.mark(10_000L)
        assertEquals("one emission seen", 1L, p.emissions)
        assertEquals("but no interval yet", 0L, p.maxGapMs)
        assertTrue("no buckets filled", p.render().endsWith("{}"))
    }

    @Test fun `gaps are the differences between marks, not the marks`() {
        val p = CadenceProbe("loc")
        listOf(1_000L, 1_200L, 1_400L, 1_600L).forEach { p.mark(it) }
        assertEquals(4L, p.emissions)
        assertEquals("3 gaps of 200 ms", 200L, p.maxGapMs)
        assertEquals("loc n=3 max=200ms {<350:3}", p.render())
    }

    @Test fun `bucket bounds are upper-EXCLUSIVE so a value lands above its own bound`() {
        val p = CadenceProbe("b")
        // 1000 is a bound: it must NOT fall in "<1000", it opens "<1500".
        p.add(999L)
        p.add(1000L)
        assertEquals("b n=2 max=1000ms {<1000:1 <1500:1}", p.render())
    }

    @Test fun `a value past the last bound lands in the overflow bucket`() {
        val p = CadenceProbe("b")
        p.add(7_999L)
        p.add(8_000L)
        p.add(60_000L)
        assertEquals("b n=3 max=60000ms {<8000:1 >=8000:2}", p.render())
    }

    @Test fun `a backwards clock is discarded, not counted as a huge gap`() {
        val p = CadenceProbe("b")
        p.add(-1L)
        assertEquals("nothing recorded", "b n=0 max=0ms {}", p.render())
    }

    @Test fun `reset clears the histogram and the origin`() {
        val p = CadenceProbe("b")
        p.mark(1_000L); p.mark(2_000L)
        p.reset()
        p.mark(50_000L) // must be treated as a fresh origin, not a 48 s gap
        assertEquals("no gap recorded after a reset", "b n=0 max=0ms {}", p.render())
        assertEquals("but the mark itself counts", 1L, p.emissions)
    }

    /**
     * The discrimination this whole exercise turned on: a 1 Hz source and a 5 s source must land in
     * visibly different buckets, so the ride log can no longer be read either way.
     */
    @Test fun `a 1 Hz stream and a 5 s stream are distinguishable in the rendered shape`() {
        val hz1 = CadenceProbe("loc")
        var t = 0L
        repeat(20) { hz1.mark(t); t += 1_000L }

        val s5 = CadenceProbe("loc")
        t = 0L
        repeat(20) { s5.mark(t); t += 5_000L }

        assertEquals("1 Hz sits in <1500", "loc n=19 max=1000ms {<1500:19}", hz1.render())
        assertEquals("5 s sits in <8000", "loc n=19 max=5000ms {<8000:19}", s5.render())
    }
}
