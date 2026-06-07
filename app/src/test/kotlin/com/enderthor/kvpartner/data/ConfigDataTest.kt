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
    @Test fun `migrateToLatest is identity for a fresh config`() {
        val config = KVPartnerConfig()
        assertEquals(config, config.migrateToLatest())
    }

    @Test fun `default target is 12 kmh and version is 4`() {
        val c = KVPartnerConfig()
        assertEquals(DEFAULT_TARGET_SPEED_MS, c.targetSpeedMs, 1e-9)
        assertEquals(kmhToMs(12.0), c.targetSpeedMs, 1e-9)
        assertEquals(3.333333, c.targetSpeedMs, 1e-3)
        assertEquals(4, c.version)
    }

    @Test fun `v1 unset target migrates to 12 kmh default`() {
        val migrated = KVPartnerConfig(version = 1, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `v2 unset target migrates to 12 kmh default`() {
        val migrated = KVPartnerConfig(version = 2, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `migration preserves an explicit target value`() {
        val migrated = KVPartnerConfig(version = 2, targetSpeedMs = kmhToMs(25.0)).migrateToLatest()
        assertEquals(kmhToMs(25.0), migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `race defaults are sane`() {
        val c = KVPartnerConfig()
        assertTrue(c.raceEnabled)            // ② on by default
        assertTrue(c.autoRecord)             // history recording on by default
        assertEquals(GhostPick.BEST, c.ghostPick)
        assertFalse(c.segmentEntryAlert)     // alerts off by default (sounds off by default)
    }

    @Test fun `config version bumped to 4`() { assertEquals(4, CONFIG_VERSION) }

    @Test fun `ghost icon and size defaults`() {
        val c = KVPartnerConfig()
        assertEquals(GhostIcon.GHOST, c.ghostIcon)
        assertEquals(GhostSize.MEDIUM, c.ghostSize)
    }

    @Test fun `v3 config migrates to 4 keeping ghost defaults`() {
        val migrated = KVPartnerConfig(version = 3).migrateToLatest()
        assertEquals(4, migrated.version)
        assertEquals(GhostIcon.GHOST, migrated.ghostIcon)
        assertEquals(GhostSize.MEDIUM, migrated.ghostSize)
    }

    @Test fun `validTargetOrNull returns value for positive and null for explicit clear`() {
        assertEquals(kmhToMs(20.0), KVPartnerConfig(targetSpeedMs = kmhToMs(20.0)).validTargetOrNull()!!, 1e-9)
        assertNull(KVPartnerConfig(targetSpeedMs = 0.0).validTargetOrNull())
    }
}
