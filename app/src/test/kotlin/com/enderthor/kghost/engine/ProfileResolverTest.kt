package com.enderthor.kghost.engine

import com.enderthor.kghost.data.DEFAULT_TARGET_SPEED_MS
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.ProfileSetting
import com.enderthor.kghost.data.kmhToMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileResolverTest {

    private val globalTarget = kmhToMs(20.0)
    private fun cfg(
        master: Boolean = true,
        settings: List<ProfileSetting> = emptyList(),
    ) = KGhostConfig(targetSpeedMs = globalTarget, masterEnabled = master, profileSettings = settings)

    @Test fun `null or blank id resolves to global, active`() {
        val r = resolveProfile(cfg(), null)
        assertTrue(r.active)
        assertEquals(globalTarget, r.targetSpeedMs, 1e-9)
        assertEquals(globalTarget, resolveProfile(cfg(), "").targetSpeedMs, 1e-9)
    }

    @Test fun `unknown profile id resolves to global, active`() {
        val r = resolveProfile(cfg(settings = listOf(ProfileSetting("p1", "Road"))), "pX")
        assertTrue(r.active)
        assertEquals(globalTarget, r.targetSpeedMs, 1e-9)
    }

    @Test fun `useGlobal entry inherits global target and stays active`() {
        val r = resolveProfile(cfg(settings = listOf(ProfileSetting("p1", "Road", useGlobal = true))), "p1")
        assertTrue(r.active)
        assertEquals(globalTarget, r.targetSpeedMs, 1e-9)
        assertEquals("Road", r.profileName)
    }

    @Test fun `custom entry uses its own target`() {
        val mtb = kmhToMs(14.0)
        val r = resolveProfile(
            cfg(settings = listOf(ProfileSetting("p2", "MTB", useGlobal = false, targetSpeedMs = mtb, enabled = true))),
            "p2",
        )
        assertTrue(r.active)
        assertEquals(mtb, r.targetSpeedMs, 1e-9)
    }

    @Test fun `custom entry disabled makes it inactive but master keeps global target sane`() {
        val r = resolveProfile(
            cfg(settings = listOf(ProfileSetting("p2", "MTB", useGlobal = false, targetSpeedMs = 0.0, enabled = false))),
            "p2",
        )
        assertFalse(r.active)
        assertEquals(DEFAULT_TARGET_SPEED_MS, r.targetSpeedMs, 1e-9)
    }

    @Test fun `master off forces inactive even for a useGlobal profile`() {
        val r = resolveProfile(cfg(master = false, settings = listOf(ProfileSetting("p1", "Road"))), "p1")
        assertFalse(r.active)
    }

    @Test fun `learnProfile appends a useGlobal stub on first sight`() {
        val out = learnProfile(emptyList(), "p1", "Road")
        assertEquals(1, out.size)
        assertEquals("p1", out[0].profileId)
        assertEquals("Road", out[0].profileName)
        assertTrue(out[0].useGlobal)
    }

    @Test fun `learnProfile updates name but preserves override on rename`() {
        val existing = listOf(ProfileSetting("p1", "Road", useGlobal = false, targetSpeedMs = kmhToMs(33.0), enabled = false))
        val out = learnProfile(existing, "p1", "Road Bike")
        assertEquals(1, out.size)
        assertEquals("Road Bike", out[0].profileName)
        assertFalse(out[0].useGlobal)
        assertEquals(kmhToMs(33.0), out[0].targetSpeedMs, 1e-9)
        assertFalse(out[0].enabled)
    }

    @Test fun `learnProfile prunes stale same-name different-id predecessor`() {
        val existing = listOf(ProfileSetting("oldId", "Gravel", useGlobal = false))
        val out = learnProfile(existing, "newId", "Gravel")
        assertEquals(1, out.size)
        assertEquals("newId", out[0].profileId)
        assertTrue(out[0].useGlobal)
    }

    @Test fun `learnProfile is a no-op for blank id`() {
        val existing = listOf(ProfileSetting("p1", "Road"))
        assertEquals(existing, learnProfile(existing, "", "Whatever"))
    }
}
