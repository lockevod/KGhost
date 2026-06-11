package com.enderthor.kghost.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.writeFile(name: String, content: String): File =
        File(this, name).apply { writeText(content) }

    @Test fun `migrateIfNeeded copies internal files to empty external dir`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        internal.writeFile("a.json", "A")
        internal.writeFile("index.json", "IDX")
        internal.writeFile("sourcekeys.json", "SK")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        assertEquals(3, copied)
        assertEquals("A", File(external, "a.json").readText())
        assertEquals("IDX", File(external, "index.json").readText())
        assertEquals("SK", File(external, "sourcekeys.json").readText())
        // Internal copies are kept as a backup.
        assertTrue(File(internal, "a.json").exists())
        assertTrue(File(internal, "index.json").exists())
        assertTrue(File(internal, "sourcekeys.json").exists())
    }

    @Test fun `migrateIfNeeded heals internal-only files when external is already canonical`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        // a.json landed on INTERNAL only (recorded while all-files access was revoked).
        internal.writeFile("a.json", "A")
        external.writeFile("existing.json", "EXT")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        // Split-brain heal: the internal-only ride is brought across; external keeps its own files.
        assertEquals(1, copied)
        assertEquals("A", File(external, "a.json").readText())
        assertEquals("EXT", File(external, "existing.json").readText())
    }

    @Test fun `migrateIfNeeded heal does not resurrect a ride archived on external`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        // c.json was migrated long ago and later ARCHIVED by the auto-clean on external; the
        // internal backup copy must NOT come back as an active track.
        internal.writeFile("c.json", "C")
        external.writeFile("existing.json", "EXT")
        val externalArchive = File(external, TrackStore.ARCHIVE_SUBDIR).apply { mkdirs() }
        File(externalArchive, "c.json").writeText("C")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        assertEquals(0, copied)
        assertTrue(!File(external, "c.json").exists())
    }

    @Test fun `migrateIfNeeded returns 0 when internal is empty`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")

        assertEquals(0, TrackStorage.migrateIfNeeded(internal, external))
    }

    @Test fun `migrateIfNeeded returns 0 when internal is absent`() {
        val internal = File(tmp.root, "does-not-exist")
        val external = tmp.newFolder("external")

        assertEquals(0, TrackStorage.migrateIfNeeded(internal, external))
    }

    @Test fun `migrateIfNeeded does not overwrite an existing external file of the same name`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        internal.writeFile("a.json", "internal-A")
        internal.writeFile("b.json", "internal-B")
        external.writeFile("a.json", "external-A")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        // External is canonical: the same-name a.json is NEVER overwritten; the internal-only
        // b.json is healed across.
        assertEquals(1, copied)
        assertEquals("external-A", File(external, "a.json").readText())
        assertEquals("internal-B", File(external, "b.json").readText())
    }

    @Test fun `migrateIfNeeded skips unknown directories but DOES copy the archive subdir`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        internal.writeFile("a.json", "A")
        File(internal, "subdir").mkdirs() // unrelated dir → skipped
        val internalArchive = File(internal, TrackStore.ARCHIVE_SUBDIR).apply { mkdirs() }
        File(internalArchive, "old.json").writeText("OLD")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        assertEquals(2, copied) // a.json + archive/old.json
        assertTrue(File(external, "a.json").exists())
        assertTrue(!File(external, "subdir").exists())
        // The archived ride is brought across so it stays recoverable on external.
        assertEquals("OLD", File(File(external, TrackStore.ARCHIVE_SUBDIR), "old.json").readText())
        // Internal archive kept as a backup.
        assertTrue(File(internalArchive, "old.json").exists())
    }
}
