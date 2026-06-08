package com.enderthor.kghost.extension

import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.datatype.GapGraphicDataType
import com.enderthor.kghost.datatype.GapNumericDataType
import com.enderthor.kghost.engine.CoastQuality
import com.enderthor.kghost.engine.CoastingEstimator
import com.enderthor.kghost.engine.GapCalculator
import com.enderthor.kghost.engine.GapState
import com.enderthor.kghost.engine.GapStateHolder
import com.enderthor.kghost.engine.GhostCurve
import com.enderthor.kghost.engine.LiveSegment
import com.enderthor.kghost.engine.RenderPrefs
import com.enderthor.kghost.engine.RouteGhost
import com.enderthor.kghost.engine.RouteProjectedProgress
import com.enderthor.kghost.engine.SegmentInfoHolder
import com.enderthor.kghost.engine.GhostPaceSource
import com.enderthor.kghost.engine.toInfo
import com.enderthor.kghost.geo.BBox
import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.PolylinePath
import com.enderthor.kghost.geo.SegmentMatcher
import com.enderthor.kghost.geo.TrackRecorder
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.map.GhostMapPresenter
import com.enderthor.kghost.map.GhostMarker
import com.enderthor.kghost.map.ghostIconRes
import com.enderthor.kghost.map.ghostIconRotates
import com.enderthor.kghost.map.ghostSizeForZoom
import com.enderthor.kghost.map.MapEmit
import com.enderthor.kghost.map.decideMapEmit
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import timber.log.Timber


/**
 * Central orchestrator for the KGhost extension.
 *
 * Connects the [KarooSystemService] streams to the pure gap engine and publishes results to
 * [GapStateHolder] (and, in route mode, [SegmentInfoHolder]):
 *  - Subscribes to [RideState]. Work only happens while `Recording`. `Idle` stops the tick and
 *    clears the state; `Paused` freezes the tick (the time clock is driven by `ELAPSED_TIME`,
 *    which the ride app pauses on its own, so there is nothing to reset).
 *  - While recording, runs a ~1 Hz tick that combines the `DISTANCE`, `ELAPSED_TIME` and `SPEED`
 *    streams. The tick has two modes:
 *      * **① Ghost Pace mode** (default, when no route is loaded or racing is disabled):
 *        feeds the DISTANCE stream through a [CoastingEstimator] (dead-reckoning during brief GPS
 *        loss) + a cached [GhostPaceSource] curve into [GapCalculator].
 *      * **② Route mode** (when a navigated route is loaded and `raceEnabled`): projects the live
 *        GPS position onto the route via [RouteProjectedProgress] and computes the gap against the
 *        continuous whole-route ghost (recorded stretches stitched with VP-pace fills). Publishes
 *        which recorded [LiveSegment] is currently active to [SegmentInfoHolder] — used only to show
 *        the data fields' SEG (racing your past self) vs VP (fixed-pace) tag.
 *  - Subscribes to the navigation state. On `NavigatingRoute` (route mode ②), it decodes the route
 *    polyline, loads candidate recorded tracks, and runs [SegmentMatcher] to build the live
 *    segments. On `Idle`/`NavigatingToDestination`, route mode is cleared and the tick falls back
 *    to ① Ghost Pace behavior.
 *  - Subscribes to the GPS location stream and records the ride history (decimated) to the
 *    [TrackStore] at ride end, when `autoRecord` is on. That history is what later route loads
 *    match against.
 *
 * All work runs on `Dispatchers.Main + SupervisorJob` owned by this service; the heavier
 * route-matching and file IO are dispatched off Main onto `Dispatchers.Default`/`Dispatchers.IO`.
 */
class KGhostExtension : KarooExtension("kghost", "0.1.0") {

