package com.enderthor.kghost.managers

import com.enderthor.kghost.data.GhostIcon
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.extension.jsonForStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision the DataStore corruption recovery rests on: WHEN the config mirror is
 * restored and WHAT of it comes back. The DataStore delegate itself (the corruption handler, the
 * `edit {}` write) has no JVM harness — it needs an Android Context and a real preferences file —
 * so it is exercised on-device, not here.
 */
class ConfigMirrorTest {

    private val saved = KGhostConfig(
        targetSpeedMs = 7.5,
        ghostIcon = GhostIcon.CYCLIST,
        masterEnabled = false,
        lastScanEpoch = 1_700_000_000_000L,
        tidySweepEpoch = 1_700_000_000_000L,
        permAlertFiredCount = 3,
        permAlertLastFiredEpoch = 1_699_000_000_000L,
    )
    private val mirror = jsonForStorage.encodeToString(saved)

    @Test fun `corruption reset restores the rider's settings`() {
        val restored = restoredFromMirror(mirror)!!
        assertEquals(saved.targetSpeedMs, restored.targetSpeedMs, 1e-9)
        assertEquals(GhostIcon.CYCLIST, restored.ghostIcon)
        assertFalse(restored.masterEnabled)
    }

    @Test fun `a genuine first run has no mirror and restores nothing`() {
        assertNull(restoredFromMirror(null))
    }

    @Test fun `ride-churned epochs are never restored`() {
        val restored = restoredFromMirror(mirror)!!
        // Stale values here SKIP work (an unimported file, a never-run sweep) — always re-do it.
        assertEquals(0L, restored.lastScanEpoch)
        assertEquals(0L, restored.tidySweepEpoch)
        // The alert schedule is not work-skipping, so it comes back as saved.
        assertEquals(3, restored.permAlertFiredCount)
        assertEquals(1_699_000_000_000L, restored.permAlertLastFiredEpoch)
    }

    @Test fun `an unparseable mirror degrades to defaults without throwing`() {
        assertNull(restoredFromMirror("{\"targetSpeedMs\":"))
        assertEquals(KGhostConfig(), runBlocking { configForUpdate(null) { "{\"targetSpeedMs\":" } })
    }

    @Test fun `the write path never consults the mirror when the store has a blob`() = runBlocking {
        val current = configForUpdate(jsonForStorage.encodeToString(KGhostConfig(targetSpeedMs = 4.0))) {
            error("mirror must not be read when DataStore holds a config")
        }
        assertEquals(4.0, current.targetSpeedMs, 1e-9)
    }

    @Test fun `the write path still throws on a present-but-broken blob`() {
        val threw = runCatching { runBlocking { configForUpdate("{\"targetSpeedMs\":") { mirror } } }.isFailure
        // Must NOT fall back to defaults/mirror: that would persist them over the rider's settings.
        assertTrue(threw)
    }

    @Test fun `an empty store with a mirror seeds the write path from the mirror`() = runBlocking {
        val current = configForUpdate(null) { mirror }
        assertEquals(saved.targetSpeedMs, current.targetSpeedMs, 1e-9)
        assertEquals(0L, current.lastScanEpoch)
    }
}
