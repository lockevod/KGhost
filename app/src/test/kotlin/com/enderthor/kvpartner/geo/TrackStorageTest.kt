package com.enderthor.kvpartner.geo

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

    @Test fun `migrateIfNeeded does not copy when external already has a file`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        internal.writeFile("a.json", "A")
        external.writeFile("existing.json", "EXT")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        assertEquals(0, copied)
        // The internal file was NOT brought across because external is already canonical.
        assertTrue(!File(external, "a.json").exists())
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
        // External has one file already, but also a different-named one that should be copied.
        internal.writeFile("a.json", "internal-A")
        internal.writeFile("b.json", "internal-B")
        external.writeFile("a.json", "external-A")

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        // External already had a file → whole migration is skipped (external is canonical),
        // so the existing a.json keeps its content and nothing is overwritten.
        assertEquals(0, copied)
        assertEquals("external-A", File(external, "a.json").readText())
    }

    @Test fun `migrateIfNeeded skips directories and only copies regular files`() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        internal.writeFile("a.json", "A")
        File(internal, "subdir").mkdirs()

        val copied = TrackStorage.migrateIfNeeded(internal, external)

        assertEquals(1, copied)
        assertTrue(File(external, "a.json").exists())
        assertTrue(!File(external, "subdir").exists())
    }
}
