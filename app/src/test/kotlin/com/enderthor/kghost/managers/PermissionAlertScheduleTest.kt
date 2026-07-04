package com.enderthor.kghost.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionAlertScheduleTest {
    private val h72 = 72L * 3600_000
    private val d10 = 10L * 24 * 3600_000

    @Test fun `first ride always fires`() {
        val out = PermissionAlertSchedule.decide(PermAlertState(0, 0L), nowEpoch = 1_000)
        assertEquals(PermAlertState(1, 1_000), out)
    }

    @Test fun `second ride within 72h stays silent`() {
        val out = PermissionAlertSchedule.decide(PermAlertState(1, 1_000), nowEpoch = 1_000 + h72 - 1)
        assertNull(out)
    }

    @Test fun `second ride after 72h fires during the initial burst`() {
        val out = PermissionAlertSchedule.decide(PermAlertState(1, 1_000), nowEpoch = 1_000 + h72)
        assertEquals(PermAlertState(2, 1_000 + h72), out)
    }

    @Test fun `after the burst the throttle grows to 10 days`() {
        // count == 3 → past the burst; 72h is no longer enough, 10 days is.
        val base = 5_000_000L
        assertNull(PermissionAlertSchedule.decide(PermAlertState(3, base), nowEpoch = base + d10 - 1))
        assertEquals(
            PermAlertState(4, base + d10),
            PermissionAlertSchedule.decide(PermAlertState(3, base), nowEpoch = base + d10),
        )
    }

    @Test fun `backward clock jump fires instead of suppressing`() {
        // now < lastFired (wall clock jumped back: GPS time correction / FIT-replay testing) —
        // elapsed is negative, which must NOT be treated as "within the throttle window".
        val out = PermissionAlertSchedule.decide(PermAlertState(1, 5_000_000L), nowEpoch = 1_000L)
        assertEquals(PermAlertState(2, 1_000L), out)
    }
}