    companion object {
        @Volatile
        var instance: KGhostExtension? = null
            private set

        /** Tick cadence. The ride app advances its record timer at ~1 Hz. */
        private const val REFRESH_MS = 1000L

        /** Stable id for the ghost map symbol — re-emitting the same id MOVES the marker. */
        private const val GHOST_SYMBOL_ID = "kghost-ghost"

        /**
         * Minimum movement (m) before re-emitting ShowSymbols. Small (0.5 m) because the ghost
         * position is purely time-based and noise-free — there is no GPS jitter to suppress, so a
         * large threshold would only stutter/freeze a slow ghost. This just skips a truly-stationary
         * re-emit (the heartbeat re-asserts those). Movement-per-emit at [MAP_REFRESH_MS]: ~1.6 m at
         * 8 m/s, ~0.4 m at 2 m/s.
         */
        private const val MARKER_MIN_MOVE_M = 0.5

        /**
         * Ghost map-marker refresh cadence (ms). The marker is interpolated and re-emitted at this rate
         * (~5 Hz) by a dedicated loop, INDEPENDENT of the 1 Hz gap tick — the ghost position is a pure
         * function of elapsed ride time, so it can be sampled between ticks without any new GPS. This
         * is what makes the ghost glide instead of hopping once per second.
         */
        private const val MAP_REFRESH_MS = 200L

        /**
         * Cap (ms) on how far the map loop extrapolates the ghost past the last gap-tick anchor using
         * wall-clock, so a stalled tick can't run the ghost away. Pause is handled separately (the loop
         * freezes on [ridePaused]); this is only a safety net for an unusually late tick. Set well above
         * the ~1 s tick interval (the tick is `sample(1000)` on Dispatchers.Default and can run late
         * under GC/match contention) so normal riding never clips — clipping would re-introduce a stutter.
         */
        private const val MAX_GHOST_EXTRAP_MS = 2500L

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

        /**
         * How long (s) the whole-ride odometer must be dead-reckoned (GPS frozen while moving) before
         * we fire the one-shot "GPS lost" in-ride alert. Larger than [CoastingEstimator.COAST_WINDOW_MS]
         * (~30 s) so a brief tunnel that's already transparently coasted doesn't nag the rider; only a
         * genuinely prolonged loss alerts. Re-arms when GPS recovers.
         */
        private const val GPS_ALERT_S = 60.0

        /**
         * After this long (s) of continuous GPS loss WHILE the ride keeps timing, give up the
         * dead-reckoned estimate and blank the field (and hide the ghost) — 3 min with no GPS on a bike
         * computer is degenerate, and a believable estimate extrapolated that far is worse than an
         * honest `---`. A mere stop never reaches this: the ride app's auto-pause freezes ELAPSED_TIME
         * so the coast stops growing (and if the rider disabled auto-pause, that was their choice).
         */
        private const val GPS_GIVEUP_S = 180.0
    }

    lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var configManager: ConfigurationManager
    private val activeConfig = MutableStateFlow(KGhostConfig())
    private var tickJob: Job? = null
    // GPS location consumer. Subscribed only while Recording (started in startTick, cancelled in
    // stopTick/stopTickAndJoin) so GPS fixes aren't consumed when the recorder/projector don't need
    // them. Owned by [scope].
    private var locationJob: Job? = null
    // RideState + navigation-state stream collectors, owned by [scope]. karooSystem.connect{}'s
    // callback is NOT one-shot — it re-fires on every (re)bind of the host service. Tracking these
    // lets onConnected() cancel the previous collectors before relaunching, so a reconnect can't
    // accumulate duplicate RideState/nav consumers that would all drive the tick in parallel.
    private var rideJob: Job? = null
    private var navJob: Job? = null
    // Map zoom-level consumer (OnMapZoomLevel). Drives the ghost icon's auto-scaling. Owned by [scope].
    private var zoomJob: Job? = null
    // UserProfile consumer — reads the rider's distance unit (metric/imperial) for the gap fields.
    private var profileJob: Job? = null

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

    /**
     * Immutable snapshot the 1 Hz tick hands to the ~5 Hz map loop so it can interpolate the ghost's
     * time-based position between ticks. [anchorElapsedS]/[anchorWallMs] pin the ghost clock at the
     * last tick; the loop advances it by wall-clock (capped by [MAX_GHOST_EXTRAP_MS], frozen on pause).
     */
    private data class MapGhostState(
        val rg: GhostCurve,
        val path: PolylinePath,
        val ghostStartElapsedS: Double,
        val anchorElapsedS: Double,
        val anchorWallMs: Long,
    )

