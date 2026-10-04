package com.enderthor.kghost.import_

import android.content.Context
import com.enderthor.kghost.geo.LibraryLock
import com.enderthor.kghost.managers.ConfigurationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import com.enderthor.kghost.geo.TrackStore
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryImportRunnerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val ctx: Context = mock(Context::class.java)
    private val cm: ConfigurationManager = mock(ConfigurationManager::class.java)
    private lateinit var fx: LibraryFixture
    private var cutoff = 0L

    @Before fun setUp() {
        fx = LibraryFixture(tmp.newFolder())
        HistoryImportRunner.scopeForTest = CoroutineScope(SupervisorJob() + dispatcher)
        HistoryImportRunner.depsForTest = { fx.deps(lastScanProvider = { cutoff }) }
    }

    @After fun tearDown() {
        HistoryImportRunner.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        // Only a test can still hold it here: every runner job is cancelled and drained above.
        if (LibraryLock.mutex.isLocked) LibraryLock.mutex.unlock()
        HistoryImportRunner.scopeForTest = null
        HistoryImportRunner.depsForTest = null
        HistoryImportRunner.completionHookForTest = null
    }

    private fun start(auto: Boolean = false, onlyNew: Boolean = false, admit: (suspend () -> Boolean)? = null) =
        HistoryImportRunner.start(ctx, cm, onlyNew, lastScanEpoch = cutoff, auto = auto, admit = admit)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test fun `the lock serializes jobs`() = test {
        fx.fits(1)
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        assertEquals(0, fx.decodes)
        assertTrue(HistoryImportRunner.running.value)
        LibraryLock.mutex.unlock()
        advanceUntilIdle()
        assertEquals(1, fx.decodes)
        assertFalse(HistoryImportRunner.running.value)
    }

    @Test fun `a refused admission releases running`() = test {
        fx.fits(1)
        assertTrue(start(auto = true, admit = { false }))
        advanceUntilIdle()
        assertFalse(HistoryImportRunner.running.value)
        assertEquals(0, fx.decodes)
        assertTrue(start())
    }

    @Test fun `a cancel while waiting for the lock releases running and a later start is accepted`() = test {
        fx.fits(1)
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        HistoryImportRunner.cancel()
        advanceUntilIdle()
        assertFalse(HistoryImportRunner.running.value)
        assertTrue(HistoryImportRunner.canceled.value)
        LibraryLock.mutex.unlock()
        assertTrue(start())
        advanceUntilIdle()
        assertEquals(1, fx.decodes)
    }

    @Test fun `manual start while an auto run is active is refused`() = test {
        LibraryLock.mutex.lock()
        assertTrue(start(auto = true))
        assertTrue(HistoryImportRunner.activeRunIsAuto)
        assertFalse(start(auto = false))
        HistoryImportRunner.cancelAuto()
        advanceUntilIdle()
        assertFalse(HistoryImportRunner.running.value)
        assertFalse(HistoryImportRunner.activeRunIsAuto)
    }

    @Test fun `cancelAuto leaves a manual run alone`() = test {
        fx.fits(1)
        LibraryLock.mutex.lock()
        assertTrue(start(auto = false))
        assertFalse(HistoryImportRunner.activeRunIsAuto)
        HistoryImportRunner.cancelAuto()
        advanceUntilIdle()
        assertTrue(HistoryImportRunner.running.value)
        LibraryLock.mutex.unlock()
        advanceUntilIdle()
        assertEquals(1, fx.decodes)
    }

    @Test fun `cancel then immediate start is refused until completion`() = test {
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        HistoryImportRunner.cancel()
        assertFalse("a cancelled job that has not completed still holds admission", start())
        advanceUntilIdle()
        assertTrue(start())
    }

    @Test fun `a run admitted after a cancelled one starts and ends with its own state`() = test {
        fx.fits(1)
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        HistoryImportRunner.cancel()
        start() // refused while the cancelled run is still finishing
        advanceUntilIdle()
        assertTrue(start()) // B, queued on the lock
        advanceUntilIdle()
        assertTrue(HistoryImportRunner.running.value)
        assertFalse(HistoryImportRunner.canceled.value)
        LibraryLock.mutex.unlock()
        advanceUntilIdle()
        assertEquals(ImportProgress.Phase.DONE, HistoryImportRunner.progress.value?.phase)
        assertFalse(HistoryImportRunner.canceled.value)
        assertFalse(HistoryImportRunner.running.value)
        assertEquals(1, fx.decodes)
    }

    @Test fun `a stale completion handler cannot overwrite the new run's state`() = test {
        LibraryLock.mutex.lock() // keeps both A and B queued on the lock
        assertTrue(start())
        advanceUntilIdle()
        var other: Thread? = null
        var admitted = false
        // Between A's identity check and its writes, another thread tries to start B. Correct code holds
        // [admission] there, so B waits until A's handler is done; the join timeout only bounds that wait.
        HistoryImportRunner.completionHookForTest = {
            HistoryImportRunner.completionHookForTest = null
            other = Thread { admitted = start() }.apply { start(); join(500) }
        }
        HistoryImportRunner.cancel()
        advanceUntilIdle()
        other!!.join()
        assertTrue("B must be admitted once A has completed", admitted)
        assertTrue("A's handler must not clear the running B", HistoryImportRunner.running.value)
        assertFalse(HistoryImportRunner.canceled.value)
    }

    @Test fun `cancelling an automatic run does not report an import cancel`() = test {
        // A completed manual run clears whatever cancel line an earlier test left on the shared runner;
        // an automatic run deliberately no longer does.
        assertTrue(start())
        advanceUntilIdle()
        LibraryLock.mutex.lock()
        assertTrue(start(auto = true))
        advanceUntilIdle()
        HistoryImportRunner.cancelAuto()
        advanceUntilIdle()
        assertFalse(HistoryImportRunner.running.value)
        assertFalse(HistoryImportRunner.canceled.value)
    }

    @Test fun `manual new-only keeps its cutoff`() = test {
        fx.fits(1).single().setLastModified(1_000_000L)
        cutoff = 5_000_000L
        assertTrue(start(onlyNew = true))
        advanceUntilIdle()
        assertEquals("an old unledgered file is outside a manual New-only scan", 0, fx.decodes)
        assertTrue(start(auto = true, onlyNew = true))
        advanceUntilIdle()
        assertEquals("an automatic run always scans everything", 1, fx.decodes)
    }

    @Test fun `a zero-work automatic run leaves the manual summary untouched`() = test {
        fx.fits(1)
        assertTrue(start())
        advanceUntilIdle()
        val manual = HistoryImportRunner.progress.value
        assertEquals(1, manual?.imported)
        assertTrue(start(auto = true)) // the ledger skips the one file: nothing to do
        advanceUntilIdle()
        assertEquals(manual, HistoryImportRunner.progress.value)
    }

    @Test fun `a refused automatic run leaves the manual cancel line untouched`() = test {
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        HistoryImportRunner.cancel()
        advanceUntilIdle()
        LibraryLock.mutex.unlock()
        assertTrue(HistoryImportRunner.canceled.value)
        assertTrue(start(auto = true, admit = { false }))
        advanceUntilIdle()
        assertTrue(HistoryImportRunner.canceled.value)
    }

    @Test fun `an automatic run with work replaces the manual summary`() = test {
        LibraryLock.mutex.lock()
        assertTrue(start())
        advanceUntilIdle()
        HistoryImportRunner.cancel()
        advanceUntilIdle()
        LibraryLock.mutex.unlock()
        assertTrue(HistoryImportRunner.canceled.value)
        fx.fits(1)
        assertTrue(start(auto = true))
        advanceUntilIdle()
        assertEquals(1, HistoryImportRunner.progress.value?.total)
        assertEquals(ImportProgress.Phase.DONE, HistoryImportRunner.progress.value?.phase)
        assertFalse("the stale cancel line must not sit under the new progress", HistoryImportRunner.canceled.value)
    }

    @Test fun `a rebuild that re-imports nothing still owes the model it stranded`() = test {
        TrackStore(fx.tracksDir).add(fx.ride("r1"))
        TrackStore(fx.tracksDir).add(fx.ride("r2"))
        fx.fits(2)
        HistoryImportRunner.depsForTest = { fx.deps(decode = { null }) }
        HistoryImportRunner.rebuildAll(ctx, cm, lastScanEpoch = 0L)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("fixture must archive", TrackStore(fx.tracksDir).allTrackIds().isEmpty())
        // The no-op hand-back must keep the pre-archive debt, so the model is rebuilt over the emptied library.
        assertTrue(File(fx.tracksDir, "gradepace.json").isFile)
        assertFalse(fx.cfg.value.gradeModelDirty)
    }

    @Test fun `a rebuild that cannot record the model debt does not archive`() = test {
        TrackStore(fx.tracksDir).add(fx.ride("r1"))
        fx.fits(1)
        var first = true
        HistoryImportRunner.depsForTest = {
            val d = fx.deps(decode = { null })
            d.copy(updateConfig = { t -> if (first) { first = false; false } else d.updateConfig(t) })
        }
        HistoryImportRunner.rebuildAll(ctx, cm, lastScanEpoch = 0L)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("r1"), TrackStore(fx.tracksDir).allTrackIds())
    }
}
