package com.enderthor.kghost.extension

import com.enderthor.kghost.engine.GhostCheckpoint
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.geo.atomicWriteText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real threads (Dispatchers.IO), not runTest's virtual dispatcher: the writer blocks on a latch, which
 *  would deadlock a single-threaded test scheduler. Every latch wait is bounded so a broken impl fails. */
class GhostCheckpointFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cp(routeKey: String, leadS: Double = 10.0) = GhostCheckpoint(
        rideEpoch = 1L, leadS = leadS, lastRiderDist = 1000.0, pick = GhostPick.BEST,
        vpTimePerM = 0.0, savedAtEpoch = 1L, routeKey = routeKey,
    )

    private fun CountDownLatch.awaitOrFail() = assertTrue("latch timed out", await(5, TimeUnit.SECONDS))

    @Test
    fun `invalidate while a flush is paused inside the lock leaves no file`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = File(tmp.root, "cp.json")
        val store = GhostCheckpointFile(target, Mutex(), write = { f, s ->
            entered.countDown()
            release.awaitOrFail()
            atomicWriteText(f, s, fsync = false)
        })
        store.pending = cp("A")
        val flush = launch(Dispatchers.IO) { store.flush() }
        entered.awaitOrFail()
        // Correct impl: suspends at the held mutex. An unlocked delete would run NOW, before the write.
        val inv = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { store.invalidate() }
        release.countDown()
        flush.join(); inv.join()
        assertFalse(target.exists())
    }

    @Test
    fun `a flush that read nothing before the lock cannot resurrect`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = File(tmp.root, "cp.json")
        val store = GhostCheckpointFile(target, Mutex(), beforeLock = {
            entered.countDown()
            release.awaitOrFail()
        })
        store.pending = cp("A")
        val flush = launch(Dispatchers.IO) { store.flush() }
        entered.awaitOrFail()
        store.invalidate()
        release.countDown()
        flush.join()
        assertFalse(target.exists())
    }

    @Test
    fun `invalidate is awaited before a newer checkpoint is published`() = runBlocking {
        val target = File(tmp.root, "cp.json")
        val store = GhostCheckpointFile(target, Mutex())
        store.pending = cp("A"); store.flush()
        store.invalidate()
        store.pending = cp("B"); store.flush()
        assertEquals("B", store.load()?.routeKey)
    }

    @Test
    fun `flush after a new pending is set writes the new snapshot`() = runBlocking {
        val target = File(tmp.root, "cp.json")
        val store = GhostCheckpointFile(target, Mutex())
        store.pending = cp("A", leadS = 1.0); store.flush()
        store.pending = cp("A", leadS = 2.0); store.flush()
        assertEquals(2.0, store.load()!!.leadS, 0.0)
    }

    @Test
    fun `the within-300 m restart sequence restores nothing`() = runBlocking {
        val target = File(tmp.root, "cp.json")
        val store = GhostCheckpointFile(target, Mutex())
        store.pending = cp("A"); store.flush()
        store.invalidate() // post-route entry
        assertNull(store.load())
    }
}
