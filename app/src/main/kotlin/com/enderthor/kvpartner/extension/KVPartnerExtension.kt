package com.enderthor.kvpartner.extension

import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.datatype.GapGraphicDataType
import com.enderthor.kvpartner.datatype.GapNumericDataType
import com.enderthor.kvpartner.engine.DistanceProgress
import com.enderthor.kvpartner.engine.GapCalculator
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.engine.GhostCurve
import com.enderthor.kvpartner.engine.RenderPrefs
import com.enderthor.kvpartner.engine.VirtualPartnerSource
import com.enderthor.kvpartner.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Central orchestrator for the KVPartner extension.
 *
 * Connects the [KarooSystemService] streams to the pure gap engine and publishes results to
 * [GapStateHolder]:
 *  - Subscribes to [RideState]. Work only happens while `Recording`. `Idle` stops the tick and
 *    clears the state; `Paused` freezes the tick (the time clock is driven by `ELAPSED_TIME`,
 *    which the ride app pauses on its own, so there is nothing to reset).
 *  - While recording, runs a ~1 Hz tick that combines the `DISTANCE` and `ELAPSED_TIME` streams,
 *    feeds them into [DistanceProgress] + [GapCalculator], and publishes the resulting
 *    [com.enderthor.kvpartner.engine.GapState].
 *  - Subscribes to the navigation state. In sub-project ① this is logging only — it is the hook
 *    for sub-project ② (route ghost).
 *
 * All work runs on `Dispatchers.Main + SupervisorJob` owned by this service.
 */
class KVPartnerExtension : KarooExtension("kvpartner", "0.1.0") {

    companion object {
        @Volatile
        var instance: KVPartnerExtension? = null
            private set

        /** Tick cadence. The ride app advances its record timer at ~1 Hz. */
        private const val REFRESH_MS = 1000L
    }

    lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var configManager: ConfigurationManager
    private val activeConfig = MutableStateFlow(KVPartnerConfig())
    private var tickJob: Job? = null

    // The two on-screen data fields rendering the GapState. typeIds must match
    // extension_info.xml exactly ("kvpartner-gap" and "kvpartner-gap-num").
    override val types by lazy {
        listOf(
            GapGraphicDataType(applicationContext),
            GapNumericDataType(applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigurationManager(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        // loadConfigFlow() already applies migrateToLatest(); no need to migrate again here.
        configManager.loadConfigFlow().onEach { activeConfig.value = it }.launchIn(scope)
        // Feed the render-prefs holder so the data fields don't each open their own DataStore
        // subscription just to read gapDisplay (single writer here; fields are readers).
        activeConfig
            .map { it.gapDisplay }
            .distinctUntilChanged()
            .onEach { RenderPrefs.setGapDisplay(it) }
            .launchIn(scope)
        karooSystem.connect { connected -> if (connected) onConnected() }
    }

    private fun onConnected() {
        karooSystem.streamRide().onEach { state ->
            when (state) {
                is RideState.Recording -> startTick()
                is RideState.Paused -> {
                    // The clock is tied to ELAPSED_TIME, which the ride app already pauses.
                    // Freeze the tick by leaving it running but receiving no emissions; do not reset.
                }
                is RideState.Idle -> stopTick()
                else -> {}
            }
        }.launchIn(scope)
        // Hook for sub-project ② (route ghost); logging only in sub-project ①.
        karooSystem.streamNavigationState().onEach { Timber.d("nav=${it.state}") }.launchIn(scope)
    }

    // `.sample()` is a @FlowPreview API; opting in here (same convention as KSafe's LocationManager).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startTick() {
        if (tickJob?.isActive == true) return
        val progress = DistanceProgress()
        // Cache the ghost curve and rebuild it only when the target speed changes.
        // VirtualPartnerSource.curve() allocates a fresh curve on every call, so building it
        // inside the 1 Hz tick would churn a curve per second; instead we remember the target
        // it was built for and recompute lazily when that target changes.
        var cachedCurve: GhostCurve? = null
        var cachedTargetMs: Double? = null
        tickJob = scope.launch {
            val distance = karooSystem.streamDataFlow(DataType.Type.DISTANCE)
            val elapsed = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
            combine(distance, elapsed) { d, e -> d to e }
                .sample(REFRESH_MS) // rate-limit BEFORE conflate so we tick at most once per REFRESH_MS
                .conflate()
                .onEach { (d, e) ->
                    val target = activeConfig.value.validTargetOrNull()
                    if (target == null) {
                        GapStateHolder.clear()
                        return@onEach
                    }
                    // DISTANCE is in metres.
                    val distM = (d as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@onEach
                    // ELAPSED_TIME is delivered in milliseconds by karoo-ext, so convert to seconds.
                    // GapCalculator expects elapsed seconds. If field testing shows the SDK already
                    // delivers seconds, drop the divide-by-1000 in [elapsedMsToSeconds].
                    val elapsedRaw = (e as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@onEach
                    val elapsedS = elapsedMsToSeconds(elapsedRaw)
                    progress.onDistance(distM)
                    if (cachedTargetMs != target || cachedCurve == null) {
                        cachedCurve = VirtualPartnerSource(target).curve()
                        cachedTargetMs = target
                    }
                    GapStateHolder.update(
                        GapCalculator.compute(progress.progressM, elapsedS, cachedCurve!!, progress.isFresh),
                    )
                }.collect {}
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
        GapStateHolder.clear()
    }

    override fun onDestroy() {
        stopTick()
        scope.coroutineContext[Job]?.cancel()
        if (::karooSystem.isInitialized) karooSystem.disconnect()
        instance = null
        super.onDestroy()
    }
}

/**
 * Converts a raw `ELAPSED_TIME` reading (milliseconds, per karoo-ext 1.1.9) to seconds.
 *
 * Isolated as a pure top-level helper so the unit assumption is documented in one place and is a
 * one-line change if device testing ever shows the SDK delivers a different unit.
 */
internal fun elapsedMsToSeconds(rawMs: Double): Double = rawMs / 1000.0
