package com.enderthor.kvpartner.extension

import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.datatype.GapGraphicDataType
import com.enderthor.kvpartner.datatype.GapNumericDataType
import com.enderthor.kvpartner.datatype.SegmentGapDataType
import com.enderthor.kvpartner.engine.DistanceProgress
import com.enderthor.kvpartner.engine.GapCalculator
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.engine.GhostCurve
import com.enderthor.kvpartner.engine.LiveSegment
import com.enderthor.kvpartner.engine.RenderPrefs
import com.enderthor.kvpartner.engine.RouteProjectedProgress
import com.enderthor.kvpartner.engine.SegmentInfoHolder
import com.enderthor.kvpartner.engine.StalenessLogic
import com.enderthor.kvpartner.engine.VirtualPartnerSource
import com.enderthor.kvpartner.engine.toInfo
import com.enderthor.kvpartner.geo.BBox
import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.Polyline
import com.enderthor.kvpartner.geo.PolylinePath
import com.enderthor.kvpartner.geo.SegmentMatcher
import com.enderthor.kvpartner.geo.TrackRecorder
import com.enderthor.kvpartner.geo.TrackStore
import com.enderthor.kvpartner.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
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
import java.io.File

/**
 * Central orchestrator for the KVPartner extension.
 *
 * Connects the [KarooSystemService] streams to the pure gap engine and publishes results to
 * [GapStateHolder] (and, in route mode, [SegmentInfoHolder]):
 *  - Subscribes to [RideState]. Work only happens while `Recording`. `Idle` stops the tick and
 *    clears the state; `Paused` freezes the tick (the time clock is driven by `ELAPSED_TIME`,
 *    which the ride app pauses on its own, so there is nothing to reset).
 *  - While recording, runs a ~1 Hz tick that combines the `DISTANCE`, `ELAPSED_TIME` and `SPEED`
 *    streams. The tick has two modes:
 *      * **① Virtual Partner mode** (default, when no route is loaded or racing is disabled):
 *        feeds [DistanceProgress] + a cached [VirtualPartnerSource] curve into [GapCalculator].
 *      * **② Route mode** (when a navigated route is loaded and `raceEnabled`): projects the live
 *        GPS position onto the route via [RouteProjectedProgress], finds the active recorded
 *        [LiveSegment], and computes the gap against that segment's ghost. Publishes the active
 *        segment metadata to [SegmentInfoHolder].
 *  - Subscribes to the navigation state. On `NavigatingRoute` (route mode ②), it decodes the route
 *    polyline, loads candidate recorded tracks, and runs [SegmentMatcher] to build the live
 *    segments. On `Idle`/`NavigatingToDestination`, route mode is cleared and the tick falls back
 *    to ① Virtual Partner behavior.
 *  - Subscribes to the GPS location stream and records the ride history (decimated) to the
 *    [TrackStore] at ride end, when `autoRecord` is on. That history is what later route loads
 *    match against.
 *
 * All work runs on `Dispatchers.Main + SupervisorJob` owned by this service; the heavier
 * route-matching and file IO are dispatched off Main onto `Dispatchers.Default`/`Dispatchers.IO`.
 */
class KVPartnerExtension : KarooExtension("kvpartner", "0.1.0") {

    companion object {
        @Volatile
        var instance: KVPartnerExtension? = null
            private set

        /** Tick cadence. The ride app advances its record timer at ~1 Hz. */
        private const val REFRESH_MS = 1000L

        /**
         * A route segment is rendered as an elevation profile (render A) only when its elevation
         * gain over the segment is at least this many metres; below this it falls back to the
         * two-dot track render (render B). Keeps flat segments from showing a noisy flat silhouette.
         */
        private const val ELEV_GAIN_THRESHOLD_M = 30.0
    }

    lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var configManager: ConfigurationManager
    private val activeConfig = MutableStateFlow(KVPartnerConfig())
    private var tickJob: Job? = null

    // --- ② route / history state -------------------------------------------
    // On-disk store of recorded tracks (history) + the in-memory recorder for the current ride.
    private lateinit var trackStore: TrackStore
    private val recorder = TrackRecorder()

    // Latest GPS fix from the location stream. Read by both the recorder and the route projector
    // on the tick. @Volatile because the location collector and the tick may run on different
    // dispatcher threads. NaN until the first finite fix arrives (finite-guarded on write).
    @Volatile
    private var lastLat: Double = Double.NaN
    @Volatile
    private var lastLng: Double = Double.NaN

