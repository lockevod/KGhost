package com.enderthor.kghost.datatype

import com.enderthor.kghost.data.GapDisplay
import com.enderthor.kghost.engine.GapState
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GapPermissionNoticeTest {
    private val active = GapState(-30.0, 40.0, 0.0, 0.0, ahead = true, estimated = false, active = true)

    private fun key(s: GapState, notice: Boolean? = null) =
        if (notice == null) gapRenderKey(s, GapDisplay.TIME, false, dark = false, imperial = false)
        else gapRenderKey(s, GapDisplay.TIME, false, dark = false, imperial = false, permissionNotice = notice)

    @Test
    fun `the render key changes when permission is granted`() {
        for (s in listOf(active, GapState.inactive())) {
            assertNotEquals(key(s, true), key(s, false))
        }
    }

    @Test
    fun `numeric caller default is unchanged`() {
        assertEquals(key(active), key(active, false))
    }
}
