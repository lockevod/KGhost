package com.enderthor.kghost

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileLogTree.isLoggable] is the cheap gate Timber consults BEFORE it formats a log's
 * arguments and dispatches to [FileLogTree.log]. With file logging off (the rider default) it must
 * return false so Timber short-circuits the whole pipeline for this tree — no arg formatting, no
 * buffer touch — for every per-tick diagnostic call across a multi-hour ride.
 */
class FileLogTreeTest {

    @After fun tearDown() {
        FileLogTree.enabled = false
    }

    @Test fun `isLoggable is false for every priority when file logging is disabled`() {
        FileLogTree.enabled = false
        // 2=VERBOSE .. 7=ASSERT — none should be loggable when off.
        for (priority in 2..7) {
            assertFalse("priority $priority", FileLogTree.isLoggable(null, priority))
        }
    }

    @Test fun `isLoggable is true when file logging is enabled`() {
        FileLogTree.enabled = true
        assertTrue(FileLogTree.isLoggable(null, 3 /* DEBUG */))
        assertTrue(FileLogTree.isLoggable("KGhost", 2 /* VERBOSE */))
    }
}
