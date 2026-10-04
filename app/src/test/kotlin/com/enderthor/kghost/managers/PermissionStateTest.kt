package com.enderthor.kghost.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test
    fun `permission is re-checked at most once per ttl`() {
        var now = 0L
        var calls = 0
        val s = PermissionState(check = { calls++; false }, nowMs = { now })
        repeat(3) { assertTrue(s.missing()); now += 1_000 }
        assertEquals(1, calls)
        now = 5_000
        s.missing()
        assertEquals(2, calls)
    }

    @Test
    fun `a grant is seen after the ttl`() {
        var now = 0L
        var granted = false
        val s = PermissionState(check = { granted }, nowMs = { now })
        assertTrue(s.missing())
        granted = true
        now = 5_000
        assertFalse(s.missing())
    }

    @Test
    fun `preview never renders the permission notice`() {
        assertFalse(permissionNotice(missing = true, preview = true))
        assertTrue(permissionNotice(missing = true, preview = false))
        assertFalse(permissionNotice(missing = false, preview = false))
    }
}
