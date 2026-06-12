package com.enderthor.kghost.extension

import com.enderthor.kghost.BuildConfig
import com.enderthor.kghost.FileLogTree
import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.datatype.GapGraphicDataType
import com.enderthor.kghost.datatype.GapNumericDataType
import com.enderthor.kghost.datatype.GapStreamDataType
import com.enderthor.kghost.engine.CoastQuality
import com.enderthor.kghost.engine.CoastingEstimator
import com.enderthor.kghost.engine.GapCalculator
import com.enderthor.kghost.engine.GapState
import com.enderthor.kghost.engine.GapStateHolder
import com.enderthor.kghost.engine.GhostCurve
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.engine.LiveSegment
import com.enderthor.kghost.engine.RenderPrefs
import com.enderthor.kghost.engine.RouteGhost
import com.enderthor.kghost.engine.SegmentInfoHolder
import com.enderthor.kghost.engine.EffectiveProfile
import com.enderthor.kghost.engine.resolveProfile
import com.enderthor.kghost.engine.learnProfile
import com.enderthor.kghost.engine.StalenessLogic
import com.enderthor.kghost.engine.GhostPaceSource
import com.enderthor.kghost.engine.toInfo
import com.enderthor.kghost.engine.AGG_MIN_LAPS
import com.enderthor.kghost.engine.AGG_START_TOL_M
import com.enderthor.kghost.engine.AGG_STEP_M
import com.enderthor.kghost.engine.updateAggregate
import com.enderthor.kghost.geo.AggregateStore
import com.enderthor.kghost.geo.BBox
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.PolylinePath
import com.enderthor.kghost.geo.SegmentMatcher
import com.enderthor.kghost.geo.TrackRecorder
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.geo.routeKeyOf
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.map.GhostMapPresenter
import com.enderthor.kghost.map.GhostMarker
import com.enderthor.kghost.map.ghostIconRes
import com.enderthor.kghost.map.ghostIconRotates
import com.enderthor.kghost.data.GhostSize
import com.enderthor.kghost.map.ghostSizeForZoom
import com.enderthor.kghost.map.MapEmit
import com.enderthor.kghost.map.decideMapEmit
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.InRideAlert
import java.io.File
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
 *      * **② Route mode** (when a navigated route is loaded and `raceEnabled`): takes the rider's
 *        route position from the Karoo's own map-matched DISTANCE_TO_DESTINATION (routeDist =
 *        routeLen − remaining) and computes the gap against the
 *        continuous whole-route ghost (recorded stretches stitched with VP-pace fills). Publishes
 *        which recorded [LiveSegment] is currently active to [SegmentInfoHolder] — used only to show
 *        the data fields' SEG (racing your past self) vs GP (fixed-pace Ghost Pace) tag.
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
// The extension id "kghost" MUST match res/xml/extension_info.xml (id="kghost"); the version is taken
// from the build's versionName so it never drifts from the released build (was a stale hardcoded "0.1.0").
class KGhostExtension : KarooExtension("kghost", BuildConfig.VERSION_NAME) {

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
         * Ghost map-marker refresh cadence (ms) on the Karoo 3. The marker is interpolated and re-emitted
         * at this rate (~5 Hz) by a dedicated loop, INDEPENDENT of the 1 Hz gap tick — the ghost position
         * is a pure function of elapsed ride time, so it can be sampled between ticks without any new GPS.
         * This is what makes the ghost glide instead of hopping once per second. The actual rate used is
         * [mapRefreshMs], chosen by hardware — this is the K3 default.
         */
        private const val MAP_REFRESH_MS = 200L

        /**
         * Map-marker cadence (ms) on the Karoo 2 (~3 Hz). The K2 has a slower SoC and a smaller battery,
         * so the busiest loop runs ~40 % fewer wakeups there; the marker still glides at 3 Hz. (RouteGraph
         * applies the same K2-halving idea to its own busiest work.)
         */
        private const val MAP_REFRESH_MS_K2 = 333L

        /**
         * Map-loop cadence (ms) when there is no ghost to show (VP mode / route not yet matched / map
         * ghost disabled). Saves ~4 wakeups/s relative to the active rate: the loop just checks
         * [mapGhostState] and calls publishGhostMarker(null), which is a no-op when already hidden.
         * 1 s gives a worst-case latency of ~1 s from the tick setting mapGhostState to the first
         * glide frame — acceptable since the tick itself has ~1 s latency.
         */
        private const val MAP_IDLE_REFRESH_MS = 1000L

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

        /**
         * Horizontal accuracy (m) at or below which a GPS fix is TRUSTED. The Karoo serves its
         * cached/default position (often a city 20+ km away — its last known location) as the first
         * "fix" before a true satellite lock; that pre-lock position carries a poor accuracy. Gating
         * lastLat/lastLng on this keeps the bogus fix out of the route projector, the recorder and the
         * ghost anchor, so the gap is built only on the rider's REAL position — no movement-jump
         * heuristics that would also fire on legitimate route skips. A real outdoor lock is typically
         * < 15 m, so 50 m trusts genuine fixes (even degraded urban/tree-cover ones) while rejecting a
         * far cached default. PROVISIONAL: the per-fix accuracy is logged ("KVP loc: … acc=…m") so this
         * threshold can be tuned from real rides.
         */
        private const val GPS_GOOD_ACCURACY_M = 50.0

        /**
         * Route-distance gap (m) below which two recorded stretches are treated as CONTINUOUS: when the
         * rider rides off the end of one stretch and the next begins within this distance, the EXIT
         * alert is suppressed (the upcoming ENTRY will speak), so abutting/close stretches never fire an
         * exit popup immediately followed by an entry popup. Distance-based, not time-based, so it does
         * the right thing regardless of speed/pauses, and it never swallows a legitimate entry to a
         * genuinely FAR stretch (entries always fire). ~1 km ≈ the old "a few minutes" intent in distance.
         */
        private const val SEG_CLOSE_GAP_M = 1000.0

        /**
         * Slack (m) for the odometric plausibility filter. Route progress can never exceed the physical
         * distance ridden (the route is ≤ the path travelled), so when the Karoo's routeDist jumps beyond
         * the odometer delta by more than this slack it is physically impossible — a loop snap to a later
         * pass through the same streets (km 9 → km 20), or a backward re-correction. The slack only
         * absorbs GPS noise in the two measurements; the real signal (a loop snap) is kilometres.
         */
        private const val REROUTE_JUMP_SLACK_M = 500.0

        /**
         * Remaining-to-destination (m) at/under which the rider is treated as having REACHED the route
         * end. At the finish we (A) trust the Karoo's snap to the route end — bypassing the odometric
         * plausibility filter, which would otherwise reject the legitimate end-snap forever when GPS
         * dropped the final metres (the odometer ridden since the last good fix is then SMALLER than the
         * remaining route distance, so the snap looks "spurious") and pin the rider short of the line —
         * and (B) FREEZE the gap so it stops inflating against the still-advancing ghost clock.
         */
        private const val ROUTE_END_EPS_M = 8.0

        /**
         * remaining < [ROUTE_END_EPS_M] must PERSIST this long before it counts as a genuine finish, so a
         * transient DISTANCE_TO_DESTINATION glitch mid-route can't false-freeze the race. Pure debounce —
         * the frozen gap value is captured live once confirmed.
         */
        private const val ROUTE_END_CONFIRM_MS = 4_000L

        /** How often the diagnostic-log uploader sends the new tail of the current ride's log. */
        private const val LOG_SEND_INTERVAL_MS = 20 * 60_000L

        /**
         * Max characters per uploaded chunk. The Karoo `httpRequest` body crosses the host Binder
         * transaction at ~80 KB (KSafe's empirical CALIBRATION_MAX_CHUNK_BYTES = 72 000), so each POST
         * must stay well under it — 60 000 chars ≈ 60 KB of ASCII log + multipart overhead. A bigger
         * body fails with TransactionTooLargeException, so the WHOLE feature silently never delivers.
         */
        private const val LOG_CHUNK_CHARS = 60_000

        /** Chunks per PERIODIC cycle, so one window can't monopolise the link draining a huge backlog. */
        private const val LOG_PERIODIC_MAX_CHUNKS = 6

        /** Device model sanitised for the upload filename/caption (e.g. "Karoo 3" → "Karoo-3"). */
        private val DEVICE_LABEL: String by lazy {
            android.os.Build.MODEL.trim()
                .replace(' ', '-')
                .replace(Regex("[^A-Za-z0-9._-]"), "")
                .take(20)
                .ifEmpty { "device" }
        }
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
    private var destJob: Job? = null
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
    // ActiveRideProfile consumer — drives per-profile target + the master/per-profile gate. Owned by [scope].
    private var rideProfileJob: Job? = null
    // Active Karoo ride profile id (RideProfile.id), updated by rideProfileJob. Read on the tick to
    // resolve the effective per-profile target + enable. Null until the first ActiveRideProfile arrives.
    @Volatile
    private var activeProfileId: String? = null

    // --- ② route / history state -------------------------------------------
    // On-disk store of recorded tracks (history) + the in-memory recorder for the current ride.
    // Resolved lazily (see [trackStore]) rather than pinned once in onCreate: the rider can grant
    // all-files access AFTER the extension started, and tracksDir() then flips from the internal
    // fallback to /sdcard/KGhost/tracks. Caching the store forever would keep reading the (empty)
    // internal dir for the whole service lifetime, so no imported ghosts would EVER load until the
    // app was killed and recreated — exactly the "I granted permission but it doesn't re-read until
    // I close the app" bug.
    @Volatile private var trackStoreCache: TrackStore? = null
    @Volatile private var trackStoreDir: File? = null
    @Volatile private var aggregateStoreCache: AggregateStore? = null
    @Volatile private var aggregateStoreDir: File? = null
    private val recorder = TrackRecorder()

    // ── Average-ghost lap capture ───────────────────────────────────────────────────────────────
    // This ride's route progress as (routeDistM, riderTimeFromStartS) samples, decimated to one per
    // ~25 m of route advance. Written by the tick COROUTINE (Dispatchers.Default), read once at ride-end
    // by finishAndSaveRecording(). Safe because the Idle handler runs stopTickAndJoin() (cancelAndJoin)
    // BEFORE finishAndSaveRecording(), so the join establishes a happens-before edge from the last tick
    // write to the read — there is no concurrent access on that path. The synchronizedList only keeps
    // the list internally consistent on the onDestroy teardown (non-joining cancel); it is not what
    // makes the ride-end read correct. @Volatile on the target below is the cross-thread publish.
    private val lapAggBuffer = java.util.Collections.synchronizedList(ArrayList<DoubleArray>())

    /** The route this lap's samples fold into at ride-end — one immutable value so key/name/length can
     *  never be observed out of sync. null ⇒ the lap does NOT contribute (no route, or the rider joined
     *  mid-route so the time origin would be offset). */
    private data class LapAggTarget(val key: String, val name: String, val lenM: Double)
    @Volatile private var lapAggTarget: LapAggTarget? = null

    // Latest GPS fix from the location stream. Read by both the recorder and the route projector
    // on the tick. @Volatile because the location collector and the tick may run on different
    // dispatcher threads. NaN until the first finite fix arrives (finite-guarded on write).
    @Volatile
    private var lastLat: Double = Double.NaN
    @Volatile
    private var lastLng: Double = Double.NaN

