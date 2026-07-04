package com.enderthor.kghost.import_

import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression coverage for marking PERMANENTLY-invalid (<2-point) files in the [ProcessedLedger] so
 * they are not re-decoded on every import (unlike genuinely transient failures — null decode or a
 * thrown exception — which must keep retrying, since those may be a mid-write/truncated file that
 * becomes readable later).
 *
 * "invalid.fit" decodes fine but its track has only 1 point (structurally unusable as a ghost —
 * indoor/trainer or single-GPS-fix ride) — must be marked in the ledger.
 * "corrupt.fit" fails to decode at all (fitDecode returns null) — must NOT be marked, so it keeps
 * retrying every run in case the underlying cause (e.g. a truncated file) resolves itself.
 */
class HistoryImporterInvalidLedgerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun validTrack(id: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0),
            TrackPointDto(0.001, 0.001, 100.0, 10.0),
        ),
        sourceKey = "key-$id",
        source = Source.FIT_IMPORT,
    )

    /** A single-point track: any identity decimation still yields <2 points, forcing the
     *  structurally-invalid path regardless of the injected `decimate`. */
    private fun onePointTrack(id: String): RecordedTrack = RecordedTrack(
        id = id,
        startedAtEpoch = 1_000L,
        points = listOf(TrackPointDto(0.0, 0.0, 0.0, 0.0)),
        sourceKey = "key-$id",
        source = Source.FIT_IMPORT,
    )

    private fun touch(dir: File, name: String, lastModified: Long): File =
        File(dir, name).apply { writeText("x"); setLastModified(lastModified) }

    @Test fun `structurally-invalid file is marked processed while a null-decode failure is not`() = runTest {
        val fitFilesDir = tmp.newFolder("fitfiles")
        val importDir = tmp.newFolder("import")
        val tracksDir = tmp.newFolder("tracks")
        val ledgerFile = File(tmp.newFolder("bookkeeping"), "processed.json")

        val validNames = listOf("v0.fit", "v1.fit", "v2.fit")
        validNames.forEachIndexed { i, name -> touch(fitFilesDir, name, 1_000L + i) }
        val invalidFile = touch(fitFilesDir, "invalid.fit", 2_000L)
        val corruptFile = touch(fitFilesDir, "corrupt.fit", 3_000L)

        val store = TrackStore(tracksDir)

        fun makeImporter(onInvalidDecode: (() -> Unit)? = null, onCorruptDecode: (() -> Unit)? = null) =
            HistoryImporter(
                fitFilesDir = fitFilesDir,
                importDir = importDir,
                trackStore = store,
                decimate = { it },
                fitDecode = { f, _ ->
                    when (f.name) {
                        invalidFile.name -> {
                            onInvalidDecode?.invoke()
                            onePointTrack("t-invalid")
                        }
                        corruptFile.name -> {
                            onCorruptDecode?.invoke()
                            null
                        }
                        else -> validTrack("t-${f.name}")
                    }
                },
                gpxParse = { null },
                lastScanProvider = { 0L },
                processedLedgerFile = ledgerFile,
            )

        // --- FIRST RUN ---
        val firstImporter = makeImporter()
        val firstDone = firstImporter.import(onlyNew = false).toList().last()

        val total = validNames.size + 2 // 3 valid + invalid + corrupt
        assertEquals(total, firstDone.total)
        assertEquals(firstDone.total, firstDone.imported + firstDone.skippedDuplicates + firstDone.failed)
        assertTrue("both invalid and corrupt must count as failed", firstDone.failed >= 2)
        assertEquals(validNames.size, firstDone.imported)

        val ledger = ProcessedLedger(ledgerFile)
        val reloaded = ledger.load()

        assertTrue(
            "structurally-invalid (<2-point) file must be marked processed so it isn't re-decoded",
            ledger.isProcessed(reloaded, invalidFile),
        )
        assertFalse(
            "null-decode (transient) failure must NOT be marked, so it keeps retrying",
            ledger.isProcessed(reloaded, corruptFile),
        )
        validNames.forEach { name ->
            assertTrue("valid file $name must be marked processed", ledger.isProcessed(reloaded, File(fitFilesDir, name)))
        }

        // --- SECOND RUN ---
        // A fresh importer instance (same ledger file on disk) with spies proving the invalid
        // file's decode is skipped via the ledger, while the corrupt file's decode is retried.
        var invalidDecodeCalledOnSecondRun = false
        var corruptDecodeCalledOnSecondRun = false
        val secondImporter = makeImporter(
            onInvalidDecode = { invalidDecodeCalledOnSecondRun = true },
            onCorruptDecode = { corruptDecodeCalledOnSecondRun = true },
        )
        val secondDone = secondImporter.import(onlyNew = false).toList().last()

        assertFalse(
            "invalid file was already ledger-marked as permanently unusable; decode must be skipped",
            invalidDecodeCalledOnSecondRun,
        )
        assertTrue(
            "corrupt (transient) file must still be retried on every run",
            corruptDecodeCalledOnSecondRun,
        )
        // Only the corrupt file is attempted this run (valid files + invalid are ledger-skipped).
        assertEquals(1, secondDone.total)
        assertEquals(1, secondDone.failed)
    }
}
