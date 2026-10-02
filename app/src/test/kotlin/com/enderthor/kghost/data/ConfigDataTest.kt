package com.enderthor.kghost.data

import com.enderthor.kghost.engine.GhostPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDataTest {
    @Test fun `kmh to ms`() { assertEquals(5.0, kmhToMs(18.0), 1e-6) }
    @Test fun `pace minkm to ms`() { assertEquals(5.0, paceMinKmToMs(3.3333333), 1e-3) } // 3:20/km ≈ 5 m/s
    @Test fun `targetMs defaults when zeroed (VP cannot be deactivated)`() {
        assertEquals(DEFAULT_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = 0.0).targetMs(), 1e-9)
    }
    @Test fun `targetMs returns the configured value when set`() {
        assertEquals(5.0, KGhostConfig(targetSpeedMs = 5.0).targetMs(), 1e-6)
    }
    @Test fun `migrateToLatest is identity for a fresh config`() {
        val config = KGhostConfig()
        assertEquals(config, config.migrateToLatest())
    }

    @Test fun `default target is 20 kmh and version is current`() {
        val c = KGhostConfig()
        assertEquals(DEFAULT_TARGET_SPEED_MS, c.targetSpeedMs, 1e-9)
        assertEquals(kmhToMs(20.0), c.targetSpeedMs, 1e-9)
        assertEquals(CONFIG_VERSION, c.version)
    }

    // The default is never persisted (storage Json omits defaults), so a rider who never touched the
    // target picks up a changed default on the next read — while any other value survives it. Known
    // exception: a target typed as EXACTLY the old default (12 km/h) was omitted too, and now reads 20.
    @Test fun `an untouched target follows the default, an explicit one is kept`() {
        val untouched = com.enderthor.kghost.extension.jsonForStorage.encodeToString(KGhostConfig.serializer(), KGhostConfig())
        assertFalse(untouched.contains("targetSpeedMs"))
        // A blob written by an older build with the target untouched carries no target key at all.
        val oldBlob = com.enderthor.kghost.extension.jsonWithUnknownKeys.decodeFromString(KGhostConfig.serializer(), "{\"version\":9}")
        assertEquals(kmhToMs(20.0), oldBlob.migrateToLatest().targetMs(), 1e-9)
        val explicit = com.enderthor.kghost.extension.jsonForStorage
            .encodeToString(KGhostConfig.serializer(), KGhostConfig(targetSpeedMs = kmhToMs(25.0)))
        val back = com.enderthor.kghost.extension.jsonWithUnknownKeys.decodeFromString(KGhostConfig.serializer(), explicit)
        assertEquals(kmhToMs(25.0), back.targetSpeedMs, 1e-9)
    }

    @Test fun `v1 unset target migrates to the default`() {
        val migrated = KGhostConfig(version = 1, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(CONFIG_VERSION, migrated.version)
    }

    @Test fun `v2 unset target migrates to the default`() {
        val migrated = KGhostConfig(version = 2, targetSpeedMs = 0.0).migrateToLatest()
        assertEquals(DEFAULT_TARGET_SPEED_MS, migrated.targetSpeedMs, 1e-9)
        assertEquals(CONFIG_VERSION, migrated.version)
    }

    @Test fun `migration preserves an explicit target value`() {
        val migrated = KGhostConfig(version = 2, targetSpeedMs = kmhToMs(25.0)).migrateToLatest()
        assertEquals(kmhToMs(25.0), migrated.targetSpeedMs, 1e-9)
        assertEquals(CONFIG_VERSION, migrated.version)
    }

    @Test fun `race defaults are sane`() {
        val c = KGhostConfig()
        assertTrue(c.raceEnabled)            // ② on by default
        assertTrue(c.autoRecord)             // history recording on by default
        assertEquals(GhostPick.BEST, c.ghostPick)
        assertFalse(c.segmentEntryAlert)     // alerts off by default (sounds off by default)
    }

    @Test fun `config version bumped to 9`() { assertEquals(9, CONFIG_VERSION) }

    @Test fun `ghost icon default`() {
        assertEquals(GhostIcon.GHOST, KGhostConfig().ghostIcon)
    }

    @Test fun `v3 config migrates to current version keeping ghost defaults`() {
        val migrated = KGhostConfig(version = 3).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.version)
        assertEquals(GhostIcon.GHOST, migrated.ghostIcon)
    }

    @Test fun `targetMs clamps an out-of-range blob and defaults a non-positive one`() {
        assertEquals(kmhToMs(20.0), KGhostConfig(targetSpeedMs = kmhToMs(20.0)).targetMs(), 1e-9)
        assertEquals(MAX_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = 999.0).targetMs(), 1e-9)
        assertEquals(DEFAULT_TARGET_SPEED_MS, KGhostConfig(targetSpeedMs = -1.0).targetMs(), 1e-9)
    }
}