    // The Karoo's own remaining-distance-to-destination (m) on the navigated route, and whether the
    // rider is on that route. Source of the authoritative route position (routeDist = routeLen −
    // remaining) used by the ② route tick, replacing the local GPS projection. Written by [destJob]
    // (a different dispatcher thread than the tick), so @Volatile. NaN/false until the first emission
    // or while off route / without a fix — the tick then holds ---.
    @Volatile
    private var lastDistToDestM: Double = Double.NaN
    @Volatile
    private var lastOnRoute: Boolean = false
    // Wall-clock (ms) of the last time the route-remaining VALUE actually changed (not every emission).
    // Drives route-position staleness: while moving, if remaining stops changing the route fix is lost
    // (independent of the whole-ride odometer). 0 until the first change.
    @Volatile
    private var lastDestChangeMs: Long = 0L
    // Whether the Karoo is currently offering a REJOIN path (rider off-route, being guided back). When
    // true the route position is not trustworthy even if ON_ROUTE hasn't flipped yet — mirrors how
    // RouteGraph nulls its along-route position whenever rejoinDistance/rejoinPolyline is set. Written
    // from the nav stream (before the match dedup), read on the tick.
    @Volatile
    private var lastRejoinActive: Boolean = false
    // Latest rejoin-path length (m) from the NavigatingRoute event. When the Karoo is guiding the
    // rider back, DISTANCE_TO_DESTINATION = rejoin_path + remaining_from_rejoin_point. So the planned
    // rejoin point on the original route = routeLen − (remaining − rejoinDist). Updated live as the
    // Karoo re-emits NavigatingRoute with an updated rejoin calculation. NaN when not rejoining.
    @Volatile
    private var lastRejoinDistM: Double = Double.NaN

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
        /**
         * The encoded polyline this mode was matched from — the route's IDENTITY. The tick compares
         * it (not the [path] object reference) to tell a genuinely NEW route from a same-route
         * re-match (a mid-ride settings change), which must NOT reset the race anchor.
         */
        val polyline: String,
        /** The loaded route's name (from NavigatingRoute) — used to key the average-ghost aggregate. */
        val routeName: String,
        val segments: List<LiveSegment>,
        /**
         * The continuous whole-route ghost — recorded stretches stitched with VP-pace fills (see
         * [RouteGhost]). Distance axis is ROUTE distance `[0, path.totalM]`. Null only when it could
         * not be built (no fill pace and gaps present); the tick then falls back to ① VP.
         */
        val routeGhost: GhostCurve?,
        /**
         * Total route length (m) as reported by the Karoo's NavigatingRoute — the scale that
         * DISTANCE_TO_DESTINATION's remaining distance is measured against, so route position =
         * routeDistanceM − remaining. Falls back to [path].totalM if the host reports a non-positive
         * value.
         */
        val routeDistanceM: Double,
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
    // The latest navigation event, kept so a mid-ride settings change can REPLAY it: the gate + match
    // snapshot the resolved mode/pick and dedup re-emits on the polyline, so a config edit or a
    // ride-profile switch would otherwise keep serving the old settings until the NEXT route load.
    // lastMatchSig is the signature of the resolved settings the last gate/match decision used; see
    // [rematchOnSettingsChange]. Both are touched only from collectors on [scope] (Main).
    private var lastNavEvent: OnNavigationState? = null
    private var lastMatchSig: MatchSig? = null
    // Last compact nav summary logged, to dedup the noisy NavigatingRoute re-emits (see onNavigationState).
    private var lastNavLog: String? = null
    // Last config summary logged, to dedup the per-emission config flow (log only on a real change).
    private var lastCfgLog: String? = null
    // Previous fileLogging value, so the "file logging ON" banner fires on the config off→on transition
    // independent of who set FileLogTree.enabled (the settings UI sets it immediately too).
    private var prevFileLogging = false

    // ── Diagnostic-log upload (only when file logging is ON) ─────────────────────────────────────
    // Periodically (and at ride end) uploads the NEW tail of the current ride's log to the developer's
    // Telegram, with GPS coordinates redacted by LogReporter. Off entirely unless the rider enabled the
    // diagnostic log AND the build carries credentials (local.properties).
    private var logSendJob: kotlinx.coroutines.Job? = null
    // The Anon tag (stable opaque install id) — fetched once, shown in the UI and the upload caption.
    @Volatile
    private var installId: String? = null
    // Chars of the current ride's log already uploaded, so each cycle sends only what's new. Reset to 0
    // (with [logChunkSeq]) on each new ride file.
    @Volatile
    private var sentLogChars: Int = 0
    @Volatile
    private var logChunkSeq: Int = 0
    // Guards against a periodic drain and the ride-end drain running concurrently (both advance
    // sentLogChars), which could skip or duplicate a chunk.
    @Volatile
    private var logSendInFlight = false

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
    // Ghost-marker loop cadence (ms), chosen by hardware in onConnected: ~3 Hz on the slower/smaller-
    // battery K2, ~5 Hz on the K3. Defaults to the K3 rate until the hardware is known.
    @Volatile
    private var mapRefreshMs: Long = MAP_REFRESH_MS
    // True while RideState.Paused — the map loop then freezes the ghost (no wall-clock extrapolation)
    // so a paused ghost holds position instead of drifting forward. Set from the RideState collector.
    @Volatile
    private var ridePaused = false
    // Latest map zoom level [8,18] from OnMapZoomLevel (15.0 mid-range until the first event). Drives
    // the ghost icon's automatic size (the drawable is swapped S/M/L by zoom so it stays proportionate
    // to the map). @Volatile: written by the zoom collector, read in publishGhostMarker.
    @Volatile
    private var currentMapZoom = 15.0