    // Wall-clock epoch captured when the current Recording started. Used as the recorded track's
    // id and startedAtEpoch (this is the live service, not a deterministic workflow).
    @Volatile
    private var recordingStartedEpoch: Long = 0L

    // Route mode state. When [routePath] is non-null AND [liveSegments] is non-empty the tick runs
    // the per-segment ② logic; otherwise it runs the ① Virtual Partner logic. Written by the
    // navigation-state collector (off Main), read by the tick — hence @Volatile.
    @Volatile
    private var routePath: PolylinePath? = null
    @Volatile
    private var liveSegments: List<LiveSegment> = emptyList()

    // The on-screen data fields rendering the GapState. typeIds must match extension_info.xml
    // exactly ("kvpartner-gap", "kvpartner-gap-num" and "kvpartner-segment").
    override val types by lazy {
        listOf(
            GapGraphicDataType(applicationContext),
            GapNumericDataType(applicationContext),
            SegmentGapDataType(applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigurationManager(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        trackStore = TrackStore(File(applicationContext.filesDir, "tracks"))
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
        // Keep the latest GPS fix available for the recorder and the route projector. Finite-guard
        // on write so the tick/recorder never sees a NaN/±Inf coordinate. Tied to [scope] → torn
        // down in onDestroy.
        karooSystem.streamLocation().onEach { loc ->
            val lat = loc.lat
            val lng = loc.lng
            if (lat.isFinite() && lng.isFinite()) {
                lastLat = lat
                lastLng = lng
            }
        }.launchIn(scope)

        karooSystem.streamRide().onEach { state ->
            when (state) {
                is RideState.Recording -> startTick()
                is RideState.Paused -> {
                    // The clock is tied to ELAPSED_TIME, which the ride app already pauses.
                    // Freeze the tick by leaving it running but receiving no emissions; do not reset.
                }
                is RideState.Idle -> {
                    stopTick()
                    finishAndSaveRecording()
                }
                else -> {}
            }
        }.launchIn(scope)

        // ② route load → match recorded history into live segments; clear on non-route states.
        karooSystem.streamNavigationState().onEach { onNavigationState(it) }.launchIn(scope)
    }

    /**
     * Reacts to a navigation-state change. On [OnNavigationState.NavigationState.NavigatingRoute]
     * (and only when racing is enabled) it decodes the route, loads candidate tracks, runs the
     * matcher, fills the per-segment elevation profile, and switches the tick to route mode. Any
     * other state (Idle / NavigatingToDestination) clears route mode → falls back to ① VP.
     */
    private fun onNavigationState(event: OnNavigationState) {
        val state = event.state
        Timber.d("nav=$state")
        if (state is OnNavigationState.NavigationState.NavigatingRoute && activeConfig.value.raceEnabled) {
            val routePolyline = state.routePolyline
            val elevPolyline = state.routeElevationPolyline
            // Off Main: polyline decode, candidate file IO, and segment matching are all heavier
            // than a frame. Default is fine; loadCandidates does file IO but never overlaps a save
            // in practice (save runs at ride-end, matching at route-load).
            scope.launch(Dispatchers.Default) {
                runCatching {
                    val path = PolylinePath(Polyline.decode(routePolyline))
                    val bbox = BBox.around(path.points)
                        ?: return@launch clearRouteMode()
                    val tracks = trackStore.loadCandidates(bbox)
                    val matched = SegmentMatcher.match(
                        path,
                        tracks,
                        activeConfig.value.ghostPick,
                        SegmentMatcher.Params(),
                    )
                    val withElevation = applyElevation(matched, elevPolyline)
                    routePath = path
                    liveSegments = withElevation
                    Timber.d("route mode ON: ${withElevation.size} segment(s) on '${state.name}'")
                }.onFailure { e ->
                    Timber.w(e, "route matching failed; staying in ① VP mode")
                    clearRouteMode()
                }
            }
        } else {
            clearRouteMode()
        }
    }

    /** Clears ② route mode so the tick falls back to ① Virtual Partner behavior. */
    private fun clearRouteMode() {
        routePath = null
        liveSegments = emptyList()
        SegmentInfoHolder.clear()
    }

    /**
     * Fills [LiveSegment.hasElevation]/[LiveSegment.elevationProfile] for each segment from the
     * route's elevation polyline. The elevation polyline is a Google-encoded polyline at
     * **precision 1** whose decoded points carry (distanceAlongRouteM, altitudeM) in their
     * (lat, lng) fields. For each segment we keep the samples whose distance-along-route lands in
     * `[routeStartM, routeEndM]`, rebase their distance to be segment-relative, and set
     * `hasElevation` when the gain over that window is at least [ELEV_GAIN_THRESHOLD_M]. On any
     * decode failure or an absent polyline the segments keep `hasElevation = false`.
     */
    private fun applyElevation(
        segments: List<LiveSegment>,
        elevationPolyline: String?,
    ): List<LiveSegment> {
        if (segments.isEmpty()) return segments
        // (distanceAlongRouteM, altitudeM) pairs, ascending by distance.
        val elev: List<Pair<Double, Double>> = if (elevationPolyline.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching {
                Polyline.decode(elevationPolyline, precision = 1)
                    .map { it.lat to it.lng } // lat = distanceAlongRoute, lng = altitude
                    .filter { it.first.isFinite() && it.second.isFinite() }
                    .sortedBy { it.first }
            }.getOrElse {
                Timber.w(it, "elevation polyline decode failed; segments stay hasElevation=false")
                emptyList()
            }
        }
        if (elev.isEmpty()) return segments

        return segments.map { seg ->
            val inSeg = elev
                .filter { it.first in seg.routeStartM..seg.routeEndM }
                .map { (distAlong, alt) -> (distAlong - seg.routeStartM) to alt } // segment-relative distance
            if (inSeg.size < 2) {
                seg.copy(hasElevation = false, elevationProfile = null)
            } else {
                val gain = inSeg.maxOf { it.second } - inSeg.minOf { it.second }
                seg.copy(
                    hasElevation = gain >= ELEV_GAIN_THRESHOLD_M,
                    elevationProfile = inSeg,
                )
            }
        }
    }

    // `.sample()` is a @FlowPreview API; opting in here (same convention as KSafe's LocationManager).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startTick() {
        if (tickJob?.isActive == true) return
        recordingStartedEpoch = System.currentTimeMillis()
        // ① Virtual Partner state (used when route mode is OFF).
        val progress = DistanceProgress()
        // Cache the ghost curve and rebuild it only when the target speed changes.
        // VirtualPartnerSource.curve() allocates a fresh curve on every call, so building it
        // inside the 1 Hz tick would churn a curve per second; instead we remember the target
        // it was built for and recompute lazily when that target changes.
        var cachedCurve: GhostCurve? = null
        var cachedTargetMs: Double? = null

        // ② route-mode per-tick state.
        // A RouteProjectedProgress bound to the currently loaded route. Rebuilt when the route
        // identity changes (different PolylinePath instance) so it never projects against a stale
        // route. Null when no route is loaded.
        var routeProjector: RouteProjectedProgress? = null
        var projectorRoute: PolylinePath? = null
        // The route-distance start of the segment currently being raced, used to detect when the
        // active segment changes (to reset the per-segment entry clock). null = no active segment.
        var activeSegmentStartM: Double? = null
        // Ride-elapsed seconds at which the rider first entered the active segment. The per-segment
        // gap clock is `elapsedS - segmentEntryElapsedS`.
        var segmentEntryElapsedS = 0.0

        tickJob = scope.launch {
            val distance = karooSystem.streamDataFlow(DataType.Type.DISTANCE)
            val elapsed = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
            // SPEED (m/s) is streamed to distinguish "stopped at a light" (frozen distance is
            // legitimate) from "GPS lost while moving" (frozen distance is wrong → blank to `---`).
            val speed = karooSystem.streamDataFlow(DataType.Type.SPEED)
            combine(distance, elapsed, speed) { d, e, sp -> Triple(d, e, sp) }
                .sample(REFRESH_MS) // rate-limit BEFORE conflate so we tick at most once per REFRESH_MS
                .conflate()
                .onEach { (d, e, sp) ->
                    // DISTANCE is in metres. Drop non-finite values (NaN/±Inf) so they never reach
                    // the gap engine.
                    val distM = (d as? StreamState.Streaming)?.dataPoint?.singleValue
                        ?.takeIf { it.isFinite() } ?: return@onEach
                    // ELAPSED_TIME is delivered in milliseconds by karoo-ext, so convert to seconds.
                    // GapCalculator expects elapsed seconds. If field testing shows the SDK already
                    // delivers seconds, drop the divide-by-1000 in [elapsedMsToSeconds].
                    val elapsedRaw = (e as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@onEach
                    val elapsedS = elapsedMsToSeconds(elapsedRaw).takeIf { it.isFinite() } ?: return@onEach
                    // SPEED in m/s — raw magnitude, NOT value-change freshness. A legitimately
                    // constant speed (a rider settled at a steady 0.0 after stopping >3 s at a light)
                    // never "changes", so value-change freshness would wrongly age it out and blank a
                    // valid gap. Magnitude classifies correctly: a frozen-HIGH speed (still moving, or
                    // GPS lost while moving) reads as moving → not trustworthy → blank; a LOW/zero
                    // speed reads as stopped → trustworthy → gap stays visible. Null (stream not yet
                    // emitted or non-finite) means we cannot prove a stop → not trustworthy.
                    val speedMs = (sp as? StreamState.Streaming)?.dataPoint?.singleValue
                        ?.takeIf { it.isFinite() }

                    // History recording: feed the decimating recorder the latest fix while the ride
                    // is recording (only when autoRecord is on). Skipped until a finite GPS fix has
                    // arrived. The recorder decimates by distance, so a 1 Hz feed is fine.
                    if (activeConfig.value.autoRecord) {
                        val lat = lastLat
                        val lng = lastLng
                        if (lat.isFinite() && lng.isFinite()) {
                            recorder.onSample(lat, lng, distM, elapsedS)
                        }
                    }

                    // --- mode select: ② route mode vs ① Virtual Partner ---------------------
                    val route = routePath
                    val segments = liveSegments
                    if (route != null && segments.isNotEmpty()) {
                        // Rebuild the projector when the route identity changes.
                        if (projectorRoute !== route) {
                            routeProjector = RouteProjectedProgress(route)
                            projectorRoute = route
                            activeSegmentStartM = null
                        }
                        val rp = routeProjector!!
                        val lat = lastLat
                        val lng = lastLng
                        if (lat.isFinite() && lng.isFinite()) {
                            rp.onLocation(LatLng(lat, lng))
                        }
                        val routeDist = rp.progressM
                        val seg = segments.firstOrNull { routeDist in it.routeStartM..it.routeEndM }
                        if (seg == null) {
                            // Between segments / off the matched stretch: no active segment.
                            activeSegmentStartM = null
                            GapStateHolder.clear()
                            SegmentInfoHolder.clear()
                            return@onEach
                        }
                        // Per-segment entry clock: when the active segment changes (or we just
                        // entered one), start counting from now. Entering mid-segment simply starts
                        // the comparison from the entry point (partial-entry marking omitted for now).
                        if (activeSegmentStartM != seg.routeStartM) {
                            activeSegmentStartM = seg.routeStartM
                            segmentEntryElapsedS = elapsedS
                        }
                        val progressM = routeDist - seg.routeStartM
                        val elapsedInSeg = elapsedS - segmentEntryElapsedS
                        val fresh = StalenessLogic.isTrustworthy(rp.isFresh && rp.onRoute, speedMs)
                        GapStateHolder.update(
                            GapCalculator.compute(progressM, elapsedInSeg, seg.ghost, fresh),
                        )
                        SegmentInfoHolder.set(seg.toInfo())
                    } else {
                        // ① Virtual Partner mode — unchanged from sub-project ①.
                        SegmentInfoHolder.clear()
                        val target = activeConfig.value.validTargetOrNull()
                        if (target == null) {
                            GapStateHolder.clear()
                            return@onEach
                        }
                        progress.onDistance(distM)
                        if (cachedTargetMs != target || cachedCurve == null) {
                            cachedCurve = VirtualPartnerSource(target).curve()
                            cachedTargetMs = target
                        }
                        // Speed-magnitude-gated staleness: a frozen distance is trustworthy only if
                        // the rider is essentially stopped (raw speed below the moving threshold);
                        // frozen WHILE moving — or with no usable speed — means GPS is unreliable.
                        val trustworthy = StalenessLogic.isTrustworthy(progress.isFresh, speedMs)
                        GapStateHolder.update(
                            GapCalculator.compute(progress.progressM, elapsedS, cachedCurve!!, trustworthy),
                        )
                    }
                }.collect {}
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
        GapStateHolder.clear()
        SegmentInfoHolder.clear()
    }

    /**
     * Called on [RideState.Idle]: persists the just-recorded ride (if it produced >= 2 decimated
     * points) to the [TrackStore] on IO, then resets the recorder for the next ride. The id and
     * startedAtEpoch are the wall-clock epoch captured at Recording start.
     */
    private fun finishAndSaveRecording() {
        val started = recordingStartedEpoch
        val track = recorder.build(id = started.toString(), startedAtEpoch = started)
        if (track != null) {
            scope.launch(Dispatchers.IO) { trackStore.save(track) }
        }
        recorder.reset()
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