    // Route mode state. When non-null AND [RouteMode.segments] is non-empty the tick runs the
    // per-segment ② logic; otherwise it runs the ① Ghost Pace logic. Written by the
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

    // Whether we are currently in RideState.Recording. The heavy route match only runs while recording
    // (set true in startTick, false in stopTick/Idle) so merely PREVIEWING a route on standby — which
    // the host signals with NavigatingRoute well before the rider presses start — doesn't trigger a
    // full polyline-decode + candidate-file-read + O(n²) match burst that drains the battery for a race
    // that may never start.
    @Volatile
    private var isRecording = false

    // The most recent NavigatingRoute event seen while NOT recording. Deferred so the match runs once
    // racing actually begins: startTick() replays it after flipping isRecording=true. Cleared when a
    // non-route state arrives (the previewed route went away).
    @Volatile
    private var pendingNavState: OnNavigationState? = null

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
    // Icon resource of the currently shown ghost symbol (0 = nothing shown). Re-emitting the same
    // Symbol.Icon id only reliably MOVES the marker, not swaps its drawable, so when the rider changes
    // the ghost icon/size mid-ride we must Hide then Show to actually swap it. Guarded by mapLock.
    private var lastIconRes: Int = 0
    // Serialises publishGhostMarker across threads. The tick now runs on Dispatchers.Default and
    // publishGhostMarker is also called from clearRouteMode()/stop paths (potentially other threads),
    // so the read-modify-write of lastGhostMarker + the emitter call must be mutually exclusive.
    private val mapLock = Any()

    // Smooth ghost-on-map loop. The 1 Hz gap tick hands the ~5 Hz [mapLoopJob] an immutable
    // [MapGhostState] snapshot here; the loop interpolates the ghost's time-based position between
    // ticks and emits it, so the marker glides instead of hopping once per second. Null when there is
    // no ghost to show. @Volatile: written by the tick (Default), read by the map loop.
    @Volatile
    private var mapGhostState: MapGhostState? = null
    private var mapLoopJob: Job? = null
    // True while RideState.Paused — the map loop then freezes the ghost (no wall-clock extrapolation)
    // so a paused ghost holds position instead of drifting forward. Set from the RideState collector.
    @Volatile
    private var ridePaused = false
    // Latest map zoom level [8,18] from OnMapZoomLevel (15.0 mid-range until the first event). Drives
    // the ghost icon's automatic size (the drawable is swapped S/M/L by zoom so it stays proportionate
    // to the map). @Volatile: written by the zoom collector, read in publishGhostMarker.
    @Volatile
    private var currentMapZoom = 15.0

