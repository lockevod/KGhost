package com.enderthor.kghost.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class LogReporterRedactTest {

    @Test
    fun `coordinates are fully redacted with a dot or a comma decimal separator`() {
        // The comma form is what a Spanish-locale Karoo logs; a dot-only pattern used to leave ",32937".
        assertEquals(
            "KVP loc: lat=• lng=• acc=7m",
            LogReporter.redactForUpload("KVP loc: lat=41,32937 lng=2,61972 acc=7m"),
        )
        assertEquals(
            "KVP loc: lat=• lng=• acc=7m",
            LogReporter.redactForUpload("KVP loc: lat=41.32937 lng=-2.61972 acc=7m"),
        )
    }

    @Test
    fun `route-relative numbers are not touched`() {
        val line = "KVP tick VP (no route): dist=18385 elapsed=3274 gap=T=-36s D=202m AHEAD speed=0,0"
        assertEquals(line, LogReporter.redactForUpload(line))
    }
}
