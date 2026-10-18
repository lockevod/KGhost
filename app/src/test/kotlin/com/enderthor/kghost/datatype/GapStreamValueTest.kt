package com.enderthor.kghost.datatype

import com.enderthor.kghost.engine.GapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GapStreamValueTest {

    private fun state(distM: Double, active: Boolean = true) =
        GapState.inactive().copy(gapTimeS = -12.0, gapDistanceM = distM, active = active)

    @Test fun `finite value is streamed`() {
        assertEquals(60.0, GapStreamDataType.streamValue(state(60.0)) { it.gapDistanceM }!!, 0.0)
        assertEquals(12.0, GapStreamDataType.streamValue(state(60.0)) { -it.gapTimeS }!!, 0.0)
    }

    @Test fun `non-finite distance is not streamed`() {
        assertNull(GapStreamDataType.streamValue(state(Double.NaN)) { it.gapDistanceM })
    }

    @Test fun `inactive state is not streamed`() {
        assertNull(GapStreamDataType.streamValue(state(60.0, active = false)) { it.gapDistanceM })
    }
}
