package com.enderthor.kghost.fake

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.datatype.GapStreamDataType
import com.enderthor.kghost.engine.GapStateHolder
import fi.nikosavola.karooext.testing.StreamRecorder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boots the real extension against a fake Karoo and drives Recording → Idle → Recording: the second
 * ride's collectors must be alive, and nothing from the first ride may stay on the map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowNoAllFilesAccess::class])
class RecordingIdleRecordingFakeKarooTest {
    private val karoo = FakeKarooRule()
    private val kghost = KGhostFakeKarooRule(karoo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(kghost).around(karoo)

    // A straight ~3.3 km route due north; the rider rides along it at 8 m/s (the ghost's target is
    // 20 km/h, so the rider pulls ahead and the marker trails behind on the route).
    private val lat0 = 41.0
    private val lng0 = 2.0
    private val metresPerDegLat = 111_320.0
    private fun along(distanceM: Double) = (lat0 + distanceM / metresPerDegLat) to lng0
    private val route = (0..30).map { along(it * 110.0) }

    private val config = KGhostConfig(
        // Else a real HTTP request after 45 s of looper time.
        updateCheckEnabled = false,
        fileLogging = false,
    )

    private fun StreamRecorder.lastState(): StreamState? = items.lastOrNull()
    private fun StreamRecorder.streamingValue(): Double? =
        (lastState() as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SINGLE)
    private fun ghostVisible() = GHOST in kghost.host.visibleSymbols()

    private fun goIdle(gap: StreamRecorder) {
        karoo.system.setRideState(RideState.Idle)
        karoo.awaitValue { true.takeIf { !GapStateHolder.state.value.active } }
        karoo.awaitValue { true.takeIf { gap.lastState() == StreamState.Searching } }
        karoo.awaitValue { true.takeIf { !ghostVisible() } }
    }

    /** Ride 2: Recording again, no route. Returns the distinct gap values streamed during the ride. */
    private fun rideWithoutRoute(gap: StreamRecorder): Set<Double> {
        val mark = gap.mark()
        karoo.system.setRideState(RideState.Recording)
        val ride2 = kghost.Ride(::along, speedMs = 8.0)
        ride2.until(maxSteps = 40) { gap.streamingValue()?.takeIf { gap.items.size > mark } }
        // A few more seconds: a live tick keeps publishing a changing gap.
        repeat(5) { ride2.step() }
        assertTrue("gap must be active on the second ride", GapStateHolder.state.value.active)
        return gap.items.drop(mark)
            .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SINGLE) }
            .toSet()
    }

    private fun rideWithRoute(): StreamRecorder {
        kghost.seed(config)
        kghost.startExtension()
        val gap = kghost.host.startStream(GapStreamDataType.TYPE_ID_TIME)
        kghost.host.startMap()
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setRoute(route, name = "Fake straight")
        val ride1 = kghost.Ride(::along, speedMs = 8.0)
        ride1.until(maxSteps = 90) { true.takeIf { gap.streamingValue() != null && ghostVisible() } }
        return gap
    }

    @Test
    fun `second ride after Idle streams a live gap and leaves no first-ride ghost on the map`() {
        val gap = rideWithRoute()

        goIdle(gap)
        // Ride 2 has no route: the route ends while idle and its debounced teardown runs.
        karoo.system.setNavigation(OnNavigationState.NavigationState.Idle)
        RobolectricPump.advanceBy(5.seconds)

        val values = rideWithoutRoute(gap)
        assertTrue("the second ride's gap must change over time, got $values", values.size >= 2)
        assertFalse("no first-ride ghost on the map during the second ride", ghostVisible())
        assertEquals(emptyMap<String, Any>(), kghost.host.visibleSymbols())
    }

    @Test
    fun `a host reconnect around ride end leaves the next ride's collectors working`() {
        val gap = rideWithRoute()

        karoo.system.setNavigation(OnNavigationState.NavigationState.Idle)
        karoo.system.setRideState(RideState.Idle)
        // Rebind the host right after ride end: onConnected runs again on the same instance, the replayed
        // Idle hides the ghost, and the next ride's collectors work. This does NOT pin a2b52b3's drain
        // window (the reconnect is not guaranteed to land inside stopTickAndJoin/drainRetiredTicks; that
        // needs a main-code seam). AwaitRetiredTickTest covers that path at unit level.
        RobolectricPump.pumpMainLooper()
        kghost.reconnect()
        karoo.awaitValue { true.takeIf { RideState.Params in karoo.system.consumerParams } }
        karoo.awaitValue { true.takeIf { gap.lastState() == StreamState.Searching } }
        karoo.awaitValue { true.takeIf { !ghostVisible() } }
        RobolectricPump.advanceBy(5.seconds)

        val values = rideWithoutRoute(gap)
        assertTrue("the second ride's gap must change over time, got $values", values.size >= 2)
        assertFalse("no first-ride ghost on the map during the second ride", ghostVisible())
        assertEquals(emptyMap<String, Any>(), kghost.host.visibleSymbols())
    }

    private companion object {
        const val GHOST = "kghost-ghost"
    }
}
