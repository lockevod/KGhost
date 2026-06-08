package com.enderthor.kghost.data

import com.enderthor.kghost.engine.GhostPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDataTest {
    @Test fun `kmh to ms`() { assertEquals(5.0, kmhToMs(18.0), 1e-6) }
    @Test fun `pace minkm to ms`() { assertEquals(5.0, paceMinKmToMs(3.3333333), 1e-3) } // 3:20/km ≈ 5 m/s
    @Test fun `targetMs defaults to 12 km per h when zeroed (VP cannot be deactivated)`() {
        assertEquals(DEFAULT_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = 0.0).targetMs(), 1e-9)
    }
    @Test fun `targetMs returns the configured value when set`() {
        assertEquals(5.0, KGhostConfig(targetSpeedMs = 5.0).targetMs(), 1e-6)
    }
    @Test fun `migrateToLatest is identity for a fresh config`() {
        val config = KGhostConfig()
        assertEquals(config, config.migrateToLatest())
    }

    @Test fun `default target is 12 kmh and version is 4`() {
        val c = KGhostConfig()
        assertEquals(DEFAULT_TARGET_SPEED_MS, c.targetSpeedMs, 1e-9)
        assertEquals(kmhToMs(12.0), c.targetSpeedMs, 1e-9)
        assertEquals(3.333333, c.targetSpeedMs, 1e-3)
        assertEquals(4, c.version)
    }

    @Test fun `v1 unset target migrates to 12 kmh default`() {
        val migrated = KGhostConfig(version = 1, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `v2 unset target migrates to 12 kmh default`() {
        val migrated = KGhostConfig(version = 2, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `migration preserves an explicit target value`() {
        val migrated = KGhostConfig(version = 2, targetSpeedMs = kmhToMs(25.0)).migrateToLatest()
        assertEquals(kmhToMs(25.0), migrated.targetSpeedMs, 1e-9)
        assertEquals(4, migrated.version)
    }

    @Test fun `race defaults are sane`() {
        val c = KGhostConfig()
        assertTrue(c.raceEnabled)            // ② on by default
        assertTrue(c.autoRecord)             // history recording on by default
        assertEquals(GhostPick.BEST, c.ghostPick)
        assertFalse(c.segmentEntryAlert)     // alerts off by default (sounds off by default)
    }

    @Test fun `config version bumped to 4`() { assertEquals(4, CONFIG_VERSION) }

    @Test fun `ghost icon default`() {
        assertEquals(GhostIcon.GHOST, KGhostConfig().ghostIcon)
    }

    @Test fun `v3 config migrates to 4 keeping ghost defaults`() {
        val migrated = KGhostConfig(version = 3).migrateToLatest()
        assertEquals(4, migrated.version)
        assertEquals(GhostIcon.GHOST, migrated.ghostIcon)
    }

    @Test fun `targetMs clamps an out-of-range blob and defaults a non-positive one`() {
        assertEquals(kmhToMs(20.0), KGhostConfig(targetSpeedMs = kmhToMs(20.0)).targetMs(), 1e-9)
        assertEquals(MAX_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = 999.0).targetMs(), 1e-9)
        assertEquals(DEFAULT_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = -1.0).targetMs(), 1e-9)
    }
}
