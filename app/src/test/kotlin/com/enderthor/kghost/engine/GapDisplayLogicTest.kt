package com.enderthor.kghost.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GapDisplayLogicTest {

    @Test fun `neutral when within the positive epsilon`() {
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(0.0))
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(0.5))
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(-0.5))
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(0.999))
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(-0.999))
    }

    @Test fun `ahead when clearly below minus epsilon`() {
        // Mathematical sign: ahead means a negative time gap.
        assertEquals(GapStatus.AHEAD, GapDisplayLogic.gapStatus(-1.5))
        assertEquals(GapStatus.AHEAD, GapDisplayLogic.gapStatus(-90.0))
    }

    @Test fun `behind when clearly above plus epsilon`() {
        assertEquals(GapStatus.BEHIND, GapDisplayLogic.gapStatus(1.5))
        assertEquals(GapStatus.BEHIND, GapDisplayLogic.gapStatus(120.0))
    }

    @Test fun `boundary exactly at plus epsilon is behind`() {
        // abs == eps is NOT < eps, so it leaves the neutral band; positive ⇒ behind.
        assertEquals(GapStatus.BEHIND, GapDisplayLogic.gapStatus(1.0))
    }

    @Test fun `boundary exactly at minus epsilon is ahead`() {
        // abs == eps is NOT < eps, so it leaves the neutral band; negative ⇒ ahead.
        assertEquals(GapStatus.AHEAD, GapDisplayLogic.gapStatus(-1.0))
    }

    @Test fun `custom epsilon widens the neutral band`() {
        assertEquals(GapStatus.NEUTRAL, GapDisplayLogic.gapStatus(4.0, epsS = 5.0))
        assertEquals(GapStatus.BEHIND, GapDisplayLogic.gapStatus(6.0, epsS = 5.0))
        assertEquals(GapStatus.AHEAD, GapDisplayLogic.gapStatus(-6.0, epsS = 5.0))
    }
}