    // The on-screen data fields rendering the GapState, plus the stream-only gap publishers other
    // extensions can consume (TYPE_EXT::kghost::kghost-gap-time / ::kghost-gap-dist). typeIds must
    // match extension_info.xml exactly.
    override val types by lazy {
        listOf(
            GapGraphicDataType(applicationContext),
            GapNumericDataType(applicationContext),
        ) + GapStreamDataType.all()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigurationManager(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        // tracksDir() does file IO (mkdirs + one-time migration); onCreate runs off the main
        // thread for an extension service, so it's safe here. Resolves shared external storage
        // when all-files access is granted, else falls back to internal. Priming it here keeps the
        // early-migration behaviour; trackStore() re-resolves later if the rider grants access.
        trackStore()
        // loadConfigFlow() already applies migrateToLatest(); no need to migrate again here.
        configManager.loadConfigFlow().onEach {
            activeConfig.value = it
            // Drive the file-logger toggle off the rider's config (a backstop to the settings UI, which
            // already sets it immediately — this re-asserts it on a service restart). On the off→on
            // transition write a banner so ride/session boundaries are findable in the rolling log.
            if (it.fileLogging && !prevFileLogging) {
                Timber.i("===== KGhost ${BuildConfig.VERSION_NAME} file logging ON =====")
            }
            prevFileLogging = it.fileLogging
            FileLogTree.enabled = it.fileLogging
            // Log the active settings as context for any bug report, deduped so it only fires on a real
            // change ("I changed X and it didn't apply"). startTick() also logs a baseline per ride.
            val cfgSig = configSummary(it)
            if (cfgSig != lastCfgLog) {
                lastCfgLog = cfgSig
                Timber.i("KVP config: $cfgSig")
            }
            // A config edit can change the RESOLVED mode/pick/target mid-route → re-match if it did.
            rematchOnSettingsChange()
        }.launchIn(scope)
        // Feed the render-prefs holder so the data fields don't each open their own DataStore
        // subscription just to read gapDisplay (single writer here; fields are readers).
        activeConfig
            .map { it.gapDisplay }
            .distinctUntilChanged()
            .onEach { RenderPrefs.setGapDisplay(it) }
            .launchIn(scope)
        // One-time backlog sweep: clean the EXISTING library once (off-Main), then stamp the epoch so it
        // never re-runs. Gated on autoTidy; sweep() has its own hard cap for a degenerate library.
        scope.launch(Dispatchers.IO) {
            // Pay the index's lazy one-time costs (legacy rebuild) and repair index/file drift NOW,
            // at service start off-Main — not under the indexLock on the FIRST route match of a ride.
            runCatching { trackStore().prewarmAndReconcile() }
                .onFailure { Timber.w(it, "KVP index prewarm failed") }
            // Prune aggregate blobs for routes not ridden in ages (renamed/deleted routes pile up).
            // Sweep the INTERNAL fallback dir too: aggregatesDir()'s heal copies internal-only blobs
            // into the canonical external dir, so a stale blob deleted only there would zombie back
            // from internal on the next resolution — with a fresh mtime, never aging out again.
            runCatching {
                aggregateStore().sweep()
                AggregateStore(File(applicationContext.filesDir, AggregateStore.DIR_NAME)).sweep()
            }.onFailure { Timber.w(it, "KVP aggregate sweep failed") }
            val cfg = configManager.loadConfigFlow().first()
            if (cfg.autoTidy && cfg.tidySweepEpoch == 0L) {
                val archived = runCatching { trackStore().sweep() }.getOrElse { e ->
                    Timber.w(e, "KVP tidy: backlog sweep failed"); 0
                }
                Timber.i("KVP tidy: backlog sweep archived $archived")
                configManager.updateConfig { it.copy(tidySweepEpoch = System.currentTimeMillis()) }
            }
        }
        karooSystem.connect { connected -> if (connected) onConnected() }
    }

    private fun onConnected() {
        // Now that the host is bound, hardwareType is known: run the busiest loop (the ~5 Hz ghost-marker
        // loop) slower on the K2 to spare its weaker SoC / smaller battery. Cheap to re-read on reconnect.
        mapRefreshMs = if (karooSystem.hardwareType == HardwareType.K2) MAP_REFRESH_MS_K2 else MAP_REFRESH_MS
        // connect{}'s callback re-fires on every reconnect (host service rebind after an app update,
        // OOM kill, or transient unbind). Cancel the prior collectors first so we don't stack a
        // second RideState/nav consumer — N of them would each call startTick/finish in parallel and
        // race the recorder. The callbackFlow's awaitClose removes the underlying host consumer too.
        rideJob?.cancel()
        navJob?.cancel()
        zoomJob?.cancel()
        profileJob?.cancel()
        rideProfileJob?.cancel()
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
        // destJob (route-progress) is wired to the dead binding for the SAME reason as tick/location, so
        // cancel it too — otherwise it silently stops emitting and route mode freezes on `---` for the
        // rest of the ride. Reset its @Volatile outputs so the rebuilt tick can't read a STALE
        // lastOnRoute/remaining from the previous binding and latch a bogus D0 on its first tick (the
        // route-progress fields aren't covered by the combine's drop(1), which only guards DISTANCE).
        destJob?.cancel()
        destJob = null
        lastDistToDestM = Double.NaN
        lastOnRoute = false
        lastDestChangeMs = 0L
        // lastRejoinActive is driven by the nav stream (navJob is relaunched below). DISTRUST until that
        // re-stamps it: routeMode is preserved across reconnect and destJob replays its last on-route
        // state fast, so a `false` here would briefly say "on route, no rejoin" and could latch a bogus
        // D0 from a rejoin-relative remaining if the reconnect happened mid-rejoin. `true` holds --- until
        // the fresh NavigatingRoute confirms the real rejoin state — matching the distrust-default of the
        // three resets above (NaN / false).
        lastRejoinActive = true
        lastRejoinDistM = Double.NaN
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
                    // Upload the ride's final log tail (GPS redacted) before the file rotates next ride.
                    // maxChunks high so the remaining backlog drains; periodic normally kept it small.
                    scope.launch { runCatching { sendLogTail("ride-end", maxChunks = 200) } }
                    recordingStartedEpoch = 0L // backstop; finish() already cleared it
                }
                else -> {}
            }
        }.launchIn(scope)

        // ② route load → match recorded history into live segments; clear on non-route states.
        navJob = karooSystem.streamNavigationState().onEach { onNavigationState(it) }.launchIn(scope)
        // Map zoom → auto-scale the ghost icon. Cheap (changes only when the rider zooms). Log only when
        // the zoom crosses into a different ghost-SIZE bucket (the thing that actually changes the icon),
        // so "is the zoom affecting the ghost?" is answerable without flooding on every zoom tick.
        var lastZoomSize: GhostSize? = null
        zoomJob = karooSystem.streamMapZoom().onEach { z ->
            currentMapZoom = z
            val size = ghostSizeForZoom(z)
            if (size != lastZoomSize) {
                lastZoomSize = size
                Timber.d("KVP zoom=${"%.1f".format(z)} → ghost size=$size")
            }
        }.launchIn(scope)
        // Rider's distance unit → the gap fields render metres or feet accordingly.
        profileJob = karooSystem.streamUserProfile()
            .onEach {
                RenderPrefs.setImperialDistance(
                    it.preferredUnit.distance == io.hammerhead.karooext.models.UserProfile.PreferredUnit.UnitType.IMPERIAL,
                )
            }
            .launchIn(scope)
        // Active ride profile → remember its id for the tick's per-profile resolution, and auto-learn
        // it into the roster (so a never-customised profile still appears in settings after one ride).
        rideProfileJob = karooSystem.streamRideProfile()
            .distinctUntilChanged()
            .onEach { profile ->
                activeProfileId = profile.id
                // A profile switch can change the RESOLVED mode/pick/target mid-route (per-profile
                // overrides) → re-match if it did, so the new profile's settings apply immediately.
                rematchOnSettingsChange()
                runCatching {
                    configManager.updateConfig { cfg ->
                        cfg.copy(profileSettings = learnProfile(cfg.profileSettings, profile.id, profile.name))
                    }
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "learnProfile persist failed for ${profile.id} — continuing")
                }
                Timber.i("KVP active profile: id=${profile.id} name=${profile.name}")
            }
            .launchIn(scope)

        startLogSendLoop()
    }

    /**
     * Periodically uploads the NEW tail of the current ride's diagnostic log to the developer's
     * Telegram (GPS coordinates redacted), and the ride-end tail is sent from the Idle handler. Runs
     * only while a ride is active AND file logging is on; otherwise each cycle is a cheap no-op. The
     * whole feature is dormant unless the rider turned the diagnostic log on and the build carries
     * Telegram credentials.
     */
    private fun startLogSendLoop() {
        // Start ONCE. onConnected re-fires on every host rebind, but this loop holds no host consumer
        // (it just calls karooSystem.httpRequest, and karooSystem is the same instance across
        // reconnects), so restarting it would only reset the periodic timer on every reconnect —
        // starving the periodic send on a flaky link. Survives reconnects untouched.
        if (logSendJob?.isActive == true) return
        logSendJob = scope.launch {
            while (true) {
                delay(LOG_SEND_INTERVAL_MS)
                if (!FileLogTree.enabled) continue
                if (tickJob?.isActive != true) continue // only during an active ride
                sendLogTail("periodic", LOG_PERIODIC_MAX_CHUNKS)
            }
        }
    }

    /**
     * Uploads the part of the current ride's log not yet sent ([sentLogChars]) to the developer's
     * Telegram via [LogReporter] (which redacts every GPS coordinate first, so no location data leaves
     * the device), in chunks of [LOG_CHUNK_CHARS] — each `httpRequest` body MUST stay under the host
     * Binder transaction limit (~80 KB) or the whole POST fails. Sends at most [maxChunks] per call.
     *
     * Runs entirely on [Dispatchers.IO] — the owning [scope] is Main, and reading a multi-MB file +
     * the multipart build must never touch the Main thread (ANR). Advances [sentLogChars] only per
     * SUCCESSFUL chunk, so a failure just retries from the same point next cycle; an in-flight guard
     * stops the periodic and ride-end drains overlapping.
     */
    private suspend fun sendLogTail(prefix: String, maxChunks: Int) = withContext(Dispatchers.IO) {
        if (!FileLogTree.enabled || !::karooSystem.isInitialized) return@withContext
        if (logSendInFlight) return@withContext
        val file = FileLogTree.currentLogFile() ?: return@withContext
        logSendInFlight = true
        try {
            // Make sure the last second of buffered lines is on disk before we read.
            FileLogTree.requestFlush()
            delay(400)
            val full = runCatching { file.readText() }.getOrNull() ?: return@withContext
            if (full.length <= sentLogChars) return@withContext
            val id = installId ?: runCatching { configManager.getOrCreateInstallId() }.getOrNull()
                ?.also { installId = it } ?: return@withContext
            val sid = FileLogTree.sessionId
            val ver = BuildConfig.VERSION_NAME
            var sent = 0
            while (sentLogChars < full.length && sent < maxChunks) {
                val hardEnd = minOf(sentLogChars + LOG_CHUNK_CHARS, full.length)
                // Cut on a line boundary within the window so chunks stay readable; fall back to a
                // hard cut if a single line somehow exceeds the window.
                val nl = full.lastIndexOf('\n', hardEnd - 1)
                val end = if (nl > sentLogChars) nl + 1 else hardEnd
                val chunk = full.substring(sentLogChars, end)
                if (chunk.isBlank()) break
                val lines = chunk.count { it == '\n' }
                val fileName = "kghost_v${ver}_${id}_${sid}_p${"%03d".format(logChunkSeq)}_$DEVICE_LABEL.log"
                val caption = "KGhost log ($prefix)\nAnon tag: $id\nSession: $sid | $DEVICE_LABEL | v$ver | $lines lines"
                val res = LogReporter.sendLogFile(chunk, fileName, caption, karooSystem)
                if (res.ok) {
                    sentLogChars = end
                    logChunkSeq += 1
                    sent++
                } else {
                    Timber.w("KVP log upload ($prefix) chunk failed: ${res.message}")
                    break
                }
            }
            if (sent > 0) Timber.i("KVP log upload ($prefix) ✓ — $sent chunk(s), through char $sentLogChars")
        } finally {
            logSendInFlight = false
        }
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
            // The on-map ghost icon can be a per-profile override — resolve it against the active
            // profile (a small roster scan + one short-lived allocation; trivial at ~5 Hz).
            val ghostIcon = resolveProfile(cfg, activeProfileId).ghostIcon
            // Size follows the map zoom automatically (Symbol.Icon has no size field; we swap S/M/L).
            // A zoom change flips iconRes → iconChanged below → Hide+Show, so the icon rescales promptly.
            val iconRes = if (next != null) ghostIconRes(ghostIcon, ghostSizeForZoom(currentMapZoom)) else 0
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
                    // Rotate all directional icons (arrow, ghost, cyclist) to the route heading.
                    // DOT is rotationally symmetric so stays at 0°.
                    val orientation = if (ghostIconRotates(ghostIcon)) m.bearingDeg else 0.0f
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
                    // Include zoom + size here so an icon-swap caused by a zoom change is self-explanatory
                    // (answers "is the zoom affecting the ghost?").
                    if (force || iconChanged) {
                        Timber.d(
                            "KVP ghost SHOW force=$force iconChanged=$iconChanged " +
                                "zoom=${"%.1f".format(currentMapZoom)} size=${ghostSizeForZoom(currentMapZoom)} " +
                                "lat=${"%.5f".format(m.lat)} lng=${"%.5f".format(m.lng)}",
                        )
                    }
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
                // Back off to a slow poll when there is no ghost to show (VP mode / before the first
                // route match) OR while the ride is paused — a paused ghost is frozen in place
                // (extrapMs = 0 below), so the ~5 Hz rate buys nothing during a café stop and just
                // burns battery. The next slow delay (≤ MAP_IDLE_REFRESH_MS) restores the fast rate.
                val s = mapGhostState
                delay(if (s == null || ridePaused) MAP_IDLE_REFRESH_MS else mapRefreshMs)
                val s2 = mapGhostState
                if (s2 == null || mapEmitter == null || !activeConfig.value.showGhostOnMap) {
                    publishGhostMarker(null) // hide (idempotent — no-op when already hidden)
                    continue
                }
                // Interpolate the ghost clock from the last tick anchor: freeze on pause, else advance
                // by wall-clock capped so a stalled tick can't run the ghost away.
                val extrapMs = if (ridePaused) {
                    0L
                } else {
                    (System.currentTimeMillis() - s2.anchorWallMs).coerceIn(0L, MAX_GHOST_EXTRAP_MS)
                }
                val ghostElapsed = (s2.anchorElapsedS + extrapMs / 1000.0) - s2.ghostStartElapsedS
                val ghostDistM = s2.rg.distanceAt(ghostElapsed)
                publishGhostMarker(GhostMapPresenter.marker(ghostDistM, s2.path, fresh = true))
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
        // Keep the latest event so rematchOnSettingsChange() can replay it after a mid-ride settings
        // change. Resolve + stamp the settings signature only for ROUTE events (the only ones whose
        // gate/match read the resolved settings) — the host re-emits nav states constantly, and a
        // roster scan + EffectiveProfile alloc per non-route emission would be pure waste.
        lastNavEvent = event
        val effNow = if (state is OnNavigationState.NavigationState.NavigatingRoute) {
            resolveProfile(activeConfig.value, activeProfileId).also { lastMatchSig = matchSignature(it) }
        } else {
            null
        }
        // Compact + deduped: the host re-emits NavigatingRoute MANY times (computing climbs/progress);
        // logging the full object (polyline + climbs arrays) on every re-emit floods the log. Summarise,
        // and log only when the summary changes. The dedup key avoids String.format (which allocates a
        // Formatter + StringBuilder) on the hot re-emit path — toInt() is allocation-free by JVM rules
        // for small values; the full formatted string is built only when something actually changes.
        val navKey = when (state) {
            is OnNavigationState.NavigationState.NavigatingRoute ->
                "NavigatingRoute|${state.name}|${state.routeDistance.toInt()}|${state.rejoinDistance != null || state.rejoinPolyline != null}"
            else -> state::class.simpleName ?: "?"
        }
        if (navKey != lastNavLog) {
            lastNavLog = navKey
            val logMsg = when (state) {
                is OnNavigationState.NavigationState.NavigatingRoute ->
                    "NavigatingRoute name=${state.name} routeLen=${"%.0f".format(state.routeDistance)} " +
                        "rejoin=${state.rejoinDistance != null || state.rejoinPolyline != null}"
                else -> navKey
            }
            Timber.d("nav=$logMsg")
        }
        if (state is OnNavigationState.NavigationState.NavigatingRoute &&
            // Gate on BOTH the mode (raceEnabled) AND eff.active (master kill-switch + per-profile
            // enable). Without the eff.active check the heavy match (polyline decode + candidate file IO
            // + O(n) SegmentMatcher) would run on a disabled profile or while the master switch is off —
            // the tick would then just discard it. Honors the "master off ⇒ fully inert" contract.
            effNow != null && effNow.active && effNow.raceEnabled
        ) {
            // Track REJOIN state live (the host re-emits NavigatingRoute as it computes a rejoin, so this
            // updates even though the heavy match below dedups on the polyline). A non-null rejoin means
            // the rider is off-route being guided back → the route position is not trustworthy; the tick
            // gates on this in addition to ON_ROUTE. Store the rejoin distance so the tick can estimate
            // the planned rejoin point: routeLen − (remaining − rejoinDist).
            lastRejoinActive = state.rejoinDistance != null || state.rejoinPolyline != null
            lastRejoinDistM = state.rejoinDistance ?: Double.NaN
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
                    // Route-length-adaptive candidate cap (RouteGraph buckets its sampling by distance the
                    // same way). The matcher is O(track points) per track via its spatial grid, so a long
                    // road route with a realistic history matches fast — but each candidate track is itself
                    // long, so a pathological history (many overlapping 100 km+ rides) could spike GC /
                    // Default-pool contention at route-load and delay ② activating. Capping the candidate
                    // count on long routes bounds that without touching match accuracy (it only limits how
                    // many historical rides are considered, which on a long route is naturally few anyway).
                    val maxTracks = when {
                        path.totalM > 200_000 -> 40
                        path.totalM > 120_000 -> 70
                        else -> SegmentMatcher.Params().maxTracks
                    }
                    // Pre-cap candidates by ROUTE OVERLAP (relevance), parsing only the top tracks.
                    val tracks = trackStore().loadTopCandidates(bbox, maxTracks)
                    // A superseding route should cancel this stale match promptly: bail before the
                    // expensive match if we've been cancelled.
                    currentCoroutineContext().ensureActive()
                    // Resolve the active profile once: mode/pick/target may all be per-profile overrides.
                    val eff = resolveProfile(activeConfig.value, activeProfileId)
                    val pick = eff.ghostPick
                    val matched = if (pick == GhostPick.AVERAGE) {
                        // AVERAGE races the per-route EMA aggregate where it is raceable, and the rider's
                        // BEST everywhere else. Keyed on the DECODED-POLYLINE length — deterministic for a
                        // route file, unlike state.routeDistance which can be NaN on early emissions and
                        // drift across recalcs; an unstable length would round to a different key on a
                        // later ride and silently orphan the aggregate. The ride-end save keys on the same
                        // rm.path.totalM.
                        val avgSegs = aggregateStore().load(routeKeyOf(state.name, path.totalM))
                            ?.toLiveSegments().orEmpty()
                        // ALWAYS run the BEST match too: the aggregate covers only stretches with
                        // ≥AGG_MIN_LAPS laps, and falling to the bare Ghost-Pace fill on stretches where
                        // the rider HAS recorded history would throw that history away. Warmup (no
                        // aggregate at all) is then just the all-BEST case of the same merge.
                        val bestSegs = SegmentMatcher.match(path, tracks, GhostPick.BEST, SegmentMatcher.Params(maxTracks = maxTracks))
                        if (avgSegs.isEmpty()) {
                            Timber.i("KVP avg: no raceable aggregate for '${state.name}' yet (needs ≥$AGG_MIN_LAPS laps from the route start) — racing BEST while it warms up")
                        } else {
                            Timber.i("KVP avg: racing the aggregate on ${avgSegs.size} stretch(es), BEST on the rest")
                        }
                        // The aggregate wins where it is raceable; BEST pieces are TRIMMED around it (not
                        // dropped whole) so partial overlap can't discard a long recorded stretch.
                        RouteGhost.overlay(avgSegs, bestSegs)
                    } else {
                        SegmentMatcher.match(path, tracks, pick, SegmentMatcher.Params(maxTracks = maxTracks))
                    }
                    // Build the ONE continuous whole-route ghost (recorded stretches + VP-pace fills).
                    // Fill pace = the always-present VP target (default 12 km/h), so the ghost always
                    // flows across gaps with no recorded history.
                    // NOTE: the per-profile target is snapshotted at match time; a mid-route profile
                    // change takes effect only after a re-match (nav state change). The live per-tick gap
                    // still uses the current target via eff.targetSpeedMs — only the VP-fill pace is snapshotted.
                    val routeGhost = RouteGhost.build(path.totalM, matched, eff.targetSpeedMs)
                    // Single atomic publish: path + segments + ghost together so the tick never sees a
                    // NEW path paired with OLD segments. Guarded: only publish if a newer route has not
                    // superseded us (lastMatchedPolyline still ours). The `mine` claim is the polyline
                    // STRING, so a settings-change re-match of the SAME route re-claims an equal string —
                    // the ensureActive() is what stops this (cancelled) match from publishing a result
                    // built under the OLD settings over the replacement's.
                    currentCoroutineContext().ensureActive()
                    if (lastMatchedPolyline == mine) {
                        routeMode = RouteMode(path, mine, state.name, matched, routeGhost, state.routeDistance)
                        // Diagnostic for the scale question: the Karoo's routeDistance (the scale that
                        // DISTANCE_TO_DESTINATION is measured against) vs the decoded-polyline length (the
                        // scale segments + the ghost curve live on). A large delta means routeDist needs
                        // rescaling before it's compared to segment bounds / fed to the ghost.
                        Timber.d(
                            "route mode ON: ${matched.size} segment(s), routeGhost=${routeGhost != null} " +
                                "on '${state.name}' karooLen=${"%.0f".format(state.routeDistance)} " +
                                "polyLen=${"%.0f".format(path.totalM)} delta=${"%.0f".format(state.routeDistance - path.totalM)}",
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
            lastRejoinActive = false
            lastRejoinDistM = Double.NaN
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

    /**
     * The resolved settings the route gate + match depend on; a change makes the current match stale.
     * A small value object (not a string) so the per-nav-emission stamp allocates next to nothing.
     * targetSpeedMs is deliberately EXCLUDED: it only snapshots the VP-FILL pace (the live gap reads
     * the current target every tick), and including it would clear + fully re-match the route on
     * every keystroke while the rider edits the Ghost Pace mid-navigation — the stale fill pace until
     * the next route load is the long-standing, documented trade (see the match NOTE above).
     */
    private data class MatchSig(val active: Boolean, val raceEnabled: Boolean, val pick: GhostPick)

    private fun matchSignature(eff: EffectiveProfile): MatchSig =
        MatchSig(eff.active, eff.raceEnabled, eff.ghostPick)

    /**
     * Re-evaluates the route gate/match when the RESOLVED settings change mid-ride (a config edit or a
     * ride-profile switch). The match snapshots the mode/pick/fill-target and dedups re-emits on the
     * polyline, so without this a mid-ride change keeps serving the OLD settings until the next route
     * load: a switch to a Fixed-pace profile would keep racing the old segments, and a pick change
     * (Best→Last→Average) would never apply. On a real change: drop the claim + route mode and replay
     * the latest nav event so the gate and match re-run under the new settings (a now-closed gate means
     * the clear simply stands and the tick falls back to ① VP). Runs on [scope] (Main), same as
     * onNavigationState, so there is no race with the gate's own signature stamp.
     */
    private fun rematchOnSettingsChange() {
        val sig = matchSignature(resolveProfile(activeConfig.value, activeProfileId))
        if (sig == lastMatchSig) return
        lastMatchSig = sig
        // Nothing claimed/stashed ⇒ nothing the change could have staled; the next nav event resolves fresh.
        if (routeMode == null && lastMatchedPolyline == null && pendingNavState == null) return
        Timber.i("KVP settings changed mid-route → re-matching ($sig)")
        clearRouteMode()
        lastNavEvent?.let { onNavigationState(it) }
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

    /** Compact one-line summary of the active settings, for the log (config-change + ride-start baseline). */
    private fun configSummary(c: KGhostConfig): String =
        "raceEnabled=${c.raceEnabled} target=${"%.1f".format(c.targetMs())}m/s pick=${c.ghostPick} " +
            "showMap=${c.showGhostOnMap} icon=${c.ghostIcon} entryAlert=${c.segmentEntryAlert} " +
            "exitAlert=${c.segmentExitAlert} autoRecord=${c.autoRecord}"

    // `.sample()` is a @FlowPreview API; opting in here (same convention as KSafe's LocationManager).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startTick() {
        if (tickJob?.isActive == true) {
            Timber.d("KVP startTick SKIP (tick already active)")
            return
        }
        // Ride-start config baseline: guarantees every ride's log records the active settings even if the
        // last config-change line rotated out of the file (answers "what settings was this ride on?").
        Timber.i("KVP startTick START (route=${routeMode != null}) config: ${configSummary(activeConfig.value)}")
        isRecording = true
        // Only stamp the epoch on a genuinely fresh start. If the tick coroutine previously died
        // mid-ride (e.g. an exception) a later Recording emission re-enters startTick; without this
        // guard it would reset the epoch mid-ride → wrong track id / partial double-save risk. The
        // epoch is cleared to 0L by stopTick()/finishAndSaveRecording(), so the next ride after an
        // Idle still gets a fresh stamp.
        if (recordingStartedEpoch == 0L) {
            recordingStartedEpoch = System.currentTimeMillis()
            // Rotate the diagnostic log to a fresh per-ride file so each ride's logs are
            // self-contained and distinguishable. No-op when file logging is disabled.
            FileLogTree.newRide(recordingStartedEpoch)
            // Fresh ride file ⇒ nothing uploaded yet from it.
            sentLogChars = 0
            logChunkSeq = 0
        }
        // GPS location consumer — subscribed only while Recording. Feeds the ride RECORDER (the route
        // position itself now comes from the Karoo, see destJob below). We stream the LOCATION DataType
        // (not streamLocation()) because only the DataType carries LOC_ACCURACY, which we use to keep a
        // cached/default pre-lock fix OUT of the recorded track. lastLat/lastLng are written ONLY for a
        // trusted (accurate) fix. @Volatile (written here, read on the tick).
        // Throttle for the loc log: log the fix whenever the TRUST state flips (the acquisition story —
        // cached pre-lock fix → real lock — fully captured) but only ~every 5 s in steady state (an
        // unchanging trusted=true / acc=5m every second is just noise). Reset per ride with the job.
        var lastLocLogMs = 0L
        var lastLocTrusted: Boolean? = null
        if (locationJob?.isActive != true) {
            locationJob = karooSystem.streamDataFlow(DataType.Type.LOCATION).onEach { state ->
                val dp = (state as? StreamState.Streaming)?.dataPoint ?: return@onEach
                val lat = dp.values[DataType.Field.LOC_LATITUDE]
                val lng = dp.values[DataType.Field.LOC_LONGITUDE]
                val acc = dp.values[DataType.Field.LOC_ACCURACY]
                if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return@onEach
                val trusted = acc != null && acc.isFinite() && acc <= GPS_GOOD_ACCURACY_M
                if (trusted) {
                    lastLat = lat
                    lastLng = lng
                }
                val nowMs = System.currentTimeMillis()
                if (trusted != lastLocTrusted || nowMs - lastLocLogMs >= 5_000L) {
                    lastLocTrusted = trusted
                    lastLocLogMs = nowMs
                    Timber.d(
                        "KVP loc: lat=${"%.5f".format(lat)} lng=${"%.5f".format(lng)} " +
                            "acc=${acc?.let { "%.0f".format(it) } ?: "null"}m trusted=$trusted",
                    )
                }
            }.launchIn(scope)
        }
        // Route-progress consumer — the Karoo's OWN map-matched distance-to-destination + ON_ROUTE
        // flag. The ② route tick derives the rider's authoritative route position from this (routeDist
        // = routeLen − remaining), so a loop is unambiguous and a bogus cached fix can't place us.
        // ON_ROUTE/remaining go absent when off route or without a fix → the tick holds ---.
        if (destJob?.isActive != true) {
            destJob = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION).onEach { state ->
                val dp = (state as? StreamState.Streaming)?.dataPoint
                val newRemaining = dp?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) ?: Double.NaN
                // Stamp the change time only on a real (finite, different) value move — so a frozen feed
                // (GPS lost) stops refreshing it while a moving rider's steadily-decreasing remaining
                // keeps it fresh. A stationary rider's unchanged remaining is handled by the movement
                // gate at the read site, not here.
                if (newRemaining.isFinite() && newRemaining != lastDistToDestM) {
                    lastDestChangeMs = System.currentTimeMillis()
                }
                lastDistToDestM = newRemaining
                lastOnRoute = dp?.values?.get(DataType.Field.ON_ROUTE) == 1.0
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
        // Identity of the route the current per-route anchor state belongs to. When this differs from
        // the live RouteMode.path we reset the anchor (D0 + ghost clock) so a new route never inherits
        // the old one's. Null when no route is loaded. ② route position now comes from the Karoo's own
        // map-matched DISTANCE_TO_DESTINATION (see the tick), not a local GPS projection.
        var projectorRoute: PolylinePath? = null
        // The route IDENTITY (encoded polyline) the anchor state belongs to. Compared by VALUE so a
        // same-route re-match (mid-ride settings change → fresh RouteMode, new path object) keeps the
        // anchor; only a genuinely different polyline (new route / reroute) resets it.
        var projectorPolyline: String? = null
        // The rider's route distance (m) at the START OF THE CURRENT ROUTE — "D0". Computed ONCE on the
        // first trustworthy on-route fix as (distanceAlongRoute − distanceRiddenSinceThisRouteBegan):
        // invariant while on-route, so it back-figures any head start ridden BLIND before GPS locked and
        // detects a deliberate mid-route start. null until set.
        var routeStartDistM: Double? = null
        // Ride odometer (m) at the moment THIS route became active — the baseline for "distance ridden
        // since this route began". 0 for the first route (ride start); on a reroute it's the odometer
        // then, so D0 stays correct (the new route's distance restarts at 0 while the odometer doesn't).
        var rideDistAtRouteStartM = 0.0
        // Ride-elapsed (s) when the rider FIRST started moving this ride (speed over the moving
        // threshold). BOTH modes' race clocks start here, NOT at ride-elapsed 0, so a stationary wait
        // for a GPS lock is never counted as a deficit — independent of the Karoo's optional auto-pause.
        // Re-nulled on a route change (the new route re-anchors). null until the rider moves.
        var firstMoveElapsedS: Double? = null
        // Ride-elapsed seconds at the whole-route ghost's t=0. Set ONCE per route to −rg.timeAt(D0) so
        // the ghost sits at D0 at ride-elapsed 0 and then advances on REAL elapsed time (ghostElapsed =
        // elapsedS − ghostStartElapsedS = elapsedS + rg.timeAt(D0)). No rider-position anchor, so the
        // gap self-corrects the instant the Karoo position becomes real. null until set.
        var ghostStartElapsedS: Double? = null
        // The rider's last TRUSTWORTHY route distance (m). While off-route / rejoining the Karoo's
        // remaining is rejoin-relative (untrustworthy), so we freeze here the last good along-route
        // position and keep showing the ghost + an ESTIMATE gap from it (like a GPS dropout) instead of
        // blanking — it self-corrects the instant the rider rejoins. null until the first good fix.
        var lastGoodRouteDistM: Double? = null
        // Ride odometer (m) captured alongside [lastGoodRouteDistM]. The odometric plausibility filter
        // compares the Karoo's routeDist jump against the odometer delta here: route progress can never
        // exceed physical distance ridden, so a jump far beyond it (a loop snap) is rejected. null until
        // the first good fix; both are updated together only on a trustworthy (non-spurious) tick.
        var distMAtLastGoodM: Double? = null
        // Highest route distance (m) already captured into [lapAggBuffer] this route. We append one
        // average-ghost sample per ~AGG_STEP_M of forward progress, so the buffer is bounded to
        // routeLen/step entries regardless of ride duration. Reset (with the buffer) on a route change.
        var lastBufferedRouteDist = Double.NEGATIVE_INFINITY
        // Throttle for the per-tick route-mode diagnostic log (≤ ~once per DIAG_LOG_MS). Kept local
        // to the tick so it resets per ride. Set to 0 to disable.
        var lastDiagLogMs = 0L
        val diagLogMs = 2500L
        // One-shot guard for the "GPS lost" alert: set true when fired, re-armed to false when GPS
        // recovers (coast back to LIVE). Local to the tick so it resets per ride.
        var gpsAlertFired = false
        // Previous tick's moving state (route mode). Drives the stationary→moving edge that re-stamps
        // lastDestChangeMs: a stationary rider's remaining legitimately never changes, so without the
        // re-stamp the first moving tick after a ≥GPS_ALERT_S stop would read the whole stop duration
        // as route-position staleness and fire a spurious "GPS lost" alert on every resume.
        var wasMoving = false
        // First-fix confirmation candidate for the route position (pos, odometer at that pos). The
        // odometric plausibility filter needs a trusted baseline and D0 is latched ONCE (invariant), so
        // the very first on-route fix — which can be a wrong-pass snap on a self-intersecting route —
        // must be confirmed by a second, odometrically-consistent fix before anything trusts it.
        var d0CandPos: Double? = null
        var d0CandOdo: Double? = null
        // Start-distance of the recorded stretch the rider was on at the previous tick — a stable
        // per-route key. Edge-triggers the "segment entry" alert exactly once on entry (and again on
        // crossing into a DIFFERENT stretch), never every tick while on it. null = between segments /
        // off-route last tick. Local to the tick so it resets per ride.
        var prevSegStartM: Double? = null
        // Wall-clock (ms) when remaining-to-destination FIRST fell below [ROUTE_END_EPS_M], or 0 while
        // it's above. Debounces the finish: only a remaining that STAYS sub-EPS for ≥ROUTE_END_CONFIRM_MS
        // is a real arrival at the destination, so a one-tick remaining glitch mid-route can't freeze the
        // race. Reset on a route change.
        var routeEndSinceMs = 0L
        // The final gap, captured ONCE when the rider reaches the route end, then re-published every tick
        // so it stops inflating — the ghost clock keeps advancing past a stationary finisher (ghostElapsed
        // climbs while the route position is pinned at the end), which would otherwise read an ever-growing
        // BEHIND. Cleared if remaining climbs back out of the finish band (a pass-through destination) or on
        // a route change. null until the finish is confirmed.
        var finishedGap: GapState? = null

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

                    // Cache the config snapshot once per tick: activeConfig.value is a volatile StateFlow
                    // read (memory barrier), and it is accessed 5+ times below — reading it once and
                    // reusing the snapshot eliminates the repeated barriers and guarantees the tick sees
                    // a consistent config even if the rider changes a setting mid-tick.
                    val cfg = activeConfig.value

                    // ① Ghost Pace machinery, hoisted to run EVERY tick regardless of mode so
                    // it is always ready as the fallback when no segment is active. coast tracks the
                    // whole-ride DISTANCE odometer (dead-reckoning during brief GPS gaps); the cached
                    // VP curve is rebuilt lazily when the target changes (below, in vpGap). Updated
                    // BEFORE the per-profile gate so the odometer stays in sync even while the extension
                    // is inert — otherwise re-activating after a disabled stretch would see a distance
                    // jump and misread it as a GPS freeze.
                    coast.update(distM, speedMs, elapsedS)

                    // Per-profile + master gate: when inactive the extension is fully inert — clear the
                    // gap/segment fields (→ `---`), hide the ghost, skip recording, and emit nothing.
                    // The service stays subscribed, so flipping the master switch or the profile's enable
                    // (config flow) or changing profile (rideProfileJob) re-activates on the next tick.
                    val eff: EffectiveProfile = resolveProfile(cfg, activeProfileId)
                    if (!eff.active) {
                        GapStateHolder.clear()
                        SegmentInfoHolder.clear()
                        mapGhostState = null
                        return@runCatching
                    }

                    // History recording: feed the decimating recorder the latest fix while the ride
                    // is recording (only when autoRecord is on). Skipped until a finite GPS fix has
                    // arrived. The recorder decimates by distance, so a 1 Hz feed is fine.
                    if (cfg.autoRecord) {
                        val lat = lastLat
                        val lng = lastLng
                        if (lat.isFinite() && lng.isFinite()) {
                            recorder.onSample(lat, lng, distM, elapsedS)
                        }
                    }
                    // GPS-loss handling, fed the ACTIVE mode's staleness seconds: the whole-ride odometer
                    // coast in ① VP mode, the route-position staleness (lastDestChangeMs) in ② route mode.
                    // Sourcing it per-mode keeps the alert in step with the field's estimate mark, since the
                    // route fix can stall (nav wedged) while the whole-ride odometer keeps climbing, or vice
                    // versa. Fires the one-shot "GPS lost" alert at GPS_ALERT_S, re-arms when the signal
                    // recovers (coastingS back to 0), and RETURNS true once the loss is so long
                    // (>= GPS_GIVEUP_S) that we give up and blank. coast.update already ran above so ①'s
                    // machinery stays warm as the fallback.
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
                        } else if (coastingS < GPS_ALERT_S * 0.5) {
                            // Re-arm with HYSTERESIS once the loss is comfortably over — NOT on an exact
                            // `coastingS == 0.0`. In route mode coastingS is time-since-last-dest-change
                            // while moving, which after recovery sits at ~0–1 s but is essentially never
                            // bit-exactly 0.0 (only a full stop forces the 0.0 `!moving` branch). With the
                            // old `== 0.0` the alert re-armed only if the rider STOPPED, so a second GPS
                            // loss later in a non-stop ride never alerted. Half the fire threshold gives a
                            // clean gap (fire ≥60 s, re-arm <30 s) with no flapping at the boundary.
                            gpsAlertFired = false
                        }
                        return coastingS >= GPS_GIVEUP_S
                    }
                    // Dispatches a segment ENTRY/EXIT in-ride alert. Entry/exit differ only in strings; both
                    // use the blue "info" alert colours. There is NO time throttle — abutting double-pops are
                    // prevented structurally (publishSegment suppresses the EXIT when the next stretch is
                    // close), which never swallows a legitimate ENTRY to a far stretch.
                    fun fireSegAlert(entry: Boolean, label: String, atM: Double) {
                        karooSystem.dispatch(
                            InRideAlert(
                                id = "kghost-segment-${System.currentTimeMillis()}",
                                icon = R.drawable.ic_ghost,
                                title = applicationContext.getString(
                                    if (entry) R.string.segment_entry_title else R.string.segment_exit_title,
                                ),
                                detail = applicationContext.getString(
                                    if (entry) R.string.segment_entry_detail else R.string.segment_exit_detail,
                                ),
                                autoDismissMs = 8_000L,
                                backgroundColor = R.color.segment_alert_bg,
                                textColor = R.color.segment_alert_text,
                            ),
                        )
                        Timber.d("KVP segment ${if (entry) "entry" else "exit"} alert: $label @${"%.0f".format(atM)}m")
                    }
                    // Edge-triggered segment alerts + the per-tick SegmentInfoHolder publish, driven off the
                    // SAME active-segment decision so the alerts and the field's SEG/GP tag never disagree.
                    // ENTRY fires once when crossing INTO a recorded stretch (or a different one) — always, so
                    // a far stretch is never missed. EXIT fires once when leaving a stretch back to Ghost-Pace
                    // fill, UNLESS the next stretch begins within SEG_CLOSE_GAP_M (the stretches are
                    // effectively continuous → the upcoming ENTRY speaks instead, no exit+entry double-pop).
                    // [fireExit] is false on the GPS-loss / off-route / route-cleared paths: those are "lost
                    // the race position", not a genuine ride-off-the-end exit, so they clear silently and KEEP
                    // prevSegStartM (a brief map-match drop on the SAME stretch must not read as a re-entry).
                    fun publishSegment(seg: LiveSegment?, segments: List<LiveSegment> = emptyList(), fireExit: Boolean = true) {
                        if (seg == null) {
                            val leftStart = prevSegStartM
                            if (fireExit) {
                                if (leftStart != null && cfg.segmentExitAlert) {
                                    val left = segments.firstOrNull { it.routeStartM == leftStart }
                                    val nextStart = segments.asSequence()
                                        .map { it.routeStartM }
                                        .filter { it > (left?.routeEndM ?: leftStart) }
                                        .minOrNull()
                                    val nextIsClose = left != null && nextStart != null &&
                                        (nextStart - left.routeEndM) <= SEG_CLOSE_GAP_M
                                    if (nextIsClose) {
                                        Timber.d("KVP segment exit suppressed: next stretch within ${SEG_CLOSE_GAP_M.toInt()}m")
                                    } else {
                                        fireSegAlert(entry = false, label = "stretch", atM = leftStart)
                                    }
                                }
                                prevSegStartM = null
                            }
                            SegmentInfoHolder.clear()
                            return
                        }
                        if (cfg.segmentEntryAlert && seg.routeStartM != prevSegStartM) {
                            fireSegAlert(entry = true, label = seg.ghostLabel, atM = seg.routeStartM)
                        }
                        prevSegStartM = seg.routeStartM
                        SegmentInfoHolder.set(seg.toInfo())
                    }
                    // Route mode "hold ---": no trustworthy position to race yet (no fix, off route,
                    // rejoining, implausible remaining, given up, or race-not-started). Clears the gap +
                    // hides the ghost + clears the SEG tag without firing an exit alert. Each caller does
                    // its own (throttled) diag log then `holdGap(); return@runCatching`.
                    fun holdGap() {
                        publishSegment(null, fireExit = false)
                        mapGhostState = null
                        GapStateHolder.clear()
                    }
                    // Computes the ① VP gap (ridden distance vs race-elapsed at the fixed target pace).
                    // [raceElapsedS] is elapsed since FIRST MOVEMENT, not ride-start, so the ghost-pace
                    // race begins when the rider actually rolls (fair start) — the caller only reaches
                    // here once firstMove is set. The VP target is ALWAYS present (defaults to 12 km/h —
                    // it can't be deactivated, it's the fallback), so this always returns a gap. The gap
                    // is shown even while dead-reckoning; only a prolonged loss (LONG_LOSS) marks it as an
                    // estimate (fresh = false) — it never blanks for GPS loss.
                    fun vpGap(raceElapsedS: Double): GapState {
                        val target = eff.targetSpeedMs
                        if (cachedTargetMs != target || cachedCurve == null) {
                            cachedCurve = GhostPaceSource(target).curve()
                            cachedTargetMs = target
                        }
                        return GapCalculator.compute(
                            coast.effectiveDistanceM, raceElapsedS, cachedCurve!!,
                            fresh = coast.quality != CoastQuality.LONG_LOSS,
                        )
                    }
                    // Periodic diagnostic for ① VP (Ghost-Pace) mode — the branch that used to log nothing,
                    // so "the ghost wasn't there" was undiagnosable. [reason] says WHY we're in VP (no route
                    // vs a route with no buildable ghost curve). Same throttle as the route snapshot
                    // (~2.5 s). [gap] is null when the field is blanked (sustained GPS loss).
                    fun logVp(reason: String, gap: GapState?) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastDiagLogMs >= diagLogMs) {
                            lastDiagLogMs = nowMs
                            Timber.d(
                                "KVP tick VP ($reason): dist=${"%.0f".format(coast.effectiveDistanceM)} " +
                                    "elapsed=${"%.0f".format(elapsedS)} coast=${coast.quality} " +
                                    "coastS=${"%.0f".format(coast.coastingSeconds)} " +
                                    "gap=${gap?.let { "T=${"%.0f".format(it.gapTimeS)}s D=${"%.0f".format(it.gapDistanceM)}m ${if (it.ahead) "AHEAD" else "BEHIND"}" } ?: "---"} " +
                                    "speed=${speedMs?.let { "%.1f".format(it) } ?: "null"}",
                            )
                        }
                    }

                    // Record WHEN the rider first started moving this ride (speed over the moving
                    // threshold) BEFORE picking a mode, so BOTH the route ghost and the ① Ghost-Pace gap
                    // anchor their clock to the real race start — captured even before the first GPS fix
                    // so a blind-but-moving start (a paired speed sensor with no GPS yet) is timed from
                    // its real beginning. Until the rider moves, both modes hold --- (a stationary wait
                    // for a lock is never a deficit). A route change re-nulls this below to re-anchor.
                    if (firstMoveElapsedS == null && speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS) {
                        firstMoveElapsedS = elapsedS
                    }

                    // --- mode select: ② route mode vs ① Ghost Pace ---------------------
                    // Read the route-mode snapshot ONCE per tick so path + segments stay consistent
                    // even if the matcher publishes a new RouteMode mid-tick.
                    val rm = routeMode
                    if (rm != null && rm.segments.isNotEmpty()) {
                        // Reset the per-route anchor (D0, ghost clock, first-move, odometer baseline) when
                        // the ROUTE IDENTITY changes — including a Karoo REROUTE, which arrives as a new
                        // polyline and re-matches into a fresh RouteMode. Identity is the POLYLINE STRING,
                        // not the path object: a mid-ride settings change re-matches the SAME route into a
                        // fresh RouteMode (new path instance), and resetting the anchor there would restart
                        // the race + wipe the lap capture just because the rider toggled a setting. For a
                        // same-route re-match only the ghost CURVE changed → re-anchor just the ghost clock
                        // (next tick recomputes it from the preserved firstMove + D0 on the new curve).
                        if (projectorRoute !== rm.path) {
                            val sameRoute = projectorPolyline == rm.polyline
                            projectorRoute = rm.path
                            projectorPolyline = rm.polyline
                            ghostStartElapsedS = null
                            if (!sameRoute) {
                                routeStartDistM = null
                                firstMoveElapsedS = null
                                lastGoodRouteDistM = null
                                distMAtLastGoodM = null
                                d0CandPos = null
                                d0CandOdo = null
                                rideDistAtRouteStartM = distM
                                // New route ⇒ start a fresh average-ghost lap capture (the old route's
                                // partial samples must not fold into this one's aggregate).
                                lapAggBuffer.clear()
                                lapAggTarget = null
                                lastBufferedRouteDist = Double.NEGATIVE_INFINITY
                                // Distrust the OLD route's remaining until destJob emits the NEW route's value:
                                // routeDistanceM flips with the path (atomic in RouteMode) but lastDistToDestM is
                                // a separate stream, so pairing new routeLen with old remaining for a tick would
                                // compute a bogus routeDist and latch a wrong D0 (which is invariant → wrong for
                                // the whole new route). Holding --- one extra tick is the safe trade.
                                lastDistToDestM = Double.NaN
                                lastOnRoute = false
                                // New route ⇒ the segment-alert edge belongs to the old route; clear it so the
                                // first stretch on the new route is a fresh entry (and a preserved off-route
                                // prevSegStartM from the old route can't suppress it).
                                prevSegStartM = null
                                // New route ⇒ the previous route's finish no longer applies; race again.
                                routeEndSinceMs = 0L
                                finishedGap = null
                            }
                        }
                        val rg = rm.routeGhost
                        if (rg == null) {
                            // Couldn't build the continuous whole-route ghost. Fall back to the ①
                            // Ghost-Pace gap (always available); no map ghost. Same fair start as VP
                            // mode: hold --- until the rider first moves, so a stationary wait isn't a
                            // growing false deficit.
                            publishSegment(null, fireExit = false)
                            mapGhostState = null
                            val moveStart = firstMoveElapsedS
                            if (moveStart == null) {
                                GapStateHolder.clear()
                                logVp("route present, no ghost curve — waiting for first movement", null)
                            } else {
                                val g = vpGap(elapsedS - moveStart)
                                GapStateHolder.update(g)
                                logVp("route present, no ghost curve", g)
                            }
                            return@runCatching
                        }
                        // ② Route position from the Karoo itself (map-matched), NOT a local GPS
                        // projection: distance-along-route = total route length − the Karoo's
                        // remaining-to-destination. This makes a LOOP unambiguous (the Karoo tracks which
                        // pass you're on) and means a cached/default pre-lock fix can never place us — when
                        // there is no real on-route position the Karoo reports ON_ROUTE=false / no
                        // remaining, and we hold ---.
                        //
                        // TWO SCALES. remaining-to-destination is measured against the Karoo's OWN
                        // routeDistance (karooLenM), but the matched segments + the ghost curve live on the
                        // DECODED-POLYLINE scale (rm.path.totalM) — the two can differ by a few percent
                        // (different smoothing/snapping). Positions derived from `karooLen − remaining` are
                        // therefore RESCALED to the polyline scale before they're compared to segment bounds
                        // or fed to the ghost, so a 2 % skew can't shift segment boundaries by kilometres on
                        // a long route. An absurd ratio (outside 0.5–2.0) means one length is garbage —
                        // then we trust the values unscaled rather than amplify the error. All downstream
                        // route positions (D0, the odometric filter, lap capture) are on the polyline scale,
                        // which also matches the physical-odometer metres the filter compares against.
                        val routeLenM = rm.path.totalM
                        // Ratio guard: an absurd Karoo length (a garbage/stale value) must not be trusted
                        // AT ALL — neither for the scale factor nor for the `karooLen − remaining`
                        // subtraction (a 100 m karooLen on a 50 km route would pin routeDist at 0 and the
                        // implausible-remaining guard below would hold D0 forever). Out of band ⇒ fall back
                        // to the polyline length entirely (factor 1.0).
                        val karooLenM = rm.routeDistanceM
                            .takeIf { it.isFinite() && it > 0.0 && routeLenM / it in 0.5..2.0 }
                            ?: routeLenM
                        val karooToPoly = if (karooLenM > 0.0) routeLenM / karooLenM else 1.0
                        val remainingM = lastDistToDestM
                        // Trust the position only when on-route AND not mid-rejoin (a non-null rejoin means
                        // the Karoo is guiding the rider back; its remaining is then rejoin-relative, not a
                        // valid along-route position — RouteGraph nulls the position in exactly this case).
                        val haveRoutePos = lastOnRoute && !lastRejoinActive && remainingM.isFinite() && routeLenM > 0.0
                        if (!haveRoutePos) {
                            // No trustworthy on-route position THIS tick (no lock, off route, or rejoining).
                            //
                            // KEY INSIGHT: even mid-rejoin the Karoo's remaining-to-destination is KNOWN and
                            // COMPLETE — it already INCLUDES the rejoin detour (remaining = rejoin_path +
                            // remaining_from_the_rejoin_point). So `routeLen − remaining` (the SAME formula the
                            // on-route path uses) is a valid position: the detour stays counted, so the rider's
                            // effective progress dips slightly while off-route → they read a little BEHIND (the
                            // truth — a detour costs distance), never a false AHEAD, and it self-corrects the
                            // instant they rejoin. We deliberately do NOT add rejoinDist back (the old bug:
                            // `remaining − rejoinDist` cancelled the detour and teleported progress forward to
                            // the PLANNED rejoin point → a false AHEAD while off-route).
                            //
                            // ODOMETRIC CEILING — the one thing `remaining` CANNOT tell us is a wrong
                            // map-match: on a self-intersecting loop the Karoo can snap to a LATER pass, making
                            // remaining far too small and `routeLen − remaining` leap toward the route end.
                            // Cap progress at the last trusted position plus the metres actually ridden since:
                            // route progress can never exceed what was physically pedalled. NO
                            // REROUTE_JUMP_SLACK_M (that slack absorbs scale skew on the TRUSTED on-route
                            // comparison; here we cap an UNTRUSTED estimate tight). Null when there's no
                            // odometer baseline yet (the never-anchored bootstrap), where the raw value is the
                            // best we have.
                            //
                            // If the ghost has not been anchored yet (ghostStartElapsedS=null) and the Karoo
                            // enters rejoin BEFORE onRoute is ever seen (route loaded slightly off the path),
                            // the race would never start via the normal haveRoutePos path — so we allow
                            // anchoring here from this estimate.
                            val odoCeil: Double? = lastGoodRouteDistM?.let { lg ->
                                distMAtLastGoodM?.let { lg + (distM - it).coerceAtLeast(0.0) }
                            }
                            val estimatedRoutePos: Double? = if (lastRejoinActive && remainingM.isFinite()) {
                                // Same formula as on-route (detour stays counted), capped by the odometric
                                // ceiling so a later-loop-pass snap can't teleport us forward, floored at 0 so
                                // the detour penalty can pull progress back below the last good fix.
                                ((karooLenM - remainingM) * karooToPoly)
                                    .let { if (odoCeil != null) it.coerceAtMost(odoCeil) else it }
                                    .coerceIn(0.0, routeLenM)
                                    .takeIf { it.isFinite() }
                            } else null

                            // Best available position: prefer the estimate, then the last good pos.
                            val frozenPos = estimatedRoutePos ?: lastGoodRouteDistM

                            // Anchor the race from the estimated position if not yet anchored.
                            // This is the same math as the haveRoutePos path but using the estimate.
                            if (ghostStartElapsedS == null && frozenPos != null) {
                                if (routeStartDistM == null) {
                                    routeStartDistM = (frozenPos - (distM - rideDistAtRouteStartM)).coerceIn(0.0, routeLenM)
                                }
                                val moveStart = firstMoveElapsedS
                                if (moveStart != null) {
                                    val d0 = routeStartDistM!!
                                    ghostStartElapsedS = moveStart - rg.timeAt(d0)
                                    if (lastGoodRouteDistM == null) lastGoodRouteDistM = frozenPos
                                    Timber.i(
                                        "KVP race anchored (rejoin-fallback): firstMove=${"%.0f".format(moveStart)}s " +
                                            "D0=${"%.0f".format(d0)}m ghostStart=${"%.0f".format(ghostStartElapsedS!!)}s " +
                                            "@ estimatedPos=${"%.0f".format(frozenPos)}m elapsed=${"%.0f".format(elapsedS)}s",
                                    )
                                }
                            }

                            // Use the anchor (freshly set above or previously set) to show the gap.
                            val anchorS = ghostStartElapsedS
                            if (anchorS != null && frozenPos != null) {
                                val ghostElapsed = elapsedS - anchorS
                                val gap = GapCalculator.compute(frozenPos, ghostElapsed, rg, fresh = false)
                                GapStateHolder.update(gap)
                                // Keep the SEG/GP tag from the frozen position; the seg is unchanged so this
                                // fires no entry/exit alert (publishSegment is edge-triggered on routeStartM).
                                publishSegment(rm.segments.firstOrNull { frozenPos in it.routeStartM..it.routeEndM }, rm.segments)
                                mapGhostState = if (cfg.showGhostOnMap) {
                                    MapGhostState(rg, rm.path, anchorS, elapsedS, System.currentTimeMillis())
                                } else {
                                    null
                                }
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastDiagLogMs >= diagLogMs) {
                                    lastDiagLogMs = nowMs
                                    Timber.d("KVP tick route: off-route/rejoin (onRoute=$lastOnRoute rejoin=$lastRejoinActive) — ${if (estimatedRoutePos != null) "rejoin-estimated routeDist=${"%.0f".format(estimatedRoutePos)}" else "frozen routeDist=${"%.0f".format(frozenPos)}"} gapT=${"%.0f".format(gap.gapTimeS)}s (ghost kept)")
                                }
                                return@runCatching
                            }
                            // No anchor / no good position yet: hold --- and hide the ghost; leave D0/anchor
                            // null so they're set from the FIRST real on-route fix — not a fabricated start.
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d("KVP tick route: no on-route position yet (onRoute=$lastOnRoute rejoin=$lastRejoinActive remaining=${remainingM.takeIf { it.isFinite() }?.let { "%.0f".format(it) } ?: "null"}) — ghost/gap held")
                            }
                            holdGap()
                            return@runCatching
                        }
                        val routeDist = ((karooLenM - remainingM) * karooToPoly).coerceIn(0.0, routeLenM)
                        // Reject an IMPLAUSIBLE remaining before latching the one-shot D0 (a wrong D0 is
                        // invariant → wrong for the whole route). Two cases, both of which clamp routeDist to
                        // an extreme:
                        //  - remaining ≈ 0  → "at the route end": the host's default/0 served before the
                        //    position settles. (At the genuine end, routeStartDistM is already latched, so
                        //    this guard — gated on `== null` — doesn't fire there.)
                        //  - remaining > routeLen → routeDist clamps to 0 ("at the start"): a rejoin-relative
                        //    remaining the rejoin gate missed by a tick, or a transient scale skew at the start.
                        // Either way hold --- until a plausible value arrives; firstMove gates the race anyway.
                        if (routeStartDistM == null && (remainingM < 1.0 || remainingM > karooLenM)) {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d("KVP tick route: implausible remaining=${"%.0f".format(remainingM)} (karooLen=${"%.0f".format(karooLenM)}) — D0 held until it settles")
                            }
                            holdGap()
                            return@runCatching
                        }
                        // FIRST-FIX CONFIRMATION. The odometric filter below needs a trusted baseline, and
                        // D0 is latched ONCE (invariant for the whole route) — but the very FIRST on-route
                        // fix can be a wrong-pass snap on a self-intersecting route (the filter can't catch
                        // it: there is no baseline yet). Require two consecutive fixes that agree
                        // odometrically before trusting anything: tick 1 stores a candidate, tick 2 confirms
                        // it (or replaces it and waits one more). Costs one tick of --- at route
                        // acquisition; firstMove gates the race anyway, so a stationary start loses nothing.
                        if (lastGoodRouteDistM == null && routeStartDistM == null) {
                            val candPos = d0CandPos
                            val candOdo = d0CandOdo
                            val consistent = candPos != null && candOdo != null &&
                                kotlin.math.abs(routeDist - candPos) <=
                                (distM - candOdo).coerceAtLeast(0.0) + REROUTE_JUMP_SLACK_M
                            if (!consistent) {
                                d0CandPos = routeDist
                                d0CandOdo = distM
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastDiagLogMs >= diagLogMs) {
                                    lastDiagLogMs = nowMs
                                    Timber.d("KVP tick route: first fix at ${"%.0f".format(routeDist)}m — awaiting confirmation")
                                }
                                holdGap()
                                return@runCatching
                            }
                            // Confirmed: fall through — this tick's routeDist becomes the baseline below.
                        }
                        // END OF ROUTE — the rider has reached the destination (remaining≈0). Gated on
                        // routeStartDistM != null so this is the genuine FINISH, not the host's pre-position
                        // default-0 at the START (the implausible-remaining guard above catches that while
                        // routeStartDistM == null), and on ghostStartElapsedS != null so the race is anchored.
                        // Two things happen here, and ONLY here:
                        //   (A) TRUST the snap to the route end, bypassing the odometric filter below. At the
                        //       genuine finish the odometer ridden since the last good fix can be SMALLER than
                        //       the remaining route distance (GPS dropped the final stretch, so distM never
                        //       accrued it), so the filter would flag the end-snap "spurious" every tick and
                        //       pin the rider short of the line forever (raw=routeLen rejected → effRouteDist
                        //       = lastGood + odoΔ, frozen).
                        //   (B) FREEZE the gap — the race is over. Capture the final gap ONCE and re-publish
                        //       it; otherwise the ghost clock keeps advancing past a stationary finisher and
                        //       the BEHIND gap inflates indefinitely until the ride is stopped.
                        // Debounced (ROUTE_END_CONFIRM_MS) so a transient remaining glitch mid-route can't
                        // false-finish; if remaining climbs back out of the band (a pass-through destination)
                        // we un-freeze and race on.
                        if (routeStartDistM != null && ghostStartElapsedS != null && remainingM < ROUTE_END_EPS_M) {
                            val nowMs = System.currentTimeMillis()
                            if (routeEndSinceMs == 0L) routeEndSinceMs = nowMs
                            if (nowMs - routeEndSinceMs >= ROUTE_END_CONFIRM_MS) {
                                val endPos = routeLenM
                                // Captured ONCE: the rider's final result on the ghost clock. fresh=true —
                                // the destination IS the known position, not a dead-reckoned estimate.
                                val gap = finishedGap ?: GapCalculator.compute(
                                    endPos, elapsedS - ghostStartElapsedS!!, rg, fresh = true,
                                ).also { finishedGap = it }
                                GapStateHolder.update(gap)
                                val seg = rm.segments.firstOrNull { endPos in it.routeStartM..it.routeEndM }
                                publishSegment(seg, rm.segments)
                                // Keep the map ghost live: its curve saturates at routeLen, so a ghost that
                                // already finished sits at the line, while a ghost the rider BEAT keeps gliding
                                // to the line (the frozen number is the rider's result; the marker can still
                                // move). Re-anchoring each tick is harmless either way.
                                mapGhostState = if (cfg.showGhostOnMap) {
                                    MapGhostState(
                                        rg = rg,
                                        path = rm.path,
                                        ghostStartElapsedS = ghostStartElapsedS!!,
                                        anchorElapsedS = elapsedS,
                                        anchorWallMs = nowMs,
                                    )
                                } else {
                                    null
                                }
                                if (nowMs - lastDiagLogMs >= diagLogMs) {
                                    lastDiagLogMs = nowMs
                                    Timber.d(
                                        "KVP tick route: FINISHED at routeEnd=${"%.0f".format(endPos)}m — gap FROZEN " +
                                            "gapT=${"%.0f".format(gap.gapTimeS)}s gapD=${"%.0f".format(gap.gapDistanceM)}m " +
                                            "${if (gap.ahead) "AHEAD" else "BEHIND"} remaining=${"%.0f".format(remainingM)} " +
                                            "rideDist=${"%.0f".format(distM)}",
                                    )
                                }
                                return@runCatching
                            }
                        } else {
                            // Not at the finish band (or no longer): clear the debounce + any frozen result so
                            // a pass-through destination resumes normal racing.
                            routeEndSinceMs = 0L
                            finishedGap = null
                        }
                        // ODOMETRIC PLAUSIBILITY FILTER. Route progress can NEVER exceed the physical
                        // distance ridden (the route is ≤ the path travelled). On a self-intersecting loop
                        // the Karoo can snap the rider to a LATER pass through the same streets (km 9 →
                        // km 20) — or re-correct backward — making routeDist jump far beyond what the
                        // odometer says we rode. When |routeDist − lastGood| exceeds the odometer delta by
                        // more than REROUTE_JUMP_SLACK_M the position is physically impossible → reject it
                        // and project the rider's TRUE position from the last good fix by the distance
                        // actually ridden (lastGood + odoΔ). We do NOT latch the projection, so we keep
                        // tracking until the Karoo re-matches plausibly (|jump| ≈ odoΔ again). This flags
                        // ONLY the impossible; a real fast section / shortcut always has odoΔ ≥ progress,
                        // so it is never misclassified — unlike a position-delta "teleport" heuristic.
                        val lastGoodPos = lastGoodRouteDistM
                        val lastGoodOdo = distMAtLastGoodM
                        val odoDelta = if (lastGoodOdo != null) (distM - lastGoodOdo).coerceAtLeast(0.0) else null
                        val spurious = lastGoodPos != null && odoDelta != null &&
                            kotlin.math.abs(routeDist - lastGoodPos) > odoDelta + REROUTE_JUMP_SLACK_M
                        val effRouteDist = if (spurious) {
                            (lastGoodPos!! + odoDelta!!).coerceIn(0.0, routeLenM)
                        } else {
                            routeDist
                        }
                        // Route-position staleness drives the GPS-lost alert + give-up in route mode — NOT
                        // the whole-ride odometer (which can be fresh while the nav fix is wedged, or vice
                        // versa). While MOVING, if the Karoo's remaining stops changing the route fix is
                        // lost; a stationary rider's unchanged remaining is legitimate, so gate on movement.
                        val moving = speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS
                        // A long stop leaves lastDestChangeMs at its pre-stop value (a stationary rider's
                        // remaining legitimately never changes), so without this the FIRST moving tick after
                        // a ≥GPS_ALERT_S stop would read the whole stop as staleness → a spurious "GPS lost"
                        // alert + a held gap on every resume. Re-stamp on the stationary→moving edge:
                        // staleness then measures only time MOVING without a route-position change — the
                        // actual loss signal.
                        if (moving && !wasMoving) lastDestChangeMs = System.currentTimeMillis()
                        wasMoving = moving
                        val routePosStaleS = if (moving && lastDestChangeMs > 0L) {
                            ((System.currentTimeMillis() - lastDestChangeMs) / 1000.0).coerceAtLeast(0.0)
                        } else {
                            0.0
                        }
                        if (handleGpsLoss(routePosStaleS)) {
                            holdGap()
                            return@runCatching
                        }
                        // D0 — the rider's route position at the START of this route = current along-route
                        // minus the distance ridden SINCE this route began. Computed ONCE; invariant while
                        // on-route, so it back-figures any head start ridden BLIND before GPS locked and
                        // detects a deliberate mid-route start (D0 > 0).
                        if (routeStartDistM == null) {
                            routeStartDistM = (effRouteDist - (distM - rideDistAtRouteStartM)).coerceIn(0.0, routeLenM)
                        }
                        val d0 = routeStartDistM!!
                        // Anchor the ghost clock to the rider's REAL race start: at firstMoveElapsedS the
                        // ghost sits at D0, then advances on real elapsed time. ghostStartElapsedS =
                        // firstMove − rg.timeAt(D0). Until the rider actually moves, hold --- — the race
                        // hasn't started, so a wait at the line (with auto-pause off) is never a deficit and
                        // a head start ridden while moving (auto-pause naturally off) is timed from move 1.
                        val moveStart = firstMoveElapsedS
                        if (ghostStartElapsedS == null) {
                            if (moveStart == null) {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastDiagLogMs >= diagLogMs) {
                                    lastDiagLogMs = nowMs
                                    Timber.d("KVP tick route: on route at ${"%.0f".format(effRouteDist)}m, D0=${"%.0f".format(d0)} — waiting for first movement (race not started)")
                                }
                                holdGap()
                                return@runCatching
                            }
                            // The race anchor — THE event to verify the fair-start model. Logged once.
                            ghostStartElapsedS = moveStart - rg.timeAt(d0)
                            Timber.i(
                                "KVP race anchored: firstMove=${"%.0f".format(moveStart)}s D0=${"%.0f".format(d0)}m " +
                                    "ghostStart=${"%.0f".format(ghostStartElapsedS!!)}s @ routeDist=${"%.0f".format(effRouteDist)}m elapsed=${"%.0f".format(elapsedS)}s",
                            )
                            // Average-ghost contribution gate, decided ONCE at the anchor: only a lap that
                            // started at the route start (D0 ≈ 0) folds into the aggregate, so its time
                            // origin lines up with other laps'. A mid-route join leaves the target null
                            // (skip). Key + grid axis use the DECODED-POLYLINE length — the same
                            // deterministic length the match-time aggregate load keys on, so save and
                            // load can never round to different keys.
                            lapAggTarget = if (d0 <= AGG_START_TOL_M) {
                                LapAggTarget(routeKeyOf(rm.routeName, rm.path.totalM), rm.routeName, rm.path.totalM)
                            } else {
                                // Logged prominently: "my average never builds" is otherwise undiagnosable —
                                // a rider who always joins this far past the start NEVER feeds the average.
                                Timber.i(
                                    "KVP avg: this lap will NOT update the average — started ${"%.0f".format(d0)}m past " +
                                        "the route start (tolerance ${AGG_START_TOL_M.toInt()}m)",
                                )
                                null
                            }
                        }
                        // ghostElapsed = (elapsedS − firstMove) + rg.timeAt(D0): ghost at D0 at race start,
                        // then advances on real elapsed time (frozen only on pause — ELAPSED_TIME stops).
                        val ghostElapsed = elapsedS - ghostStartElapsedS!!
                        // Mark the gap as an estimate while the route position is stale (frozen while
                        // moving) past the coast window OR while we're projecting through a spurious jump;
                        // it never blanks for a brief gap.
                        val fresh = !spurious && routePosStaleS < CoastingEstimator.COAST_WINDOW_MS / 1000.0
                        // Which recorded stretch the rider is currently on — drives only the data fields'
                        // SEG/GP tag (via SegmentInfoHolder). The ghost is whole-route, not per-segment.
                        val seg = rm.segments.firstOrNull { effRouteDist in it.routeStartM..it.routeEndM }
                        // One gap against the whole-route ghost: progress = rider's route distance,
                        // clock = ghostElapsed, curve = the continuous route ghost (route-distance axis).
                        val gap = GapCalculator.compute(effRouteDist, ghostElapsed, rg, fresh)
                        GapStateHolder.update(gap)
                        // Latch the trustworthy position + its odometer ONLY on a non-spurious tick — so a
                        // rejoin/off-route tick can estimate from it, and the plausibility filter keeps
                        // comparing the next jump against the last KNOWN-GOOD baseline (not the projection).
                        if (!spurious) {
                            lastGoodRouteDistM = effRouteDist
                            distMAtLastGoodM = distM
                        }
                        // Average-ghost lap capture: one sample per ~AGG_STEP_M of forward route progress,
                        // on the SAME fair-start clock as the live gap (riderTime = elapsedS − firstMove), so
                        // the stored average lines up with how the live race is measured. Only on
                        // trustworthy ticks and only when this lap qualifies (started at the route start, so
                        // lapAggTarget != null). moveStart is non-null here — the race is anchored.
                        if (!spurious && lapAggTarget != null &&
                            effRouteDist > lastBufferedRouteDist + AGG_STEP_M
                        ) {
                            lapAggBuffer.add(doubleArrayOf(effRouteDist, elapsedS - moveStart!!))
                            lastBufferedRouteDist = effRouteDist
                        }
                        run {
                            val nowMs = System.currentTimeMillis()
                            // The rich state-snapshot line — INCLUDING the computed gap (the thing you study).
                            // Periodic (throttled to diagLogMs ~2.5 s); the precise MOMENTS — anchor, GPS
                            // lost/recover, segment in/out, mode/config change — are logged on-edge elsewhere,
                            // so this snapshot stays lean without losing the events that matter.
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d(
                                    "KVP tick route: routeDist=${"%.0f".format(effRouteDist)}${if (spurious) " (raw=${"%.0f".format(routeDist)} SPURIOUS odoΔ=${"%.0f".format(odoDelta ?: 0.0)})" else ""} D0=${"%.0f".format(d0)} " +
                                        "remaining=${"%.0f".format(remainingM)} rideDist=${"%.0f".format(distM)} " +
                                        "ghostDist=${"%.0f".format(gap.ghostProgressM)} gapT=${"%.0f".format(gap.gapTimeS)}s " +
                                        "gapD=${"%.0f".format(gap.gapDistanceM)}m ${if (gap.ahead) "AHEAD" else "BEHIND"} " +
                                        "seg=${seg?.let { "[${"%.0f".format(it.routeStartM)}..${"%.0f".format(it.routeEndM)}]" } ?: "none"} " +
                                        "elapsed=${"%.0f".format(elapsedS)} ghostElapsed=${"%.0f".format(ghostElapsed)} " +
                                        "fresh=$fresh onRoute=$lastOnRoute rejoin=$lastRejoinActive " +
                                        "speed=${speedMs?.let { "%.1f".format(it) } ?: "null"} " +
                                        "showMap=${cfg.showGhostOnMap} mapEmitter=${mapEmitter != null}",
                                )
                            }
                        }
                        // Segment field viz: show the active recorded stretch, else clear. The gap shown
                        // is still the whole-route gap (which, on a recorded stretch, races your past self).
                        publishSegment(seg, rm.segments)
                        // ④ ghost-on-map: hand the ~5 Hz map loop an immutable snapshot so it can
                        // interpolate the ghost's time-based position BETWEEN these 1 Hz ticks and make
                        // the marker glide. We anchor the ghost clock here (elapsedS + wall-clock now);
                        // the loop advances it smoothly until the next tick. Drawn regardless of GPS
                        // freshness — the ghost position is purely time-based, so a dropout/stop never
                        // makes it "unknown". null disables it (rider turned the map ghost off).
                        mapGhostState = if (cfg.showGhostOnMap) {
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
                        publishSegment(null, fireExit = false)
                        mapGhostState = null // VP mode: no map ghost (the loop hides it)
                        // Distinguish "no route navigated" from "route loaded but no recorded stretches
                        // overlap it" (rm != null, segments empty) — both race VP, but the cause differs.
                        val vpReason = if (rm != null) "route loaded, no recorded stretches" else "no route"
                        val moveStart = firstMoveElapsedS
                        if (moveStart == null) {
                            // Fair start, same as route mode: the ghost-pace race begins when the rider
                            // first rolls — NOT at ride-elapsed 0. So a cold start with no GPS / not yet
                            // moving holds --- instead of counting a growing phantom deficit. Checked
                            // BEFORE handleGpsLoss so a stationary pre-lock start doesn't fire "GPS lost".
                            GapStateHolder.clear()
                            logVp("$vpReason — waiting for first movement (race not started)", null)
                        } else if (handleGpsLoss(coast.coastingSeconds)) {
                            GapStateHolder.clear()
                            logVp("$vpReason — sustained GPS loss, blanked", null)
                        } else {
                            val g = vpGap(elapsedS - moveStart)
                            GapStateHolder.update(g)
                            logVp(vpReason, g)
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
        destJob?.cancel()
        destJob = null
        // Forget the last GPS fix / route position so the NEXT ride starts genuinely cold: D0 is
        // computed from the first on-route fix, and carrying a previous ride's last-known values would
        // let a new ride compute a stale D0 on its first tick.
        lastLat = Double.NaN
        lastLng = Double.NaN
        lastDistToDestM = Double.NaN
        lastOnRoute = false
        lastDestChangeMs = 0L
        lastRejoinActive = false
        lastRejoinDistM = Double.NaN
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
     * Returns the [TrackStore] for the CURRENT resolved tracks dir, rebuilding it whenever the dir
     * changes (e.g. all-files access was granted since onCreate, flipping internal → external). Both
     * call sites — the route-match candidate read and the ride-end save — run off the main thread, so
     * the tracksDir() file IO (mkdirs + one-time migration) is safe here. Synchronized so the two
     * threads never race on the rebuild; building two stores for the SAME dir is harmless anyway (the
     * write lock is keyed process-wide by dir path inside TrackStore).
     */
    @Synchronized
    private fun trackStore(): TrackStore {
        val dir = TrackStorage.tracksDir(applicationContext)
        if (trackStoreCache == null || trackStoreDir != dir) {
            trackStoreCache = TrackStore(dir)
            trackStoreDir = dir
            Timber.d("trackStore (re)bound to $dir")
        }
        return trackStoreCache!!
    }

    /**
     * Returns the [AggregateStore] for the CURRENT resolved aggregates dir, rebuilt whenever the dir
     * changes (all-files access granted since onCreate). Both call sites — the route-match aggregate
     * read and the ride-end EMA update — run off the main thread, so the aggregatesDir() file IO is
     * safe here.
     */
    @Synchronized
    private fun aggregateStore(): AggregateStore {
        val dir = TrackStorage.aggregatesDir(applicationContext)
        if (aggregateStoreCache == null || aggregateStoreDir != dir) {
            aggregateStoreCache = AggregateStore(dir)
            aggregateStoreDir = dir
        }
        return aggregateStoreCache!!
    }

    /**
     * Called on [RideState.Idle]: persists the just-recorded ride (if it produced >= 2 decimated
     * points) to the [TrackStore] on IO, then resets the recorder for the next ride. The id and
     * startedAtEpoch are the wall-clock epoch captured at Recording start.
     */
    private fun finishAndSaveRecording() {
        val started = recordingStartedEpoch
        val track = recorder.build(id = started.toString(), startedAtEpoch = started)
        // Snapshot + clear the average-ghost lap capture now. The tick is already stopped+joined (the
        // Idle handler does stopTickAndJoin() before this), so there is no concurrent writer; the copy
        // gives the IO update below a stable view and resets capture for the next ride.
        val lapTarget = lapAggTarget
        val lapSamples: List<DoubleArray> = synchronized(lapAggBuffer) { ArrayList(lapAggBuffer) }
        lapAggBuffer.clear()
        lapAggTarget = null
        if (track == null) {
            // Too few decimated points (a very short / stationary ride) → nothing recorded, so this ride
            // won't become a future ghost. Logged so "my ride didn't become a ghost" is diagnosable.
            Timber.i("KVP recording: not saved (too few points to build a track)")
        } else {
            // add() dedups on sourceKey (first writer wins). false means a same-key ride is already
            // stored — e.g. a FitFiles scan ingested this ride first; nothing to do but note it.
            scope.launch(Dispatchers.IO) {
                // NonCancellable: a service teardown at ride-end must not abort the save/tidy half-way (a
                // cancelled write between the track file and the index would leave an un-indexed track).
                // Capture the store ONCE so add() and tidyGroup() can't target different dirs if all-files
                // access is granted mid-coroutine.
                withContext(NonCancellable) {
                    val store = trackStore()
                    if (store.add(track)) {
                        Timber.i("KVP recording: saved track ${track.id} (${track.points.size} pts) → ${trackStoreDir}")
                        // Auto-clean: archive near-duplicate rides of this route so BEST/LAST stay solid.
                        if (activeConfig.value.autoTidy) {
                            val archived = runCatching { store.tidyGroup(track) }.getOrElse { e ->
                                Timber.w(e, "KVP tidy: tidyGroup failed for ${track.id}"); 0
                            }
                            if (archived > 0) Timber.i("KVP tidy: archived $archived near-duplicate(s) of ${track.id}")
                        }
                    } else {
                        Timber.i("KVP recording: track ${track.id} skipped (sourceKey ${track.sourceKey} already stored)")
                    }
                }
            }
        }
        // Fold this lap into the per-route average-ghost aggregate, INDEPENDENT of the track save (so it
        // survives the track-library prune). Only a qualifying lap — key set at the anchor because the
        // rider started at the route start — with enough samples updates it.
        if (lapTarget != null && lapSamples.size >= 2) {
            scope.launch(Dispatchers.IO) {
                withContext(NonCancellable) {
                    runCatching {
                        val aggStore = aggregateStore()
                        val updated = updateAggregate(
                            aggStore.load(lapTarget.key), lapTarget.key, lapTarget.name, lapTarget.lenM, lapSamples,
                        )
                        aggStore.save(updated)
                        Timber.i("KVP avg: updated aggregate ${lapTarget.key} (${lapSamples.size} samples)")
                    }.onFailure { Timber.w(it, "KVP avg: aggregate update failed for ${lapTarget.key}") }
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