    // The on-screen data fields rendering the GapState. typeIds must match extension_info.xml
    // exactly ("kghost-gap" and "kghost-gap-num").
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
        // connect{}'s callback re-fires on every reconnect (host service rebind after an app update,
        // OOM kill, or transient unbind). Cancel the prior collectors first so we don't stack a
        // second RideState/nav consumer — N of them would each call startTick/finish in parallel and
        // race the recorder. The callbackFlow's awaitClose removes the underlying host consumer too.
        rideJob?.cancel()
        navJob?.cancel()
        zoomJob?.cancel()
        profileJob?.cancel()
        // A reconnect re-binds the host, so the still-active tick + GPS collectors are now wired to the
        // DEAD previous binding and would silently stop receiving DISTANCE/SPEED/GPS — the gap and
        // ghost would freeze for the rest of the ride with no recovery. Cancel them here (but NOT the
        // recorder or recordingStartedEpoch, so the track id stays stable mid-ride); the RideState
        // stream replays the current state on (re)subscription, so a still-Recording ride immediately
        // rebuilds fresh streams against the new binding via startTick(). routeMode is in-memory and
        // binding-independent, so it is preserved (no needless re-match). On the FIRST connect both
        // jobs are null, so this is a no-op.
        tickJob?.cancel()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
        rideJob = karooSystem.streamRide().onEach { state ->
            Timber.d("KVP ride state=$state tickActive=${tickJob?.isActive} route=${routeMode != null}")
            when (state) {
                is RideState.Recording -> {
                    ridePaused = false
                    // Re-stamp the map anchor's wall-clock to now so the loop doesn't lurch the ghost
                    // forward (by up to MAX_GHOST_EXTRAP_MS) on resume before the next tick re-anchors:
                    // ELAPSED_TIME was frozen during pause, but anchorWallMs would otherwise be stale.
                    mapGhostState?.let { mapGhostState = it.copy(anchorWallMs = System.currentTimeMillis()) }
                    startTick()
                }
                is RideState.Paused -> {
                    // The clock is tied to ELAPSED_TIME, which the ride app already pauses, so the tick
                    // freezes by receiving no emissions. Flag the pause so the map loop holds the ghost
                    // in place rather than extrapolating it forward by wall-clock.
                    ridePaused = true
                }
                is RideState.Idle -> {
                    ridePaused = false
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
        navJob = karooSystem.streamNavigationState().onEach { onNavigationState(it) }.launchIn(scope)
        // Map zoom → auto-scale the ghost icon. Cheap (changes only when the rider zooms).
        zoomJob = karooSystem.streamMapZoom().onEach { currentMapZoom = it }.launchIn(scope)
        // Rider's distance unit → the gap fields render metres or feet accordingly.
        profileJob = karooSystem.streamUserProfile()
            .onEach {
                RenderPrefs.setImperialDistance(
                    it.preferredUnit.distance == io.hammerhead.karooext.models.UserProfile.PreferredUnit.UnitType.IMPERIAL,
                )
            }
            .launchIn(scope)
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
            lastIconRes = 0
        }
        Timber.d("KVP startMap")
        emitter.setCancellable {
            synchronized(mapLock) {
                mapEmitter = null
                lastGhostMarker = null
                lastGhostEmitMs = 0L
                lastIconRes = 0
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
            // Resolve the icon for the desired marker up front so an icon/size change can both force a
            // re-emit AND trigger a Hide+Show (a bare same-id re-emit only moves, doesn't swap drawable).
            val cfg = activeConfig.value
            // Size follows the map zoom automatically (Symbol.Icon has no size field; we swap S/M/L).
            // A zoom change flips iconRes → iconChanged below → Hide+Show, so the icon rescales promptly.
            val iconRes = if (next != null) ghostIconRes(cfg.ghostIcon, ghostSizeForZoom(currentMapZoom)) else 0
            val iconChanged = next != null && lastIconRes != 0 && iconRes != lastIconRes
            // Heartbeat: if the current marker hasn't moved enough to re-show on its own, force a
            // re-assert once the heartbeat window elapses so a host map redraw can't drop it for good.
            // An icon/size change also forces a re-emit so the new drawable applies promptly.
            val force = next != null && ((now - lastGhostEmitMs) >= GHOST_HEARTBEAT_MS || iconChanged)
            when (val decision = decideMapEmit(lastGhostMarker, next, MARKER_MIN_MOVE_M, force)) {
                is MapEmit.Show -> {
                    val m = decision.marker
                    // Drawable changed (rider switched icon/size mid-ride): hide first so the re-Show
                    // actually swaps the bitmap instead of just repositioning the old one.
                    if (iconChanged) em.onNext(HideSymbols(listOf(GHOST_SYMBOL_ID)))
                    // Only rotate directional icons (the arrow) to the route heading; upright glyphs
                    // (ghost/cyclist) and the symmetric dot are drawn at 0° so they don't tilt sideways.
                    val orientation = if (ghostIconRotates(cfg.ghostIcon)) m.bearingDeg else 0.0f
                    em.onNext(
                        ShowSymbols(
                            listOf(Symbol.Icon(GHOST_SYMBOL_ID, m.lat, m.lng, iconRes, orientation)),
                        ),
                    )
                    lastGhostMarker = m
                    lastGhostEmitMs = now
                    lastIconRes = iconRes
                    // Log only the interesting Shows (heartbeat re-assert / icon swap), NOT every ~1 Hz
                    // routine move — a per-tick String.format("%.5f", …) would box+format every second
                    // for hours, and the arg is built even in release (before the no-op log call).
                    if (force || iconChanged) Timber.d("KVP ghost SHOW force=$force iconChanged=$iconChanged")
                }
                MapEmit.Hide -> {
                    em.onNext(HideSymbols(listOf(GHOST_SYMBOL_ID)))
                    lastGhostMarker = null
                    lastIconRes = 0
                    // Reset the heartbeat clock so "time since last shown" doesn't carry a stale value
                    // across a Hide (the next Show forces anyway because lastGhostMarker is null).
                    lastGhostEmitMs = now
                    Timber.d("KVP ghost HIDE")
                }
                MapEmit.None -> {}
            }
        }
    }

    /**
     * Dedicated ~5 Hz loop that makes the on-map ghost GLIDE instead of hopping once per gap tick.
     * It reads the [mapGhostState] snapshot the 1 Hz tick publishes and interpolates the ghost's
     * time-based route position between ticks using wall-clock (capped by [MAX_GHOST_EXTRAP_MS], and
     * frozen while [ridePaused]), then emits the marker. The loop is the SOLE emitter of the ghost
     * during a ride (both show and hide), so there is no race with the tick — the tick only flips
     * [mapGhostState]. Started in [startTick], cancelled in [stopTick]/[stopTickAndJoin].
     */
    private fun startMapLoop() {
        if (mapLoopJob?.isActive == true) return
        mapLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(MAP_REFRESH_MS)
                val s = mapGhostState
                if (s == null || mapEmitter == null || !activeConfig.value.showGhostOnMap) {
                    publishGhostMarker(null) // hide (idempotent — no-op when already hidden)
                    continue
                }
                // Interpolate the ghost clock from the last tick anchor: freeze on pause, else advance
                // by wall-clock capped so a stalled tick can't run the ghost away.
                val extrapMs = if (ridePaused) {
                    0L
                } else {
                    (System.currentTimeMillis() - s.anchorWallMs).coerceIn(0L, MAX_GHOST_EXTRAP_MS)
                }
                val ghostElapsed = (s.anchorElapsedS + extrapMs / 1000.0) - s.ghostStartElapsedS
                val ghostDistM = s.rg.distanceAt(ghostElapsed)
                publishGhostMarker(GhostMapPresenter.marker(ghostDistM, s.path, fresh = true))
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
            // Only do the heavy match while RECORDING. While previewing on standby, stash the latest
            // route event and bail — startTick() replays it once recording begins. (A route loaded
            // mid-ride hits this with isRecording already true and matches immediately.)
            if (!isRecording) {
                pendingNavState = event
                return
            }
            pendingNavState = null
            val routePolyline = state.routePolyline
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
                    // Build the ONE continuous whole-route ghost (recorded stretches + VP-pace fills).
                    // Fill pace = the always-present VP target (default 12 km/h), so the ghost always
                    // flows across gaps with no recorded history.
                    val routeGhost = RouteGhost.build(path.totalM, matched, activeConfig.value.targetMs())
                    // Single atomic publish: path + segments + ghost together so the tick never sees a
                    // NEW path paired with OLD segments. Guarded: only publish if a newer route has not
                    // superseded us (lastMatchedPolyline still ours).
                    if (lastMatchedPolyline == mine) {
                        routeMode = RouteMode(path, matched, routeGhost)
                        Timber.d(
                            "route mode ON: ${matched.size} segment(s), routeGhost=${routeGhost != null} " +
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
            // Non-route state (or racing disabled): the previewed/active route went away, so drop any
            // deferred preview match so startTick() doesn't later replay a dead route.
            pendingNavState = null
            // Debounce the teardown: the host can emit a transient Idle/NavigatingToDestination blip
            // between NavigatingRoute re-emits. Clearing immediately would null
            // routeMode/lastMatchedPolyline → a needless full re-match (and a one-tick VP/`---` flicker)
            // on the next same-route re-emit. Schedule the clear after a grace; a returning route
            // cancels it (above). Only schedule if there is something to clear and no clear is pending.
            if ((routeMode != null || lastMatchedPolyline != null) && clearJob?.isActive != true) {
                clearJob = scope.launch { // scope is Dispatchers.Main
                    delay(ROUTE_CLEAR_GRACE_MS)
                    clearRouteMode()
                    clearJob = null
                }
            }
        }
    }

    /** Clears ② route mode so the tick falls back to ① Ghost Pace behavior. */
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
        // Clear the map-loop snapshot AND hide directly. publishGhostMarker is internally synchronized
        // on mapLock, so it is safe to call from any caller thread (this can run on a Default coroutine
        // via onNavigationState).
        mapGhostState = null
        publishGhostMarker(null)
    }

    // `.sample()` is a @FlowPreview API; opting in here (same convention as KSafe's LocationManager).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startTick() {
        if (tickJob?.isActive == true) {
            Timber.d("KVP startTick SKIP (tick already active)")
            return
        }
        Timber.d("KVP startTick START (route=${routeMode != null})")
        isRecording = true
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
        // Start the ~5 Hz map loop that interpolates and emits the ghost marker between gap ticks.
        startMapLoop()
        // ① Ghost Pace state (used when route mode is OFF).
        // Dead-reckoning estimator that owns BOTH the effective distance and its quality: it
        // extrapolates (coasts) the DISTANCE stream at the last known speed during a GPS gap and
        // reports LONG_LOSS for a prolonged loss (the gap is then shown as an estimate, and the
        // tick gives up + blanks only after GPS_GIVEUP_S). Replaces the old DistanceProgress path.
        val coast = CoastingEstimator()
        // Cache the ghost curve and rebuild it only when the target speed changes.
        // GhostPaceSource.curve() allocates a fresh curve on every call, so building it
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
        // One-shot guard for the "GPS lost" alert: set true when fired, re-armed to false when GPS
        // recovers (coast back to LIVE). Local to the tick so it resets per ride.
        var gpsAlertFired = false

        tickJob = scope.launch(Dispatchers.Default) {
            val distance = karooSystem.streamDataFlow(DataType.Type.DISTANCE)
            val elapsed = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
            // SPEED (m/s) is streamed to distinguish "stopped at a light" (frozen distance is
            // legitimate) from "GPS lost while moving" (frozen distance is wrong → blank to `---`).
            val speed = karooSystem.streamDataFlow(DataType.Type.SPEED)
            combine(distance, elapsed, speed) { d, e, sp -> Triple(d, e, sp) }
                .sample(REFRESH_MS) // rate-limit BEFORE conflate so we tick at most once per REFRESH_MS
                .conflate()
                // Drop the first combined emission of a FRESH tick subscription. karoo-ext replays the
                // last-known StreamState to a new subscriber, so on a new ride the DISTANCE stream can
                // briefly carry the PREVIOUS ride's odometer (e.g. 40 km) at elapsed≈0 before the ride
                // app zeros it — computing a gap against that flashes a wildly wrong value for one tick.
                // The next tick (~1 s later) carries fresh values. A Paused→Recording resume does NOT
                // re-drop (startTick early-returns while the tick is active). A host reconnect DOES
                // re-drop: onConnected cancels the tick so the replayed Recording rebuilds this flow —
                // costing one extra `---` tick, which is acceptable (a fresh binding can replay a stale
                // DISTANCE, so dropping that first frame is the right call there too).
                .drop(1)
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

                    // ① Ghost Pace machinery, hoisted to run EVERY tick regardless of mode so
                    // it is always ready as the fallback when no segment is active. coast tracks the
                    // whole-ride DISTANCE odometer (dead-reckoning during brief GPS gaps); the cached
                    // VP curve is rebuilt lazily when the target changes (below, in vpGap).
                    coast.update(distM, speedMs, elapsedS)
                    // GPS-loss alert: when the whole-ride odometer has been dead-reckoned (frozen while
                    // GPS-loss handling for the ACTIVE mode's estimator (pass coast in ① VP mode, the
                    // route projector's coast in ② route mode — sourcing it per-mode keeps the alert in
                    // step with the field's estimate mark, since the route projection can stall on an
                    // off-route deviation while the whole-ride odometer keeps climbing). Fires the
                    // one-shot "GPS lost" alert at GPS_ALERT_S, re-arms when GPS recovers (coasting 0),
                    // and RETURNS true once the loss is so long (>= GPS_GIVEUP_S) that we give up and
                    // blank. coast.update already ran above so ①'s machinery stays warm as the fallback.
                    fun handleGpsLoss(coastingS: Double): Boolean {
                        if (coastingS >= GPS_ALERT_S) {
                            if (!gpsAlertFired) {
                                gpsAlertFired = true
                                karooSystem.dispatch(
                                    InRideAlert(
                                        id = "kghost-gps-lost-${System.currentTimeMillis()}",
                                        icon = R.drawable.ic_gps_lost,
                                        title = applicationContext.getString(R.string.gps_lost_title),
                                        detail = applicationContext.getString(R.string.gps_lost_detail),
                                        autoDismissMs = 10_000L,
                                        backgroundColor = R.color.gps_alert_bg,
                                        textColor = R.color.gps_alert_text,
                                    ),
                                )
                                Timber.w("KVP GPS lost > ${GPS_ALERT_S}s — alert dispatched")
                            }
                        } else if (coastingS == 0.0) {
                            gpsAlertFired = false
                        }
                        return coastingS >= GPS_GIVEUP_S
                    }
                    // Computes the ① VP gap (whole-ride distance vs elapsed at the fixed target pace).
                    // The VP target is ALWAYS present (defaults to 12 km/h — it can't be deactivated, it's
                    // the fallback), so this always returns a gap. The gap is shown even while
                    // dead-reckoning; only a prolonged loss (LONG_LOSS) marks it as an estimate
                    // (fresh = false) — it never blanks for GPS loss.
                    fun vpGap(): GapState {
                        val target = activeConfig.value.targetMs()
                        if (cachedTargetMs != target || cachedCurve == null) {
                            cachedCurve = GhostPaceSource(target).curve()
                            cachedTargetMs = target
                        }
                        return GapCalculator.compute(
                            coast.effectiveDistanceM, elapsedS, cachedCurve!!,
                            fresh = coast.quality != CoastQuality.LONG_LOSS,
                        )
                    }

                    // --- mode select: ② route mode vs ① Ghost Pace ---------------------
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
                        // a GPS gap so segment selection and the gap keep tracking the rider's assumed
                        // position; a PROLONGED loss flags LONG_LOSS (→ the gap is shown as an estimate,
                        // never blanked). We feed the RAW projected distance; the estimator owns the
                        // coast (so rp.onRoute is folded in below only as a hard gate when the fix IS
                        // fresh).
                        cr.update(rp.progressM, speedMs, elapsedS)
                        val routeDist = cr.effectiveDistanceM
                        // Fire/re-arm the GPS-lost alert off the ROUTE estimator, and give up after a
                        // sustained (~3 min) loss: blank the field and hide the ghost rather than show a
                        // wildly-extrapolated route position.
                        if (handleGpsLoss(cr.coastingSeconds)) {
                            SegmentInfoHolder.clear()
                            mapGhostState = null // give up: hide the ghost (the loop hides it)
                            GapStateHolder.clear()
                            return@runCatching
                        }
                        // "live" = a real/short-coast fix AND not a genuine off-route deviation. When the
                        // projector fix IS fresh (a new position arrived) but the rider is off-route, we
                        // mark it (not live) — a deviation, not a dropout. While coasting (frozen
                        // projection) onRoute reflects the last good fix, so a stale off-route flag does
                        // not override the coast. !live → the gap renders as an estimate, it never blanks.
                        val fresh = cr.quality != CoastQuality.LONG_LOSS && (!rp.isFresh || rp.onRoute)
                        // Which recorded stretch the rider is currently on — drives only the data
                        // fields' SEG/VP tag (via SegmentInfoHolder). The ghost itself is whole-route,
                        // not per-segment, so this does NOT gate the ghost or the gap.
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
                            // Couldn't build the continuous whole-route ghost. Fall back to the ①
                            // whole-ride Ghost Pace gap (always available); no map ghost.
                            SegmentInfoHolder.clear()
                            mapGhostState = null
                            GapStateHolder.update(vpGap())
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
                        // ④ ghost-on-map: hand the ~5 Hz map loop an immutable snapshot so it can
                        // interpolate the ghost's time-based position BETWEEN these 1 Hz ticks and make
                        // the marker glide. We anchor the ghost clock here (elapsedS + wall-clock now);
                        // the loop advances it smoothly until the next tick. Drawn regardless of GPS
                        // freshness — the ghost position is purely time-based, so a dropout/stop never
                        // makes it "unknown". null disables it (rider turned the map ghost off).
                        mapGhostState = if (activeConfig.value.showGhostOnMap) {
                            MapGhostState(
                                rg = rg,
                                path = rm.path,
                                ghostStartElapsedS = ghostStartElapsedS!!,
                                anchorElapsedS = elapsedS,
                                anchorWallMs = System.currentTimeMillis(),
                            )
                        } else {
                            null
                        }
                    } else {
                        // ① Ghost Pace mode — no route (or empty segments). coast was already
                        // updated above; the helper coasts the DISTANCE stream at the last known speed
                        // during a GPS gap (keeping the gap accurate as an estimate) and treats a genuine
                        // stop as legitimate (frozen distance). It never blanks for a GPS loss — only a
                        // missing target blanks — EXCEPT after a sustained (~3 min) loss, where
                        // handleGpsLoss() gives up and we blank rather than show a wild extrapolation.
                        SegmentInfoHolder.clear()
                        mapGhostState = null // VP mode: no map ghost (the loop hides it)
                        if (handleGpsLoss(coast.coastingSeconds)) {
                            GapStateHolder.clear()
                        } else {
                            GapStateHolder.update(vpGap())
                        }
                    }
                    }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.e(e, "tick iteration failed")
                    }
                }
                .collect {}
        }
        // Now that we're recording, run the match for any route that was previewed on standby (the
        // match was deferred to avoid burning battery on a race that might never start).
        pendingNavState?.let { onNavigationState(it) }
    }

    private fun stopTick() {
        isRecording = false
        // Drop any route stashed during a preview: it must not survive into the NEXT ride's startTick
        // replay (it would activate route mode against a route the rider is no longer navigating).
        pendingNavState = null
        tickJob?.cancel()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
        // Stop the map loop (the sole ghost emitter) and clear its snapshot. This is the onDestroy path
        // (not suspend, so a plain cancel): the scope is cancelled right after and the host tears the
        // map layer down with the service, so a last in-flight Show racing the hide below is moot. The
        // ride-end path is stopTickAndJoin(), which cancelAndJoins to close that race.
        mapGhostState = null
        mapLoopJob?.cancel()
        mapLoopJob = null
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
        isRecording = false
        // See stopTick(): a previewed route must not leak into the next ride via the startTick replay.
        pendingNavState = null
        tickJob?.cancelAndJoin()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
        // Stop the map loop and clear its snapshot, then the Idle handler's publishGhostMarker(null)
        // hides. cancelAndJoin (not a bare cancel) so no in-flight loop iteration can re-Show the ghost
        // AFTER the hide — its publishGhostMarker has no suspension point, so a plain cancel wouldn't
        // stop a started iteration, leaving a stale marker stuck on the map after the ride.
        mapGhostState = null
        mapLoopJob?.cancelAndJoin()
        mapLoopJob = null
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
