package com.enderthor.kghost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMigrationTest {

    @Test
    fun `v4 config migrates to v5 with empty profileSettings, masterEnabled true, target preserved`() {
        val custom = kmhToMs(28.0)
        val old = KGhostConfig(version = 4, targetSpeedMs = custom)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.version)
        assertEquals(5, CONFIG_VERSION)
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
}
