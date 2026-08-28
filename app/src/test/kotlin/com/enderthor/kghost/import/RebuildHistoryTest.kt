package com.enderthor.kghost.import_

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RebuildHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `rebuild clears both dedup gates so every source file is decoded again`() {
        val dir = tmp.newFolder()
        File(dir, "processed.json").writeText("""{"entries":{}}""")
        File(dir, "sourcekeys.json").writeText("""["1:2"]""")

        clearImportDedup(dir)

        assertFalse("the ledger must be gone", File(dir, "processed.json").exists())
        assertFalse("the sourceKey set must be gone", File(dir, "sourcekeys.json").exists())
    }

    @Test fun `rebuild is safe when neither file exists yet`() {
        val dir = tmp.newFolder()
        clearImportDedup(dir) // must not throw
        assertTrue(dir.exists())
    }
}
