package com.enderthor.kvpartner.data

import com.enderthor.kvpartner.engine.GhostPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    @Test fun `migrateToLatest is identity at v1`() {
        val config = KVPartnerConfig()
        assertEquals(config, config.migrateToLatest())
    }

    @Test fun `race defaults are sane`() {
        val c = KVPartnerConfig()
        assertTrue(c.raceEnabled)            // ② on by default
        assertTrue(c.autoRecord)             // history recording on by default
        assertEquals(GhostPick.BEST, c.ghostPick)
        assertFalse(c.segmentEntryAlert)     // alerts off by default (sounds off by default)
    }

    @Test fun `config version bumped to 2`() { assertEquals(2, CONFIG_VERSION) }

    @Test fun `v1 config migrates version to 2`() {
        assertEquals(2, KVPartnerConfig(version = 1).migrateToLatest().version)
    }
}
