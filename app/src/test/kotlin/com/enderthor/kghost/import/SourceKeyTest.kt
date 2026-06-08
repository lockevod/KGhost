package com.enderthor.kghost.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceKeyTest {
    @Test fun `same start-minute and rounded distance give the same key`() {
        assertEquals(
            sourceKeyOf(1_700_000_000_000L, 42_000.0),
            sourceKeyOf(1_700_000_020_000L, 42_007.0),
        )
    }
    @Test fun `different minute gives a different key`() {
        assertNotEquals(sourceKeyOf(1_700_000_000_000L, 42_000.0), sourceKeyOf(1_700_000_120_000L, 42_000.0))
    }
    @Test fun `different distance bucket gives a different key`() {
        assertNotEquals(sourceKeyOf(1_700_000_000_000L, 42_000.0), sourceKeyOf(1_700_000_000_000L, 42_500.0))
    }
}
