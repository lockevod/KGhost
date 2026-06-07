package com.enderthor.kvpartner.extension

import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.datatype.GapGraphicDataType
import com.enderthor.kvpartner.datatype.GapNumericDataType
import com.enderthor.kvpartner.datatype.SegmentGapDataType
import com.enderthor.kvpartner.engine.CoastingEstimator
import com.enderthor.kvpartner.engine.GapCalculator
import com.enderthor.kvpartner.engine.GapState
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.engine.GhostCurve
import com.enderthor.kvpartner.engine.LiveSegment
import com.enderthor.kvpartner.engine.RenderPrefs
import com.enderthor.kvpartner.engine.RouteGhost
import com.enderthor.kvpartner.engine.RouteProjectedProgress
import com.enderthor.kvpartner.engine.SegmentInfoHolder
import com.enderthor.kvpartner.engine.VirtualPartnerSource
import com.enderthor.kvpartner.engine.toInfo
import com.enderthor.kvpartner.geo.BBox
import com.enderthor.kvpartner.geo.LatLng
import com.enderthor.kvpartner.geo.Polyline
import com.enderthor.kvpartner.geo.PolylinePath
import com.enderthor.kvpartner.geo.SegmentMatcher
import com.enderthor.kvpartner.geo.TrackRecorder
import com.enderthor.kvpartner.geo.TrackStore
import com.enderthor.kvpartner.geo.TrackStorage
import com.enderthor.kvpartner.managers.ConfigurationManager
import com.enderthor.kvpartner.map.GhostMapPresenter
import com.enderthor.kvpartner.map.GhostMarker
import com.enderthor.kvpartner.map.MapEmit
import com.enderthor.kvpartner.map.decideMapEmit
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 *        feeds the DISTANCE stream through a [CoastingEstimator] (dead-reckoning during brief GPS
 *        loss) + a cached [VirtualPartnerSource] curve into [GapCalculator].
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

        /** Stable id for the ghost map symbol — re-emitting the same id MOVES the marker. */
        private const val GHOST_SYMBOL_ID = "kvpartner-ghost"

        /** Minimum projected movement (m) before re-emitting ShowSymbols (suppresses jitter/stops). */
        private const val MARKER_MIN_MOVE_M = 5.0

        /**
         * Heartbeat cadence (ms) for re-asserting the ghost map symbol. The Karoo host drops our
         * symbol when it redraws the map layer (zoom / pan / map re-init), and a STATIONARY ghost
         * (clamped at a segment end, or the rider stopped) never crosses [MARKER_MIN_MOVE_M] again —
         * so without this it would vanish for the rest of the stop. Re-emitting the CURRENT marker
         * every heartbeat guarantees the symbol comes back within ~this long after any host redraw.
         * Mirrors the data fields' HEARTBEAT_MS; > the 1 Hz tick so it doesn't churn every tick.
         */
        private const val GHOST_HEARTBEAT_MS = 3000L

        /**
         * Grace period (ms) before a non-route nav state tears route mode down. The host can emit a
         * transient Idle/NavigatingToDestination blip BETWEEN NavigatingRoute re-emits during active
         * route navigation; clearing immediately would null routeMode/lastMatchedPolyline and force a
         * full re-match (and a one-tick VP/`---` flicker) on the next same-route re-emit. We delay the
         * clear by this grace and cancel it if a route comes back, so a blip shorter than this is a
         * no-op. A real route END (sustained non-route) still clears after ~this long.
         */
        private const val ROUTE_CLEAR_GRACE_MS = 4000L
    }

    lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var configManager: ConfigurationManager
    private val activeConfig = MutableStateFlow(KVPartnerConfig())
    private var tickJob: Job? = null
    // GPS location consumer. Subscribed only while Recording (started in startTick, cancelled in
    // stopTick/stopTickAndJoin) so GPS fixes aren't consumed when the recorder/projector don't need
    // them. Owned by [scope].
    private var locationJob: Job? = null

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

    /**
     * One immutable route-mode snapshot. The matcher (Default thread) builds the full pairing of
     * route path + its live segments and publishes it in a SINGLE @Volatile write; the tick (Main)
     * reads it ONCE per tick. Bundling them prevents a one-tick `---` glitch on a route SWITCH where
     * the tick could otherwise pair a NEW [path] with the OLD segments (two separate sequential
     * writes were observable as a torn read).
     */
    private data class RouteMode(
        val path: PolylinePath,
        val segments: List<LiveSegment>,
        /**
         * The continuous whole-route ghost — recorded stretches stitched with VP-pace fills (see
         * [RouteGhost]). Distance axis is ROUTE distance `[0, path.totalM]`. Null only when it could
         * not be built (no fill pace and gaps present); the tick then falls back to ① VP.
         */
        val routeGhost: GhostCurve?,
    )

    // Route mode state. When non-null AND [RouteMode.segments] is non-empty the tick runs the
    // per-segment ② logic; otherwise it runs the ① Virtual Partner logic. Written by the
    // navigation-state collector (off Main), read by the tick — hence @Volatile.
    @Volatile
    private var routeMode: RouteMode? = null

    // Dedup + cancel guard for the route matcher. The host re-emits the SAME NavigatingRoute
    // repeatedly while it computes climbs/progress; without these, onNavigationState would re-run the
    // full O(n²) match on every re-emit in an un-cancelled coroutine → Default pool saturation → GC
    // storm → routeMode never gets assigned (② never activates). matchJob lets a superseding route
    // cancel an in-flight stale match; lastMatchedPolyline collapses re-emits of the SAME route to a
    // single match. Both written from the navigation-state collector (off Main) and clear/stop paths.
    @Volatile
    private var matchJob: Job? = null
    @Volatile
    private var lastMatchedPolyline: String? = null

    // Debounce for non-route nav-state teardown. The host emits transient Idle/NavigatingToDestination
    // blips between NavigatingRoute re-emits; this delayed job clears route mode only if no route comes
    // back within ROUTE_CLEAR_GRACE_MS. A returning route cancels it (see onNavigationState). Launched
    // on scope (Dispatchers.Main), read/written from the navigation-state collector and teardown.
    @Volatile
    private var clearJob: Job? = null

    // ④ map overlay. The map emitter is supplied by the host via startMap() on its own thread, so it
    // is @Volatile. lastGhostMarker is the last marker we emitted (edge-trigger state); read on the
    // tick (Main) AND written from clear/stop paths, so it is @Volatile too.
    @Volatile
    private var mapEmitter: Emitter<MapEffect>? = null
    @Volatile
    private var lastGhostMarker: GhostMarker? = null
    // Wall-clock (ms) of the last ShowSymbols we emitted. Drives the GHOST_HEARTBEAT_MS re-assert so a
    // host map redraw (zoom/pan) can't permanently drop a stationary ghost. Guarded by mapLock.
    private var lastGhostEmitMs: Long = 0L
    // Serialises publishGhostMarker across threads. The tick now runs on Dispatchers.Default and
    // publishGhostMarker is also called from clearRouteMode()/stop paths (potentially other threads),
    // so the read-modify-write of lastGhostMarker + the emitter call must be mutually exclusive.
    private val mapLock = Any()

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
        // tracksDir() does file IO (mkdirs + one-time migration); onCreate runs off the main
        // thread for an extension service, so it's safe here. Resolves shared external storage
        // when all-files access is granted, else falls back to internal.
        trackStore = TrackStore(TrackStorage.tracksDir(applicationContext))
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
            Timber.d("KVP ride state=$state tickActive=${tickJob?.isActive} route=${routeMode != null}")
            when (state) {
                is RideState.Recording -> startTick()
                is RideState.Paused -> {
                    // The clock is tied to ELAPSED_TIME, which the ride app already pauses.
                    // Freeze the tick by leaving it running but receiving no emissions; do not reset.
                }
                is RideState.Idle -> {
                    // Order matters: fully stop+join the tick FIRST so the recorder is quiescent
                    // (no onSample racing build/reset) before finishAndSaveRecording() touches it.
                    // The tick never reads recordingStartedEpoch, and stopTickAndJoin() does not
                    // clear it, so the epoch is still set when finish reads it. finish() reads the
                    // epoch, builds from the now-idle recorder, launches the IO save, resets the
                    // recorder, then clears the epoch. We clear the remaining state afterwards.
                    stopTickAndJoin()
                    finishAndSaveRecording()
                    GapStateHolder.clear()
                    SegmentInfoHolder.clear()
                    publishGhostMarker(null)
                    recordingStartedEpoch = 0L // backstop; finish() already cleared it
                }
                else -> {}
            }
        }.launchIn(scope)

        // ② route load → match recorded history into live segments; clear on non-route states.
        karooSystem.streamNavigationState().onEach { onNavigationState(it) }.launchIn(scope)
    }

    /**
     * The host calls this to receive map overlay effects. We keep the emitter and feed it the ghost
     * marker from the ② branch of the tick. setCancellable nulls it when the host tears the map down.
     */
    override fun startMap(emitter: Emitter<MapEffect>) {
        // Mutate the map state under mapLock — the tick's publishGhostMarker also reads/writes
        // mapEmitter/lastGhostMarker inside synchronized(mapLock), and the host calls startMap /
        // the cancellable from its OWN thread; without the lock those interleave. Reset
        // lastGhostMarker (force a fresh Show on the next tick) and the heartbeat clock so the new
        // map gets the symbol promptly.
        synchronized(mapLock) {
            mapEmitter = emitter
            lastGhostMarker = null
            lastGhostEmitMs = 0L
        }
        Timber.d("KVP startMap")
        emitter.setCancellable {
            synchronized(mapLock) {
                mapEmitter = null
                lastGhostMarker = null
                lastGhostEmitMs = 0L
            }
            Timber.d("KVP stopMap (cancellable)")
        }
    }

    /**
     * Reconciles the desired ghost marker against what is currently shown and emits the minimal
     * MapEffect: Show (first time or moved >= MARKER_MIN_MOVE_M), Hide (was shown, now gone), or
     * nothing. Idempotent and safe to call from any path (tick, clear, stop).
     */
    private fun publishGhostMarker(next: GhostMarker?) {
        synchronized(mapLock) {
            val em = mapEmitter
            if (em == null) {
                // No map layer (host hasn't called startMap, or tore it down). Nothing to emit; the
                // next startMap resets state and the tick re-shows.
                if (next != null) Timber.d("KVP ghost: no mapEmitter, skip")
                return
            }
            val now = System.currentTimeMillis()
            // Heartbeat: if the current marker hasn't moved enough to re-show on its own, force a
            // re-assert once the heartbeat window elapses so a host map redraw can't drop it for good.
            val force = next != null && (now - lastGhostEmitMs) >= GHOST_HEARTBEAT_MS
            when (val decision = decideMapEmit(lastGhostMarker, next, MARKER_MIN_MOVE_M, force)) {
                is MapEmit.Show -> {
                    val m = decision.marker
                    em.onNext(
                        ShowSymbols(
                            listOf(Symbol.Icon(GHOST_SYMBOL_ID, m.lat, m.lng, R.drawable.ic_ghost, m.bearingDeg)),
                        ),
                    )
                    lastGhostMarker = m
                    lastGhostEmitMs = now
                    Timber.d("KVP ghost SHOW lat=${"%.5f".format(m.lat)} lng=${"%.5f".format(m.lng)} force=$force")
                }
                MapEmit.Hide -> {
                    em.onNext(HideSymbols(listOf(GHOST_SYMBOL_ID)))
                    lastGhostMarker = null
                    Timber.d("KVP ghost HIDE")
                }
                MapEmit.None -> {}
            }
        }
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
            // A route is present → cancel any pending debounced teardown from a prior transient blip.
            // Because lastMatchedPolyline is preserved across a cancelled pending-clear, a blip→same-
            // route sequence dedups below and does NOT re-match.
            clearJob?.cancel()
            clearJob = null
            val routePolyline = state.routePolyline
            val elevPolyline = state.routeElevationPolyline
            // Dedup ON POLYLINE ALONE: the host re-emits the SAME NavigatingRoute many times as it
            // computes climbs/progress. If this is a re-emit of the route we already claimed, ignore
            // it — re-running the full match would saturate the Default pool and starve the
            // assignment of routeMode. We deliberately do NOT also require `routeMode != null`:
            // lastMatchedPolyline is set BEFORE the launch (so the in-flight first match is covered),
            // and clearRouteMode() nulls it on failure/non-route — so a genuinely failed/cleared route
            // re-matches on the next emit. Requiring routeMode != null here would, while the FIRST
            // match is still running (routeMode still null), let same-route re-emits cancel+restart it
            // (churn/starvation). Compare BEFORE launching anything.
            if (routePolyline == lastMatchedPolyline) return
            // A different (or first) route: cancel any in-flight match for the previous route so a
            // stale O(n) match can't run concurrently with the new one, then claim this polyline.
            matchJob?.cancel()
            lastMatchedPolyline = routePolyline
            // Capture the polyline this match OWNS. Every state mutation below is guarded by
            // `lastMatchedPolyline == mine`, so only the match owning the CURRENT polyline can
            // publish/clear: a superseded match (a newer route claimed lastMatchedPolyline) becomes a
            // no-op and can never overwrite/wipe the NEWER route's state.
            val mine = routePolyline
            // Off Main: polyline decode, candidate file IO, and segment matching are all heavier
            // than a frame. Default is fine; loadTopCandidates does file IO but never overlaps a save
            // in practice (save runs at ride-end, matching at route-load).
            matchJob = scope.launch(Dispatchers.Default) {
                runCatching {
                    val path = PolylinePath(Polyline.decode(routePolyline))
                    val bbox = BBox.around(path.points) ?: run {
                        if (lastMatchedPolyline == mine) clearRouteMode()
                        return@launch
                    }
                    // Pre-cap candidates by ROUTE OVERLAP (relevance), parsing only the top tracks.
                    val tracks = trackStore.loadTopCandidates(bbox, SegmentMatcher.Params().maxTracks)
                    // A superseding route should cancel this stale match promptly: bail before the
                    // expensive match if we've been cancelled.
                    currentCoroutineContext().ensureActive()
                    val matched = SegmentMatcher.match(
                        path,
                        tracks,
                        activeConfig.value.ghostPick,
                        SegmentMatcher.Params(),
                    )
                    val withElevation = applyElevation(matched, elevPolyline)
                    // Build the ONE continuous whole-route ghost (recorded stretches + VP-pace fills).
                    // Fill pace = the VP target, falling back to the segments' own average so the ghost
                    // still flows even with no target configured.
                    val fillSpeedM = activeConfig.value.validTargetOrNull()
                        ?: RouteGhost.averageSegmentSpeedM(withElevation)
                    val routeGhost = if (fillSpeedM != null && fillSpeedM > 0.0) {
                        RouteGhost.build(path.totalM, withElevation, fillSpeedM)
                    } else {
                        null
                    }
                    // Single atomic publish: path + segments + ghost together so the tick never sees a
                    // NEW path paired with OLD segments. Guarded: only publish if a newer route has not
                    // superseded us (lastMatchedPolyline still ours).
                    if (lastMatchedPolyline == mine) {
                        routeMode = RouteMode(path, withElevation, routeGhost)
                        Timber.d(
                            "route mode ON: ${withElevation.size} segment(s), routeGhost=${routeGhost != null} " +
                                "on '${state.name}'",
                        )
                    }
                }.onFailure { e ->
                    // A cancellation (superseding route / teardown) must propagate, not be swallowed
                    // as a match failure — otherwise clearRouteMode() would wipe the NEW route's state.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "route matching failed; staying in ① VP mode")
                    if (lastMatchedPolyline == mine) clearRouteMode()
                }
            }
        } else {
            // Non-route state (or racing disabled). Debounce the teardown: the host can emit a
            // transient Idle/NavigatingToDestination blip between NavigatingRoute re-emits. Clearing
            // immediately would null routeMode/lastMatchedPolyline → a needless full re-match (and a
            // one-tick VP/`---` flicker) on the next same-route re-emit. Schedule the clear after a
            // grace; a returning route cancels it (above). Only schedule if there is something to
            // clear and no clear is already pending.
            if ((routeMode != null || lastMatchedPolyline != null) && clearJob?.isActive != true) {
                clearJob = scope.launch { // scope is Dispatchers.Main
                    delay(ROUTE_CLEAR_GRACE_MS)
                    clearRouteMode()
                    clearJob = null
                }
            }
        }
    }

    /** Clears ② route mode so the tick falls back to ① Virtual Partner behavior. */
    private fun clearRouteMode() {
        // An explicit clear supersedes any pending debounced clear. The delayed clear path calls this
        // then sets clearJob = null itself, so we only cancel a still-active job here (a direct caller:
        // onDestroy/stopTick teardown, or a match failure). Cancelling the very coroutine that is
        // running this is benign — there is no suspension point after this call — but the takeIf keeps
        // it tidy. We deliberately do NOT null clearJob here; the delayed path nulls it on its own.
        clearJob?.takeIf { it.isActive }?.cancel()
        // Cancel any in-flight match and drop the dedup key so a later same-route emit re-matches.
        matchJob?.cancel()
        lastMatchedPolyline = null
        routeMode = null
        SegmentInfoHolder.clear()
        // publishGhostMarker is internally synchronized on mapLock, so it is safe to call directly
        // from any caller thread (this can run on a Default coroutine via onNavigationState).
        publishGhostMarker(null)
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
        if (tickJob?.isActive == true) {
            Timber.d("KVP startTick SKIP (tick already active)")
            return
        }
        Timber.d("KVP startTick START (route=${routeMode != null})")
        // Only stamp the epoch on a genuinely fresh start. If the tick coroutine previously died
        // mid-ride (e.g. an exception) a later Recording emission re-enters startTick; without this
        // guard it would reset the epoch mid-ride → wrong track id / partial double-save risk. The
        // epoch is cleared to 0L by stopTick()/finishAndSaveRecording(), so the next ride after an
        // Idle still gets a fresh stamp.
        if (recordingStartedEpoch == 0L) {
            recordingStartedEpoch = System.currentTimeMillis()
        }
        // GPS location consumer — subscribed only while Recording so fixes aren't consumed when the
        // recorder/projector don't need them. Finite-guard on write so the tick/recorder never sees
        // a NaN/±Inf coordinate. lastLat/lastLng stay @Volatile (written here, read on the tick).
        if (locationJob?.isActive != true) {
            locationJob = karooSystem.streamLocation().onEach { loc ->
                val lat = loc.lat
                val lng = loc.lng
                if (lat.isFinite() && lng.isFinite()) {
                    lastLat = lat
                    lastLng = lng
                }
            }.launchIn(scope)
        }
        // ① Virtual Partner state (used when route mode is OFF).
        // Dead-reckoning estimator that owns BOTH the effective distance and trustworthiness: it
        // extrapolates (coasts) the DISTANCE stream at the last known speed during a brief GPS gap,
        // and only blanks after a sustained loss. Replaces the old DistanceProgress +
        // StalenessLogic.isTrustworthy pair for ①.
        val coast = CoastingEstimator()
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
        // ② route-mode dead-reckoning estimator, applied to the projected route distance. Rebuilt
        // alongside routeProjector when the route identity changes so it resets per route. Coasting
        // advances the route distance during a GPS gap (the rider keeps moving along the route),
        // which is exactly the desired behavior for segment selection and the gap.
        var coastRoute: CoastingEstimator? = null
        // Ride-elapsed seconds corresponding to the whole-route ghost's t=0, set ONCE per route so the
        // ghost starts beside the rider when racing begins (back-date by the ghost's time at the
        // rider's entry route-distance). null until the first route-mode tick of the current route.
        var ghostStartElapsedS: Double? = null
        // Throttle for the per-tick route-mode diagnostic log (≤ ~once per DIAG_LOG_MS). Kept local
        // to the tick so it resets per ride. Set to 0 to disable.
        var lastDiagLogMs = 0L
        val diagLogMs = 2500L

        tickJob = scope.launch(Dispatchers.Default) {
            val distance = karooSystem.streamDataFlow(DataType.Type.DISTANCE)
            val elapsed = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
            // SPEED (m/s) is streamed to distinguish "stopped at a light" (frozen distance is
            // legitimate) from "GPS lost while moving" (frozen distance is wrong → blank to `---`).
            val speed = karooSystem.streamDataFlow(DataType.Type.SPEED)
            combine(distance, elapsed, speed) { d, e, sp -> Triple(d, e, sp) }
                .sample(REFRESH_MS) // rate-limit BEFORE conflate so we tick at most once per REFRESH_MS
                .conflate()
                .onEach { (d, e, sp) ->
                    runCatching {
                    // DISTANCE is in metres. Drop non-finite values (NaN/±Inf) so they never reach
                    // the gap engine.
                    val distM = (d as? StreamState.Streaming)?.dataPoint?.singleValue
                        ?.takeIf { it.isFinite() } ?: return@runCatching
                    // ELAPSED_TIME is delivered in milliseconds by karoo-ext, so convert to seconds.
                    // GapCalculator expects elapsed seconds. If field testing shows the SDK already
                    // delivers seconds, drop the divide-by-1000 in [elapsedMsToSeconds].
                    val elapsedRaw = (e as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@runCatching
                    val elapsedS = elapsedMsToSeconds(elapsedRaw).takeIf { it.isFinite() } ?: return@runCatching
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

                    // ① Virtual Partner machinery, hoisted to run EVERY tick regardless of mode so
                    // it is always ready as the fallback when no segment is active. coast tracks the
                    // whole-ride DISTANCE odometer (dead-reckoning during brief GPS gaps); the cached
                    // VP curve is rebuilt lazily when the target changes (below, in vpGapOrNull).
                    coast.update(distM, speedMs)
                    // Computes the ① VP gap (whole-ride distance vs elapsed at the fixed target pace),
                    // or null when no valid VP target is configured (→ nothing to compare → `---`).
                    fun vpGapOrNull(): GapState? {
                        val target = activeConfig.value.validTargetOrNull() ?: return null
                        if (cachedTargetMs != target || cachedCurve == null) {
                            cachedCurve = VirtualPartnerSource(target).curve()
                            cachedTargetMs = target
                        }
                        return GapCalculator.compute(
                            coast.effectiveDistanceM, elapsedS, cachedCurve!!, coast.trustworthy,
                        )
                    }

                    // --- mode select: ② route mode vs ① Virtual Partner ---------------------
                    // Read the route-mode snapshot ONCE per tick so path + segments stay consistent
                    // even if the matcher publishes a new RouteMode mid-tick.
                    val rm = routeMode
                    if (rm != null && rm.segments.isNotEmpty()) {
                        // Rebuild the projector when the route identity changes.
                        if (projectorRoute !== rm.path) {
                            routeProjector = RouteProjectedProgress(rm.path)
                            coastRoute = CoastingEstimator()
                            projectorRoute = rm.path
                            ghostStartElapsedS = null
                        }
                        val rp = routeProjector!!
                        val cr = coastRoute!!
                        val lat = lastLat
                        val lng = lastLng
                        if (lat.isFinite() && lng.isFinite()) {
                            rp.onLocation(LatLng(lat, lng))
                        }
                        // Dead-reckon the projected route distance: coasting advances routeDist during
                        // a brief GPS gap so segment selection and the gap keep tracking the rider's
                        // assumed position; a sustained loss flips trustworthy=false → blank. We feed
                        // the RAW projected distance; the estimator owns trustworthiness (so rp.onRoute
                        // is folded in below only as a hard gate when the fix IS fresh).
                        cr.update(rp.progressM, speedMs)
                        val routeDist = cr.effectiveDistanceM
                        // The estimator owns trustworthiness (handles the GPS-gap/coast case). We add
                        // ONE hard gate: when the projector fix IS fresh (a new position arrived) but
                        // the rider is off-route, do not trust — a genuine deviation, not a dropout.
                        // While coasting (frozen projection) onRoute reflects the last good fix, so we
                        // don't let a stale off-route flag override the coast decision. This gates the
                        // GAP's staleness only — the map ghost is time-based and shown regardless.
                        val fresh = cr.trustworthy && (!rp.isFresh || rp.onRoute)
                        // Which recorded stretch the rider is currently on — for the segment field's
                        // elevation/track visualization only. The ghost itself is whole-route, not
                        // per-segment, so this does NOT gate the ghost or the gap.
                        val seg = rm.segments.firstOrNull { routeDist in it.routeStartM..it.routeEndM }
                        run {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d(
                                    "KVP tick route: routeDist=${"%.0f".format(routeDist)} " +
                                        "seg=${seg?.let { "[${"%.0f".format(it.routeStartM)}..${"%.0f".format(it.routeEndM)}]" } ?: "none"} " +
                                        "rg=${rm.routeGhost != null} fresh=$fresh rpFresh=${rp.isFresh} onRoute=${rp.onRoute} " +
                                        "speed=${speedMs?.let { "%.1f".format(it) } ?: "null"} mapEmitter=${mapEmitter != null}",
                                )
                            }
                        }
                        val rg = rm.routeGhost
                        if (rg == null) {
                            // Couldn't build the continuous whole-route ghost (no fill pace and gaps
                            // present). Fall back to the ① whole-ride Virtual Partner gap so the field
                            // still shows something; no map ghost.
                            SegmentInfoHolder.clear()
                            publishGhostMarker(null)
                            val vp = vpGapOrNull()
                            if (vp != null) GapStateHolder.update(vp) else GapStateHolder.clear()
                            return@runCatching
                        }
                        // Back-date the ghost clock ONCE per route so the ghost starts BESIDE the rider
                        // when racing begins (or when a route loads mid-ride), rather than at the route
                        // start. After this, ghostElapsed = elapsedS − ghostStartElapsedS drives the
                        // whole-route ghost; it advances continuously and only freezes on pause (the
                        // ride app stops ELAPSED_TIME), never just because a recorded stretch ended.
                        if (ghostStartElapsedS == null) {
                            ghostStartElapsedS = elapsedS - rg.timeAt(routeDist)
                        }
                        val ghostElapsed = elapsedS - ghostStartElapsedS!!
                        // One gap against the whole-route ghost: progress = rider's route distance,
                        // clock = ghostElapsed, curve = the continuous route ghost (route-distance axis).
                        val gap = GapCalculator.compute(routeDist, ghostElapsed, rg, fresh)
                        GapStateHolder.update(gap)
                        // Segment field viz: show the active recorded stretch, else clear. The gap shown
                        // is still the whole-route gap (which, on a recorded stretch, races your past self).
                        if (seg != null) SegmentInfoHolder.set(seg.toInfo()) else SegmentInfoHolder.clear()
                        // ④ ghost-on-map: gap.ghostProgressM is already the ghost's ROUTE distance.
                        // Drawn regardless of GPS freshness (fresh = true) — the ghost's position is
                        // purely time-based, so a GPS dropout or a stop must NOT remove it; only pause
                        // (frozen clock) or moving off the visible map (host-culled) hides it.
                        val marker = if (activeConfig.value.showGhostOnMap) {
                            GhostMapPresenter.marker(gap.ghostProgressM, rm.path, fresh = true)
                        } else {
                            null
                        }
                        publishGhostMarker(marker)
                    } else {
                        // ① Virtual Partner mode — no route (or empty segments). Uses the same unified
                        // vpGapOrNull() as the off-segment fallback so the VP computation lives in one
                        // place. coast was already updated above; the helper coasts the DISTANCE stream
                        // at the last known speed during a brief GPS gap (keeping the gap accurate),
                        // treats a genuine stop as legitimate (frozen distance, still trustworthy), and
                        // only blanks after a sustained loss or when speed is unavailable.
                        SegmentInfoHolder.clear()
                        publishGhostMarker(null)
                        val vp = vpGapOrNull()
                        if (vp != null) GapStateHolder.update(vp) else GapStateHolder.clear()
                    }
                    }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.e(e, "tick iteration failed")
                    }
                }
                .collect {}
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
        // Cancel any pending debounced route-mode teardown so a delayed clear can't fire after we've
        // already torn down (onDestroy → stopTick, or a ride-end stop).
        clearJob?.cancel()
        clearJob = null
        // Clear the epoch so the next ride's startTick() gets a fresh stamp. A re-entered startTick()
        // during the SAME ride (tick coroutine died) sees a non-zero epoch and leaves it intact.
        recordingStartedEpoch = 0L
        GapStateHolder.clear()
        SegmentInfoHolder.clear()
        publishGhostMarker(null)
    }

    /**
     * Fully stops the tick and waits for it to terminate, so the recorder is quiescent before
     * finishAndSaveRecording() builds/resets it. Unlike [stopTick] this does NOT clear
     * recordingStartedEpoch (finish() needs it) nor the holders/marker (the Idle handler clears those
     * after finish()). Cancels the GPS consumer too. Suspends — call only from a coroutine.
     */
    private suspend fun stopTickAndJoin() {
        tickJob?.cancelAndJoin()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
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
            // add() dedups on sourceKey (first writer wins). false means a same-key ride is already
            // stored — e.g. a FitFiles scan ingested this ride first; nothing to do but note it.
            scope.launch(Dispatchers.IO) {
                if (!trackStore.add(track)) {
                    Timber.d("recorded track ${track.id} skipped: sourceKey ${track.sourceKey} already stored")
                }
            }
        }
        recorder.reset()
        // Done with this ride's epoch; clear it so a fresh ride re-stamps in startTick(). stopTick()
        // also clears it, but reset here too so the non-Idle save paths stay correct.
        recordingStartedEpoch = 0L
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
