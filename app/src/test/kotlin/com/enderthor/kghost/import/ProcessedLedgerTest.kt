package com.enderthor.kghost.import_

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessedLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fileWith(bytes: Int, mtime: Long): File =
        File(tmp.newFolder(), "r.fit").apply { writeBytes(ByteArray(bytes)); setLastModified(mtime) }

    @Test fun `unchanged file is processed, changed size or mtime is not`() {
        val ledger = ProcessedLedger(File(tmp.newFolder(), "processed.json"))
        val map = ledger.load()
        val f = fileWith(100, 1_000L)

        assertFalse("fresh file not yet processed", ledger.isProcessed(map, f))
        ledger.mark(map, f); ledger.save(map)

        val reloaded = ledger.load()
        assertTrue("unchanged file skipped on re-run", ledger.isProcessed(reloaded, f))

        f.setLastModified(2_000L)
        assertFalse("mtime change forces reprocess", ledger.isProcessed(reloaded, f))
    }

    @Test fun `corrupt ledger file loads as empty`() {
        val bad = File(tmp.newFolder(), "processed.json").apply { writeText("{ not json") }
        assertTrue(ProcessedLedger(bad).load().isEmpty())
    }
}
