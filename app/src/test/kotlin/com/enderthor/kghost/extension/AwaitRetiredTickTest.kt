package com.enderthor.kghost.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitRetiredTickTest {

    /** Double reconnect: A retired but still running, B (waiting on A) cancelled, C joins only B. */
    @Test
    fun cancelledReplacementStillWaitsForTheTickItInherited() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        var aDone = false
        // A's tail ignores cancellation, like the tick's non-suspending tail after a reconnect cancel.
        val a = launch { withContext(NonCancellable) { releaseA.await(); aDone = true } }
        advanceUntilIdle()
        a.cancel()

        var bBody = false
        val b = launch { awaitRetiredTick(a); bBody = true }
        advanceUntilIdle()
        b.cancel() // second reconnect

        var cStartedWhileARunning = false
        var cBody = false
        launch { awaitRetiredTick(b); cStartedWhileARunning = !aDone; cBody = true }
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
}
