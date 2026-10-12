package com.enderthor.kghost.extension

import com.enderthor.kghost.extension.GradeSourceGate.Action
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Test

class GradeSourceGateTest {

    private fun karoo(pct: Double) = StreamState.Streaming(
        DataPoint(DataType.Type.ELEVATION_GRADE, mapOf(DataType.Field.ELEVATION_GRADE to pct)),
    )
    private fun replay(pct: Double) = StreamState.Streaming(
        DataPoint("TYPE_EXT::ride-replay::grade", mapOf(DataType.Field.SINGLE to pct)),
    )

    @Test fun `no replay open - every Karoo sample is written as with the switch off`() {
        val gate = GradeSourceGate()
        for (s in listOf(StreamState.NotAvailable, StreamState.Searching, StreamState.Idle)) {
            assertEquals(Action.Ignore, gate.onEvent(true, s))
            assertEquals(Action.Write(3.0), gate.onEvent(false, karoo(3.0)))
        }
    }

    @Test fun `a Streaming replay owns the gradient and Karoo samples only refresh it`() {
        val gate = GradeSourceGate()
        assertEquals(Action.Write(8.4), gate.onEvent(true, replay(8.4)))
        assertEquals(Action.Refresh, gate.onEvent(false, karoo(0.2)))
    }

    @Test fun `the replay letting go clears its value so no tick can use it`() {
        for (end in listOf(StreamState.NotAvailable, StreamState.Searching, StreamState.Idle)) {
            val gate = GradeSourceGate()
            gate.onEvent(true, replay(10.0))
            gate.onEvent(false, karoo(0.0))
            assertEquals(Action.Clear, gate.onEvent(true, end))
            assertEquals(Action.Write(1.5), gate.onEvent(false, karoo(1.5)))
        }
    }
}
