package com.enderthor.kghost.fake

import android.app.Application
import android.content.ComponentName
import androidx.datastore.preferences.core.edit
import com.enderthor.kghost.data.GapDisplay
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.engine.GapStateHolder
import com.enderthor.kghost.engine.RenderPrefs
import com.enderthor.kghost.engine.SegmentInfoHolder
import com.enderthor.kghost.extension.KGhostExtension
import com.enderthor.kghost.managers.CONFIG_MIRROR_FILE
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.managers.dataStore
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_PACKAGE
import fi.nikosavola.karooext.testing.KAROO_SYSTEM_SERVICE
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import timber.log.Timber

/**
 * App-side rule for booting the real [KGhostExtension] against karoo-ext-testing's fake Karoo.
 * Chain it OUTSIDE the [FakeKarooRule]: `RuleChain.outerRule(kghost).around(karoo)`.
 */
class KGhostFakeKarooRule(val karoo: FakeKarooRule) : ExternalResource() {
    private val app: Application get() = karoo.app

    override fun before() {
        // Process-wide statics survive between tests in the same Robolectric sandbox.
        GapStateHolder.clear()
        SegmentInfoHolder.clear()
        RenderPrefs.setGapDisplay(GapDisplay.BOTH)
        RenderPrefs.setImperialDistance(false)
        runBlocking { app.dataStore.edit { it.clear() } }
        // The config is restored from this mirror when the DataStore key is absent.
        File(app.filesDir, CONFIG_MIRROR_FILE).delete()
    }

    override fun after() {
        // KGhostApplication plants its trees on every Robolectric app creation.
        Timber.uprootAll()
    }

    fun seed(cfg: KGhostConfig) = runBlocking {
        check(ConfigurationManager(app).updateConfig { cfg }) { "config seed failed" }
    }

    lateinit var host: FakeKarooHost
        private set

    /** RideState is subscribed in onConnected, after the binder connects. */
    fun startExtension(): FakeKarooHost {
        host = karoo.host<KGhostExtension>()
        karoo.awaitValue { true.takeIf { RideState.Params in karoo.system.consumerParams } }
        return host
    }

    /**
     * A host-side rebind on the SAME service instance, as when the Karoo system app restarts: the SDK's
     * ServiceConnection sees onServiceDisconnected then onServiceConnected, so the extension's
     * connect{} callback (and its onConnected) runs a second time. The system end drops the dead
     * binding's consumers; the SDK re-registers its listeners on reconnect.
     */
    fun reconnect() {
        val component = ComponentName(KAROO_SYSTEM_PACKAGE, KAROO_SYSTEM_SERVICE)
        val connections = shadowOf(app).boundServiceConnections
        check(connections.size == 1) { "expected one bound KarooSystem connection, got ${connections.size}" }
        val connection = connections.single()
        connection.onServiceDisconnected(component)
        karoo.system.consumerIds.forEach(karoo.system::removeEventConsumer)
        connection.onServiceConnected(component, karoo.system)
    }

    /** Odometer state of the simulated ride; [step] advances it by one second. */
    inner class Ride(
        private val latLng: (distanceM: Double) -> Pair<Double, Double>,
        private val speedMs: Double,
    ) {
        var distanceM = 0.0
            private set
        private var elapsedMs = 0.0

        /**
         * One simulated second: DISTANCE (m), ELAPSED_TIME (ms), SPEED (m/s) and a fresh GPS fix, then
         * about one second of real time, since the tick samples its combined streams on the real clock.
         * The looper moves with it so SystemClock-based freshness (fix age) stays close to real time.
         */
        fun step() {
            distanceM += speedMs
            elapsedMs += 1000.0
            val system = karoo.system
            system.setDataPoint(DataPoint(DataType.Type.DISTANCE, mapOf(DataType.Field.DISTANCE to distanceM)))
            system.setDataPoint(DataPoint(DataType.Type.ELAPSED_TIME, mapOf(DataType.Field.ELAPSED_TIME to elapsedMs)))
            system.setDataPoint(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SPEED to speedMs)))
            val (lat, lng) = latLng(distanceM)
            system.setLocation(lat, lng, orientation = 0.0)
            repeat(10) {
                RobolectricPump.advanceBy(100.milliseconds)
                Thread.sleep(100)
            }
        }

        /** Steps until [probe] is non-null, failing with the fake's state after [maxSteps]. */
        fun <T : Any> until(maxSteps: Int, probe: () -> T?): T {
            repeat(maxSteps) {
                probe()?.let { return it }
                step()
            }
            return probe() ?: error(
                "not reached within $maxSteps ride seconds (at ${distanceM}m)\n" +
                    "${karoo.system.describe()}\nhost: ${host.describe()}",
            )
        }
    }
}
