package com.enderthor.kghost.extension

import com.enderthor.kghost.data.KGhostConfig
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDiscoveryTest {

    private class Fixture(scope: TestScope, initial: RideState?, access: Boolean = true, active: Boolean = false) {
        val state = MutableStateFlow(initial)
        val runActive = MutableStateFlow(active)
        var access = access
        var master = true
        val admits = mutableListOf<suspend () -> Boolean>()
        var cancels = 0
        val discovery = AutoDiscovery(
            scope = scope.backgroundScope,
            rideState = state,
            runActive = runActive,
            hasAccess = { this.access },
            masterEnabled = { master },
            start = { admit -> admits += admit; true },
            cancelAuto = { cancels++ },
        )
    }

    @Test
    fun `shouldAutoImport truth table`() {
        assertFalse(shouldAutoImport(null, true))
        assertFalse(shouldAutoImport(RideState.Recording, true))
        assertFalse(shouldAutoImport(RideState.Paused(false), true))
        assertFalse(shouldAutoImport(RideState.Idle, false))
        assertTrue(shouldAutoImport(RideState.Idle, true))
    }

    @Test
    fun `app resume during a ride admits nothing, ride end admits`() = runTest {
        val f = Fixture(this, RideState.Recording)
        runCurrent()
        f.discovery.request("app-resume")
        assertFalse(f.admits.last()())
        f.state.value = RideState.Idle
        runCurrent()
        f.discovery.request("app-resume")
        assertTrue(f.admits.last()())
    }

    @Test
    fun `admit re-reads state after waiting`() = runTest {
        val f = Fixture(this, RideState.Idle)
        runCurrent()
        f.discovery.request("app-resume")
        val admit = f.admits.last()
        f.state.value = RideState.Recording
        assertFalse(admit())
    }

    @Test
    fun `admit re-reads access after waiting`() = runTest {
        val f = Fixture(this, RideState.Idle)
        runCurrent()
        val admit = f.admits.last()
        f.access = false
        assertFalse(admit())
    }

    @Test
    fun `a stale Idle decision is cancelled when the ride starts`() = runTest {
        val f = Fixture(this, RideState.Idle)
        runCurrent()
        assertTrue(f.admits.last()())
        f.state.value = RideState.Recording
        runCurrent()
        assertEquals(1, f.cancels)
    }

    @Test
    fun `the first Idle after startup requests discovery`() = runTest {
        val f = Fixture(this, null)
        runCurrent()
        assertEquals(0, f.admits.size)
        f.state.value = RideState.Idle
        runCurrent()
        assertEquals(1, f.admits.size)
        f.state.value = RideState.Recording
        runCurrent()
        f.state.value = RideState.Idle
        runCurrent()
        assertEquals(2, f.admits.size)
    }

    @Test
    fun `a request while a run is active is deferred, not lost`() = runTest {
        val f = Fixture(this, null, active = true)
        runCurrent()
        f.discovery.request("app-resume")
        assertEquals(0, f.admits.size)
        f.state.value = RideState.Idle
        runCurrent()
        assertEquals(0, f.admits.size)
        f.runActive.value = false
        runCurrent()
        assertEquals(1, f.admits.size)
        assertTrue(f.admits.single()())
    }

    @Test
    fun `a refused start is remembered like a deferral`() = runTest {
        val state = MutableStateFlow<RideState?>(RideState.Idle)
        val runActive = MutableStateFlow(false)
        var accept = false
        var accepted = 0
        // A refusal means the previous run has not completed, so the runner's flag is still true.
        val start = { _: suspend () -> Boolean -> if (accept) accepted++ else runActive.value = true; accept }
        AutoDiscovery(backgroundScope, state, runActive, { true }, { true }, start, {})
        runCurrent()
        assertEquals(0, accepted) // the Idle request, refused by single flight
        accept = true
        runActive.value = false
        runCurrent()
        assertEquals(1, accepted)
    }

    @Test
    fun `paused cancels too`() = runTest {
        val f = Fixture(this, RideState.Idle)
        runCurrent()
        f.state.value = RideState.Paused(false)
        runCurrent()
        assertEquals(1, f.cancels)
    }

    @Test
    fun `found rides are only shown after the clear persisted`() = runTest {
        var stored = KGhostConfig(pendingFoundRides = 4)
        // The transform runs (it reads N) but the write fails, like a DataStore IO error.
        assertNull(consumeFoundRides({ stored }) { t -> t(stored); false })
        assertEquals(4, stored.pendingFoundRides)

        assertEquals(4, consumeFoundRides({ stored }) { t -> stored = t(stored); true })
        assertEquals(0, stored.pendingFoundRides)

        var writes = 0
        assertNull(consumeFoundRides({ stored }) { t -> writes++; stored = t(stored); true })
        assertEquals(0, writes)
    }

    @Test
    fun `the master switch off admits nothing`() = runTest {
        val f = Fixture(this, RideState.Idle)
        runCurrent()
        f.master = false
        assertFalse(f.admits.last()())
    }

    @Test
    fun `found rides wait while the master switch is off`() = runTest {
        var stored = KGhostConfig(pendingFoundRides = 2, masterEnabled = false)
        var writes = 0
        assertNull(consumeFoundRides({ stored }) { t -> writes++; stored = t(stored); true })
        assertEquals(0, writes)
        assertEquals(2, stored.pendingFoundRides)
    }

    @Test
    fun `a deferral survives a run that ends before the collector sees it`() = runTest {
        val f = Fixture(this, null)
        runCurrent()
        // The whole run happens between two dispatches: a collector that only reacts to a true→false
        // emission never sees one, because the flag is back to the value it last observed.
        f.runActive.value = true
        f.discovery.request("app-resume")
        f.runActive.value = false
        runCurrent()
        assertEquals(1, f.admits.size)
    }

    @Test
    fun `found rides are announced only on a fresh ride start`() {
        assertTrue(isFreshRideStart(null, RideState.Recording))
        assertTrue(isFreshRideStart(RideState.Idle, RideState.Recording))
        assertFalse(isFreshRideStart(RideState.Paused(false), RideState.Recording))
        assertFalse(isFreshRideStart(RideState.Recording, RideState.Recording))
        assertFalse(isFreshRideStart(RideState.Idle, RideState.Paused(false)))
    }
}
