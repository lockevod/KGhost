package com.enderthor.kvpartner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigDataTest {
    @Test fun `kmh to ms`() { assertEquals(5.0, kmhToMs(18.0), 1e-6) }
    @Test fun `pace minkm to ms`() { assertEquals(5.0, paceMinKmToMs(3.3333333), 1e-3) } // 3:20/km ≈ 5 m/s
    @Test fun `config without target is not valid`() {
        assertNull(KVPartnerConfig(targetSpeedMs = 0.0).validTargetOrNull())
    }
    @Test fun `config with target is valid`() {
        assertEquals(5.0, KVPartnerConfig(targetSpeedMs = 5.0).validTargetOrNull()!!, 1e-6)
    }
}
