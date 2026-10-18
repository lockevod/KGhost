package com.enderthor.kghost.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Models onConnected / startTick / stopTickAndJoin through the same helpers and list semantics. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitRetiredTickTest {

    private val retired = mutableListOf<Job>()

    /** A retired tick whose tail ignores cancellation, like the tick's non-suspending tail after a reconnect. */
    private fun TestScope.stubbornTick(release: CompletableDeferred<Unit>, done: () -> Unit): Job {
        val a = launch { withContext(NonCancellable) { release.await(); done() } }
        advanceUntilIdle()
        a.cancel()
        retired += a // onConnected
        return a
    }

    /** startTick: snapshot then launch a tick whose first statement awaits the snapshot. */
    private fun TestScope.startTick(body: () -> Unit): Job {
        val pending = snapshotRetiredTicks(retired)
        return launch { awaitRetiredTicks(pending); body() }
    }

    /** Double reconnect: A retired but still running, B (waiting on A) cancelled, C must still wait for A. */
    @Test
    fun cancelledReplacementStillWaitsForTheTickItInherited() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        var aDone = false
        stubbornTick(releaseA) { aDone = true }

        var bBody = false
        val b = startTick { bBody = true }
        advanceUntilIdle()
        b.cancel(); retired += b // second reconnect

        var cStartedWhileARunning = false
        var cBody = false
        startTick { cStartedWhileARunning = !aDone; cBody = true }
        advanceUntilIdle()
        try {
            assertFalse("C must not start while A runs", cBody)
            assertFalse("cancelled B must not complete while A runs", b.isCompleted)
        } finally {
            releaseA.complete(Unit) // A is NonCancellable: without this a failure would hang runTest
        }
        advanceUntilIdle()
        assertTrue(cBody)
        assertFalse(cStartedWhileARunning)
        assertFalse("cancelled B must not run the tick body", bBody)
        assertTrue(b.isCancelled)
    }

    /** B is cancelled by a second reconnect BEFORE its body (and so its await) ever starts: C must still wait for A. */
    @Test
    fun replacementCancelledBeforeItStartsDoesNotHideItsPredecessor() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        var aDone = false
        stubbornTick(releaseA) { aDone = true }

        var bBody = false
        val b = startTick { bBody = true }
        b.cancel(); retired += b // second reconnect before B is ever dispatched
        advanceUntilIdle()
        assertTrue("B never started, so it is already completed", b.isCompleted)

        var cBody = false
        var cStartedWhileARunning = false
        startTick { cStartedWhileARunning = !aDone; cBody = true }
        advanceUntilIdle()
        try {
            assertFalse("C must not start while A runs", cBody)
        } finally {
            releaseA.complete(Unit)
        }
        advanceUntilIdle()
        assertTrue(cBody)
        assertFalse(cStartedWhileARunning)
        assertFalse(bBody)
        assertEquals("completed ticks are dropped at the next snapshot", 0, snapshotRetiredTicks(retired).size)
    }

    /** Idle path: a cancel arriving while stopTickAndJoin drains must not let it return while A still runs. */
    @Test
    fun cancelledDrainStillWaitsForTheRetiredTick() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        var aDone = false
        stubbornTick(releaseA) { aDone = true }

        var drainReturnedWhileARunning = false
        val idle = launch { drainRetiredTicks(retired); drainReturnedWhileARunning = !aDone }
        advanceUntilIdle()
        idle.cancel() // reconnect cancels the Idle handler mid-join
        advanceUntilIdle()
        try {
            assertFalse("the Idle path must not finish while A runs", idle.isCompleted)
        } finally {
            releaseA.complete(Unit)
        }
        advanceUntilIdle()
        assertTrue(idle.isCompleted)
        assertFalse(drainReturnedWhileARunning)
        assertTrue("finished ticks are forgotten", retired.isEmpty())
    }
}
