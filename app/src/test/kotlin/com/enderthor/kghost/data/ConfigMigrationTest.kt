package com.enderthor.kghost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMigrationTest {

    @Test
    fun `v4 config migrates through v5 to latest with empty profileSettings, masterEnabled true, target preserved`() {
        val custom = kmhToMs(28.0)
        val old = KGhostConfig(version = 4, targetSpeedMs = custom)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.version)
        assertTrue(migrated.masterEnabled)
        assertEquals(emptyList<ProfileSetting>(), migrated.profileSettings)
        assertEquals(custom, migrated.targetSpeedMs, 1e-9)
    }

    @Test
    fun `fresh config defaults are master-on with no profiles`() {
        val c = KGhostConfig()
        assertTrue(c.masterEnabled)
        assertEquals(emptyList<ProfileSetting>(), c.profileSettings)
    }

    @Test
    fun `v5 config migrates to v6 with autoTidy on and tidySweepEpoch zero, prior fields intact`() {
        val custom = kmhToMs(31.0)
        val old = KGhostConfig(version = 5, targetSpeedMs = custom, masterEnabled = false)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.version)
        assertTrue(migrated.autoTidy)
        assertEquals(0L, migrated.tidySweepEpoch)
        assertEquals(custom, migrated.targetSpeedMs, 1e-9)
        assertEquals(false, migrated.masterEnabled)
    }
}
