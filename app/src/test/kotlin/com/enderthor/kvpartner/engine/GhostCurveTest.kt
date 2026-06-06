package com.enderthor.kvpartner.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhostCurveTest {
    private val curve = GhostCurve(listOf(
        GhostSample(0.0, 0.0),
        GhostSample(1000.0, 200.0),   // 5 m/s
        GhostSample(2000.0, 500.0),   // 3.33 m/s (más lento)
    ))

    @Test fun `timeAt interpola linealmente dentro del tramo`() {
        assertEquals(100.0, curve.timeAt(500.0), 1e-6)    // mitad del primer tramo
        assertEquals(350.0, curve.timeAt(1500.0), 1e-6)   // mitad del segundo tramo
    }

    @Test fun `distanceAt es la inversa`() {
        assertEquals(500.0, curve.distanceAt(100.0), 1e-6)
        assertEquals(1500.0, curve.distanceAt(350.0), 1e-6)
    }

    @Test fun `timeAt clampa fuera de rango por arriba`() {
        assertEquals(500.0, curve.timeAt(9999.0), 1e-6)
    }

    @Test fun `distanceAt clampa fuera de rango por abajo`() {
        assertEquals(0.0, curve.distanceAt(-5.0), 1e-6)
    }

    @Test fun `totales correctos`() {
        assertEquals(2000.0, curve.totalDistanceM, 1e-6)
        assertEquals(500.0, curve.totalTimeS, 1e-6)
    }

    @Test fun `rechaza muestras no monotonas`() {
        assertThrows(IllegalArgumentException::class.java) {
            GhostCurve(listOf(GhostSample(0.0, 0.0), GhostSample(100.0, 50.0), GhostSample(50.0, 80.0)))
        }
    }

    @Test fun `rechaza lista vacia o de un punto`() {
        assertThrows(IllegalArgumentException::class.java) { GhostCurve(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GhostCurve(listOf(GhostSample(0.0, 0.0))) }
    }
}
