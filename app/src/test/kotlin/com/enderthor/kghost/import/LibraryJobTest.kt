package com.enderthor.kghost.import_

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.reconcileOwed
import com.enderthor.kghost.geo.RecordedTrack
import com.enderthor.kghost.geo.Source
import com.enderthor.kghost.geo.TrackPointDto
import com.enderthor.kghost.geo.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Temp-dir library + a config held in a [MutableStateFlow]; shared by the job and runner tests. */
internal class LibraryFixture(root: File) {
    val tracksDir = File(root, "tracks").apply { mkdirs() }
    val fitDir = File(root, "fitfiles").apply { mkdirs() }
    val importDir = File(root, "import").apply { mkdirs() }
    val cfg = MutableStateFlow(KGhostConfig())
    var updateOk = true
    var decodes = 0

    fun ride(name: String, ele: Boolean = false, source: Source = Source.FITFILES_SCAN) = RecordedTrack(
        id = name,
        startedAtEpoch = 1_000_000L + name.filter(Char::isDigit).ifEmpty { "0" }.toLong() * 10_000L,
        points = listOf(
            TrackPointDto(0.0, 0.0, 0.0, 0.0, if (ele) 100.0 else null),
            TrackPointDto(0.0004, 0.0, 50.0, 10.0, if (ele) 101.0 else null),
            TrackPointDto(0.0009, 0.0, 100.0, 20.0, if (ele) 102.0 else null),
        ),
        sourceKey = "k-$name",
        source = source,
    )

    fun fits(n: Int, prefix: String = "r", from: Int = 1) =
        (from until from + n).map { File(fitDir, "$prefix$it.fit").apply { writeText("x") } }

    fun deps(
        decode: (File) -> RecordedTrack? = { f -> ride(f.nameWithoutExtension) },
        lastScanProvider: () -> Long = { 0L },
        lastScanSetter: suspend (Long) -> Unit = {},
        config: suspend () -> KGhostConfig = { cfg.value },
        sweep: (() -> Unit) -> Int? = { c -> TrackStore(tracksDir).sweep(checkCancel = c) },
    ) = LibraryDeps(
        tracksDir = tracksDir,
        fitFilesDir = fitDir,
        importDir = importDir,
        config = config,
        updateConfig = { t -> if (updateOk) { cfg.update(t); true } else false },
        newImporter = { onStored ->
            HistoryImporter(
                fitFilesDir = fitDir,
                importDir = importDir,
                trackStore = TrackStore(tracksDir),
                decimate = { it },
                fitDecode = { f, _ -> decodes++; decode(f) },
                gpxParse = { null },
                lastScanProvider = lastScanProvider,
                lastScanSetter = lastScanSetter,
                processedLedgerFile = File(tracksDir, "processed.json"),
                onStored = onStored,
            )
        },
        sweep = sweep,
    )
}

class LibraryJobTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture() = LibraryFixture(tmp.newFolder())

    private suspend fun run(deps: LibraryDeps, auto: Boolean = false) = runCatching {
        runLibraryJob(deps, auto = auto, onlyNew = false) {}
    }

    @Test fun `work sets both debts before decoding`() = runTest {
        val fx = fixture()
        fx.fits(1)
        val r = run(fx.deps(decode = { throw CancellationException("cut before any decode finished") }))
        assertTrue(r.exceptionOrNull() is CancellationException)
        assertTrue(fx.cfg.value.gradeModelDirty)
        assertTrue(fx.cfg.value.reconcileOwed())
    }

    @Test fun `a debt that fails to persist aborts before decoding`() = runTest {
        val fx = fixture()
        fx.fits(1)
        fx.updateOk = false
        val r = run(fx.deps())
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(0, fx.decodes)
    }

    @Test fun `an enrich-only run still owes the model`() = runTest {
        val fx = fixture()
        TrackStore(fx.tracksDir).add(fx.ride("r1", ele = false, source = Source.RECORDED).copy(id = "live1"))
        fx.fits(1)
        var last: ImportProgress? = null
        val r = runCatching {
            runLibraryJob(
                fx.deps(decode = { fx.ride("r1", ele = true) }, config = { throw CancellationException("cut before discharge") }),
                auto = false, onlyNew = false,
            ) { last = it }
        }
        assertTrue(r.exceptionOrNull() is CancellationException)
        assertEquals("fixture must enrich, not add", 0, last?.imported)
        assertEquals("fixture must enrich, not add", 1, last?.enriched)
        assertTrue(fx.cfg.value.gradeModelDirty)
    }

    @Test fun `a ride saved during a sweep stays owed`() = runTest {
        val fx = fixture()
        fx.cfg.value = KGhostConfig(reconcileGen = 1)
        run(fx.deps(sweep = { fx.cfg.update { it.copy(reconcileGen = 2) }; 0 })).getOrThrow()
        assertEquals(1L, fx.cfg.value.reconcileAckGen)
        assertTrue(fx.cfg.value.reconcileOwed())
    }

    @Test fun `a completed sweep acks the debt`() = runTest {
        val fx = fixture()
        fx.cfg.value = KGhostConfig(reconcileGen = 3)
        run(fx.deps()).getOrThrow()
        assertEquals(3L, fx.cfg.value.reconcileAckGen)
        assertFalse(fx.cfg.value.reconcileOwed())
    }

    @Test fun `a zero-work run discharges a set model debt`() = runTest {
        val fx = fixture()
        fx.cfg.value = KGhostConfig(gradeModelDirty = true)
        run(fx.deps()).getOrThrow()
        assertTrue(File(fx.tracksDir, "gradepace.json").isFile)
        assertFalse(fx.cfg.value.gradeModelDirty)
        assertEquals("nothing to import bumps no gen", 0L, fx.cfg.value.reconcileGen)
    }

    @Test fun `a failed model save keeps the debt`() = runTest {
        val fx = fixture()
        fx.cfg.value = KGhostConfig(gradeModelDirty = true)
        File(fx.tracksDir, "gradepace.json").mkdirs() // a directory where the model file goes
        run(fx.deps()).getOrThrow()
        assertTrue(fx.cfg.value.gradeModelDirty)
    }

    @Test fun `a size-skipped sweep keeps the reconcile debt`() = runTest {
        val fx = fixture()
        // The cap check counts ids before decoding anything, so filename fixtures suffice.
        repeat(2_001) { File(fx.tracksDir, "t$it.json").writeText("{}") }
        fx.cfg.value = KGhostConfig(reconcileGen = 1)
        run(fx.deps()).getOrThrow()
        assertTrue(fx.cfg.value.reconcileOwed())
    }

    @Test fun `a cancelled auto run still counts its stored tracks`() = runTest {
        val fx = fixture()
        fx.fits(30)
        // The first flush's lastScan persist is the cancel point: after the store, before any later
        // progress emission could report it.
        val r = run(fx.deps(lastScanSetter = { throw CancellationException("cut after the first flush") }), auto = true)
        assertTrue(r.exceptionOrNull() is CancellationException)
        assertEquals(25, fx.cfg.value.pendingFoundRides)
    }

    @Test fun `a manual run never adds found rides`() = runTest {
        val fx = fixture()
        fx.fits(1)
        assertEquals(1, run(fx.deps(), auto = false).getOrThrow())
        assertEquals(0, fx.cfg.value.pendingFoundRides)
    }

    @Test fun `found rides sum across runs`() = runTest {
        val fx = fixture()
        fx.fits(2)
        run(fx.deps(), auto = true).getOrThrow()
        fx.fits(3, from = 3)
        run(fx.deps(), auto = true).getOrThrow()
        assertEquals(5, fx.cfg.value.pendingFoundRides)
    }
}
