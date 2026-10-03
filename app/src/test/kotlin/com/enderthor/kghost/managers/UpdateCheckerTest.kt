package com.enderthor.kghost.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isNewer is true only when latest versionCode is strictly greater`() {
        assertTrue(UpdateChecker.isNewer(latestVersionCode = 202610030, currentVersionCode = 202610021))
        assertFalse(UpdateChecker.isNewer(latestVersionCode = 202610021, currentVersionCode = 202610021))
        assertFalse(UpdateChecker.isNewer(latestVersionCode = 202609010, currentVersionCode = 202610021))
    }

    @Test
    fun `parseManifest reads the real manifest shape`() {
        val raw = """{"label":"KGhost","latestVersion":"1.2.0","latestVersionCode":202610021,"releaseNotes":"x"}"""
        val m = UpdateChecker.parseManifest(raw)
        assertEquals(202610021, m?.latestVersionCode)
        assertEquals("1.2.0", m?.latestVersion)
    }

    @Test
    fun `parseManifest returns null on a non-JSON body`() {
        assertNull(UpdateChecker.parseManifest("<html>404</html>"))
        assertNull(UpdateChecker.parseManifest(""))
    }

    @Test
    fun `a manifest without version fields means no update`() {
        val m = UpdateChecker.parseManifest("""{"label":"KGhost"}""")
        assertFalse(UpdateChecker.isNewer(m!!.latestVersionCode, 202610021))
    }
}
