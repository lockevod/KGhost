package com.enderthor.kghost.datatype

import com.enderthor.kghost.engine.GapStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [fmtTime]: the M:SS / H:MM:SS rollover and the sign/neutral rules.
 *
 * Note the engine's mathematical sign convention: ahead ⇒ gapTimeS negative. The display flips it,
 * so a negative gapTimeS classified AHEAD renders with a leading "+", and a positive gapTimeS
 * classified BEHIND renders with a leading "-".
 */
class FmtTimeTest {

    @Test fun `under one hour renders M colon SS`() {
        // 90 s ahead → "+1:30".
        assertEquals("+1:30", fmtTime(-90.0, GapStatus.AHEAD))
        // 90 s behind → "-1:30".
        assertEquals("-1:30", fmtTime(90.0, GapStatus.BEHIND))
        // Just under an hour stays M:SS (no rollover): 3599 s → "59:59".
        assertEquals("+59:59", fmtTime(-3599.0, GapStatus.AHEAD))
    }

    @Test fun `one hour or more rolls over to H colon MM colon SS`() {
        // Exactly one hour ahead → "+1:00:00".
        assertEquals("+1:00:00", fmtTime(-3600.0, GapStatus.AHEAD))
        // 1 h 30 m behind → "-1:30:00".
        assertEquals("-1:30:00", fmtTime(5400.0, GapStatus.BEHIND))
        // 2 h 03 m 05 s ahead → "+2:03:05".
        assertEquals("+2:03:05", fmtTime(-7385.0, GapStatus.AHEAD))
    }

    @Test fun `neutral renders with no sign`() {
        assertEquals("0:00", fmtTime(0.0, GapStatus.NEUTRAL))
        // Neutral rollover also carries no sign.
        assertEquals("1:00:00", fmtTime(3600.0, GapStatus.NEUTRAL))
    }

    @Test fun `non-finite renders the neutral placeholder`() {
        assertEquals(GAP_PLACEHOLDER, fmtTime(Double.NaN, GapStatus.AHEAD))
        assertEquals(GAP_PLACEHOLDER, fmtTime(Double.POSITIVE_INFINITY, GapStatus.BEHIND))
    }
}
