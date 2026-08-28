package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackIdentity
import com.enderthor.kghost.geo.TrackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rebuild's data-safety rules. `rebuildAll` itself needs a `Context` and is not unit-testable here
 * (no Robolectric on the classpath), so the two rules that can destroy irreplaceable history are pure
 * top-level functions and are asserted DIRECTLY — not the plumbing around them.
 */
class RebuildHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    // ── what may be archived ─────────────────────────────────────────────────

    @Test fun `a live-recorded track is never archived`() {
        val ids = fileSourcedIds(listOf(TrackIdentity("live", Source.RECORDED, "1:2")))
        assertTrue("RECORDED can never be re-created by an import, so it must never be archived", ids.isEmpty())
    }

    @Test fun `every file-sourced variant is archived`() {
        val fileSources = Source.entries - Source.RECORDED
        val meta = fileSources.map { TrackIdentity(it.name, it, "k-${it.name}") }
        assertEquals(fileSources.map { it.name }, fileSourcedIds(meta))
    }

    @Test fun `the recorded track survives a mixed library`() {
        val meta = listOf(
            TrackIdentity("live", Source.RECORDED, "1:2"),
            TrackIdentity("scan", Source.FITFILES_SCAN, "3:4"),
            TrackIdentity("drop", Source.GPX_IMPORT, "5:6"),
        )
        assertEquals(listOf("scan", "drop"), fileSourcedIds(meta))
    }

    @Test fun `an empty library archives nothing`() {
        assertTrue(fileSourcedIds(emptyList()).isEmpty())
        assertTrue(survivingSourceKeys(emptyList()).isEmpty())
    }

    // ── which source keys survive ────────────────────────────────────────────

    @Test fun `only the surviving recorded tracks keep their source keys`() {
        val meta = listOf(
            TrackIdentity("live", Source.RECORDED, "1:2"),
            TrackIdentity("scan", Source.FITFILES_SCAN, "3:4"),
        )
        // The RECORDED key must stay: it is the ONLY thing that collapses that ride's FIT onto the live
        // track instead of storing a permanent twin. The archived one must go, or its file never re-decodes.
        assertEquals(setOf("1:2"), survivingSourceKeys(meta))
    }

    @Test fun `a recorded track with no source key contributes nothing`() {
        assertTrue(survivingSourceKeys(listOf(TrackIdentity("legacy", Source.RECORDED, ""))).isEmpty())
    }

    // ── the gates on disk ────────────────────────────────────────────────────

    @Test fun `reset deletes the ledger and rewrites the keys with only the survivors`() {
        val dir = tmp.newFolder()
        File(dir, "processed.json").writeText("""{"entries":{}}""")
        File(dir, "sourcekeys.json").writeText("""["1:2","3:4"]""")
        val store = TrackStore(dir)

        resetImportDedup(dir, store, keepKeys = setOf("1:2"))

        assertFalse("the ledger must be gone", File(dir, "processed.json").exists())
        assertTrue("the sourceKey set must survive, not be deleted", File(dir, "sourcekeys.json").exists())
        assertEquals(setOf("1:2"), store.knownSourceKeys())
    }

    @Test fun `reset is safe when neither file exists yet`() {
        val dir = tmp.newFolder()
        resetImportDedup(dir, TrackStore(dir), keepKeys = emptySet()) // must not throw
        assertEquals(emptySet<String>(), TrackStore(dir).knownSourceKeys())
    }
}
