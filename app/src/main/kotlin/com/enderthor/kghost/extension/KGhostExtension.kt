package com.enderthor.kghost.extension

import android.os.SystemClock
import com.enderthor.kghost.BuildConfig
import com.enderthor.kghost.FileLogTree
import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.datatype.GapGraphicDataType
import com.enderthor.kghost.datatype.GapNumericDataType
import com.enderthor.kghost.datatype.GapStreamDataType
import com.enderthor.kghost.engine.CadenceProbe
import com.enderthor.kghost.engine.CoastQuality
import com.enderthor.kghost.engine.CoastingEstimator
import com.enderthor.kghost.engine.verdictAllowed
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
import com.enderthor.kghost.engine.CorridorSeeder
import com.enderthor.kghost.engine.GhostCheckpoint
import com.enderthor.kghost.engine.GhostIntegrator
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.engine.PacePatch
import com.enderthor.kghost.engine.PerRouteAggregate
import com.enderthor.kghost.engine.SegmentInfo
import com.enderthor.kghost.engine.shouldReseed
import com.enderthor.kghost.geo.AggregateStore
import com.enderthor.kghost.geo.atomicWriteText
import com.enderthor.kghost.geo.BBox
import com.enderthor.kghost.geo.GradePaceStore
import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.PolylinePath
import com.enderthor.kghost.geo.TrackRecorder
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.geo.routeKeyOf
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.managers.PermAlertState
import com.enderthor.kghost.managers.PermissionAlertSchedule
import com.enderthor.kghost.managers.StoragePermission
import com.enderthor.kghost.map.GhostMapPresenter
import com.enderthor.kghost.map.MapGlide
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** How often the stream-cadence diagnostic prints mid-ride. Long: the point is one summary
         *  per ride, the periodic copy only exists so a power-off does not lose everything. */
        private const val CADENCE_LOG_MS = 600_000L

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
         * Route projector (GPS → polyline). The Karoo's own map-match (`routeLen − remaining`) can lock
         * onto the WRONG pass of a self-overlapping route — especially when the route is loaded mid-ride —
         * placing the rider hundreds of metres off (confirmed in the field: perp≈0 yet routeDist ~250 m
         * beyond the GPS). So we PREFER the rider's GPS projected onto the decoded route polyline:
         *  - [GPS_FIX_FRESH_MS]: ignore the GPS fix (fall back to the Karoo value) if older than this.
         *  - [ROUTE_PROJ_BACK_M]/[ROUTE_PROJ_FWD_M]: window the projection around the last-good position so
         *    it tracks the CURRENT pass and can't flip to another pass of a loop (asymmetric — the rider
         *    moves forward between fixes).
         *  - [ROUTE_PROJ_FWD_MAX_M]: hard cap on how far forward the window reaches. The forward span grows
         *    with metres ridden since the last good fix (to bracket a rejoin after a detour), but is capped
         *    here so a long detour / GPS-out stretch can't widen it until it spans the NEXT pass of a
         *    self-overlapping route (which would re-admit the wrong-pass snap the windowing prevents).
         *  - [ROUTE_PROJ_MAX_PERP_M]: if the nearest point is farther than this the rider is off the line
         *    (genuinely off-route) → don't trust the projection, fall back to the Karoo value.
         */
        private const val GPS_FIX_FRESH_MS = 5_000L
        private const val ROUTE_PROJ_BACK_M = 120.0
        private const val ROUTE_PROJ_FWD_M = 300.0
        private const val ROUTE_PROJ_FWD_MAX_M = 400.0
        private const val ROUTE_PROJ_MAX_PERP_M = 40.0

        /** Stable SegmentInfo published while the B2 ghost is racing recorded history (SEG tag). The gap
         *  field only reads whether [SegmentInfoHolder] is non-null (SEG vs GP) — the label/bounds are
         *  unused — so one shared instance avoids per-tick churn (the holder dedups on its fields). */
        private val B2_ON_HISTORY = SegmentInfo(0.0, 0.0, "SEG")

        /** B2 ghost checkpoint filename (under filesDir) + how often to persist it + the odometer
         *  proximity that lets a resumed ride (continuous distance) restore even when the process died
         *  and minted a fresh recordingStartedEpoch (a genuinely new ride starts near 0 → rejected). */
        private const val GHOST_CHECKPOINT_FILE = "ghost-checkpoint.json"
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        // Odometer proximity that lets a power-off resume (fresh epoch, continuous distance) restore. Tight
        // (~a few ticks of riding between the last checkpoint and the cut) so a genuinely NEW ride that
        // happens to start near an old interrupted ride's position does NOT inherit its lead.
        private const val CHECKPOINT_RESUME_MARGIN_M = 300.0
        // A checkpoint older than this (wall-clock) is not a resume candidate — bounds cross-ride false hits.
        private const val CHECKPOINT_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        // Heading tolerance for the marker anchor's pass-disambiguation (loop bootstrap + shortcut recovery).
        private const val ROUTE_HEADING_TOL_DEG = 45.0
        // A global marker re-acquire is AMBIGUOUS (→ hold, don't guess) when the qualifying candidates split
        // into ≥2 clusters — an along-route GAP between consecutive candidates > GAP_M (adjacent-segment gaps
        // of one pass are ≤ ~40 m; a distinct pass is the whole route apart) — or the cluster SPANs > SPAN_M
        // (backstop). Gap-based, not raw-span, so a long contiguous curl/roundabout is still accepted while a
        // second pass even a few hundred m away is refused; residual worst-case icon error is bounded by SPAN.
        private const val ROUTE_AMBIGUITY_GAP_M = 75.0
        private const val ROUTE_AMBIGUITY_SPAN_M = 200.0
        // Finish-freeze corroboration: the rider's ODOMETER must be within this of the route length before a
        // near-end R can freeze the gap — blocks a spurious near-end R lock from finish-freezing mid-route
        // (where the odometer is far short of the end). Generous so a real finish after a short end-shortcut
        // still qualifies.
        private const val FINISH_ODO_MARGIN_M = 3_000.0

        /**
         * Recovery from a poisoned rail. The GPS window is centred on `lastGoodRouteDistM`; if a bad value
         * ever latched the rail too far AHEAD, the rider's true (lower) point sits below the back window and
         * the windowed projection returns empty forever → the Karoo fallback (floored) re-pins it. After
         * this many consecutive MOVING ticks with an empty window, do ONE global perp-gated re-acquire; the
         * GPS path is un-floored, so an on-line global point below the rail pulls it back to truth.
         */
        private const val RECOVER_EMPTY_TICKS = 5

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
         * When the position is GPS-derived, also require the rider to be within this many metres of the
         * route end (by GPS) before declaring FINISH. The Karoo's remaining-to-destination can read ≈0 on
         * a wrong-pass snap (e.g. the outbound leg of an out-and-back passing near the end road) while the
         * GPS says mid-route; without this cross-check that would falsely FREEZE the gap mid-ride.
         */
        private const val ROUTE_END_NEAR_M = 50.0

        /**
         * remaining < [ROUTE_END_EPS_M] must PERSIST this long before it counts as a genuine finish, so a
         * transient DISTANCE_TO_DESTINATION glitch mid-route can't false-freeze the race. Pure debounce —
         * the frozen gap value is captured live once confirmed.
         */
        private const val ROUTE_END_CONFIRM_MS = 4_000L

        /**
         * Uncorroborated-finish give-up: if the Karoo's remaining stays < [ROUTE_END_EPS_M] this long
         * with NEITHER GPS nor odometer confirming "near the end" (both dropped the final stretch), trust
         * the Karoo end-snap anyway and freeze the gap. Much longer than [ROUTE_END_CONFIRM_MS] so a
         * transient/wrong-pass remaining≈0 (which a still-moving rider clears within seconds) can't reach
         * it; only a rider genuinely stopped at the line holds remaining≈0 this long.
         */
        private const val ROUTE_END_GIVEUP_MS = 20_000L

        /** How often the diagnostic-log uploader sends the new tail of the current ride's log. Kept
         *  short so the tail streams out DURING the ride and the ride-end flush stays tiny — a long
         *  interval piles the whole last window onto the finish, exactly when the other extensions
         *  (KPower/KSafe) are also uploading, and the sends contend for the uplink. */
        private const val LOG_SEND_INTERVAL_MS = 5 * 60_000L

        /**
         * Max RAW bytes per uploaded chunk (the tail is cut on raw bytes; [LogReporter] gzips the chunk
         * afterwards, so `sentLogBytes` accounting is unaffected by compression).
         *
         * The Karoo `httpRequest` body crosses the host Binder transaction at ~80 KB (KSafe's empirical
         * CALIBRATION_MAX_CHUNK_BYTES = 72 000), so each POST must stay well under it — a bigger body
         * fails with TransactionTooLargeException and the WHOLE feature silently never delivers.
         *
         * [LogReporter] gzips the chunk before POSTing, so the ceiling applies to the COMPRESSED body —
         * but this value is deliberately NOT sized on a compression ratio. gzip's worst case is
         * INCOMPRESSIBLE input, where the output is the input plus ~0.03% and an 18-byte header, so
         * 60 000 raw is the largest slice that fits the ceiling UNCONDITIONALLY — exactly as it did when
         * the body was plain text. Real logs measure 6.5-7.6:1 (worst of 38 slices over 9.1 MB of real
         * ride logs: 6.55:1), so in practice the body is now ~9 KB instead of ~60 KB: the bytes-on-the-
         * wire win from gzip is kept in full, only the fewer-POSTs win is given up.
         *
         * ponytail: sized for gzip's WORST case rather than its measured case, so no runtime check is
         * needed. Raising it (fewer POSTs = fewer radio wake-ups, which cost more than the bytes) means
         * ENFORCING the limit — measure the assembled body and shrink + recompress the raw slice until
         * it fits — because the failure mode is not one dropped chunk: an oversized body throws
         * TransactionTooLargeException, `sentLogBytes` never advances, and every later periodic and
         * ride-end drain retries the same unsendable bytes forever, silently killing ALL log delivery.
         */
        private const val LOG_CHUNK_BYTES = 60_000

        /** Chunks per PERIODIC cycle, so one window can't monopolise the link draining a huge backlog. */
        private const val LOG_PERIODIC_MAX_CHUNKS = 6

        /** Retry cadence while the uploader is BEHIND (a send failed/offline, or the backlog didn't fully
         *  drain this cycle). Much shorter than [LOG_SEND_INTERVAL_MS] so a no-coverage window's backlog
         *  is cleared as soon as the Companion is back in range — instead of sitting until the next slow
         *  cycle and dumping onto the ride-end flush. Relaxes back to the normal interval once caught up. */
        private const val LOG_RETRY_INTERVAL_MS = 60_000L

        /** Consecutive SEND_FAILED cycles (reachable, but the upload failed with no progress) after which
         *  the loop drops back to the normal cadence. Caps a doomed/rejected send (bad token, sustained
         *  429) at ~this many expensive 60s probe+POST retries instead of hammering all ride. Offline
         *  (RETRY) never trips this, so genuine coverage loss keeps its fast catch-up. */
        private const val LOG_MAX_STALL_RETRIES = 5

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

    /** An immutable GPS fix snapshot (lat, lng, the MONOTONIC ms — SystemClock.elapsedRealtime, for
     *  freshness only, never an epoch — it was taken at, and the rider's heading
     *  in degrees [0,360) or NaN if the fix carried none — used to disambiguate the D0 bootstrap pass). */
    private data class GpsFix(val lat: Double, val lng: Double, val ms: Long, val headingDeg: Double)
    // Latest TRUSTED GPS fix from the location stream, as ONE immutable object. Read by the recorder
    // and the route projector on the tick + the map loop. A single @Volatile reference means a reader
    // always gets a COHERENT (lat,lng,ms) triple — three separate volatiles could be read torn (lat from
    // fix N, lng from fix N−1). null until the first finite trusted fix.
    @Volatile
    private var lastFix: GpsFix? = null

    // ── Stream-cadence probes (DIAGNOSTICS ONLY, no behaviour) ───────────────────────────────────
    // Answer, from ONE ride, the two questions the logs could not: (a) how fast does LOCATION really
    // arrive, and how old is the fix when the tick actually reads it — the quantity GPS_FIX_FRESH_MS
    // is compared against, never yet measured; (b) how fast does the raw DISTANCE stream arrive
    // BEFORE combine()+sample() destroys its timing, and how often does the sampled value repeat on
    // a tick. (b) is the suspected source of the 578 one-tick "gps-loss episodes" a field ride
    // logged: Adv2CoastPipelineTest's LOCK E already proves a DISTANCE stream slower than the tick
    // is classified COASTING, withholding the historical verdict on metres that are perfectly real.
    // Reset per ride in startTick(); rendered by [logCadence].
    private val locProbe = CadenceProbe("loc")
    private val distProbe = CadenceProbe("distRaw")
    private val fixAgeProbe = CadenceProbe("fixAge@tick")
    private var locTrustedCount = 0L
    // Emissions carrying the SAME lat/lng as the previous one. The GNSS source was measured at a
    // steady 2.00 Hz (dumpsys location, device idle), yet the throttled ride log showed 1682/1683
    // intervals at a flat 5 s — a shape a 500 ms source should not produce. The suspect is karoo-ext
    // re-emitting an unchanged fix, which matters far beyond cadence: line ~1805 re-stamps lastFix
    // with a FRESH timestamp on every trusted emission without comparing coordinates, so a repeated
    // fix keeps `fixFresh` true forever and the age guard can never fire during a real dropout.
    // This counter is what distinguishes "fast stream" from "repeating stream".
    private var locRepeatCount = 0L
    // Repeats seen while the rider was MOVING. The unconditional count above cannot answer the question
    // the probe exists for: a stationary rider produces identical coordinates by definition, so a single
    // cafe stop (31 min at ~2 Hz is ~3700 emissions) swamps the signal and a high total proves nothing.
    // Only a repeat while MOVING can distinguish "karoo-ext re-emitted a cached fix" from "the bike is
    // parked". Both are printed so the stop contribution stays visible.
    private var locRepeatMovingCount = 0L
    // Set by the tick from the SPEED stream, read by the location collector on its own coroutine.
    @Volatile private var probeRiderMoving = false
    private var lastLocLat = Double.NaN
    private var lastLocLng = Double.NaN
    private var tickCount = 0L
    private var distRepeatTicks = 0L
    private var lastCadenceLogMs = 0L
    private var lastTickDistM = Double.NaN

    // Live gradient (%) + the MONOTONIC ms it arrived, the key into the historical pace-vs-gradient
    // model on ground with no local history. Its own collector rather than a fourth arm of the tick's
    // combine(): the tick must not stall waiting for a gradient sample, and a missing gradient is a
    // legitimate state (null -> the model is simply not asked). Written off Main, read on the tick.
    @Volatile private var lastGradePct: Double? = null
    @Volatile private var lastGradeMs: Long = 0L
    private var gradeJob: Job? = null
    // One-shot unit probe: ELEVATION_GRADE is documented as "Grade %" but a ratio (0.06) and a percent
    // (6.0) are indistinguishable in code and differ by 100x. Log the first raw sample so the unit can be
    // confirmed from a field log; normalisation, if any, belongs in the collector below and nowhere else.
    @Volatile private var gradeUnitLogged = false

    // The Karoo's own remaining-distance-to-destination (m) on the navigated route, and whether the
    // rider is on that route. Source of the authoritative route position (routeDist = routeLen −
    // remaining) used by the ② route tick, replacing the local GPS projection. Written by [destJob]
    // (a different dispatcher thread than the tick), so @Volatile. NaN/false until the first emission
    // or while off route / without a fix — the tick then holds ---.
    @Volatile
    private var lastDistToDestM: Double = Double.NaN
    @Volatile
    private var lastOnRoute: Boolean = false
    // Monotonic (SystemClock.elapsedRealtime ms) of the last time the route-remaining VALUE actually
    // changed (not every emission). Interval-only — compare ONLY against elapsedRealtime, never an epoch.
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

    // ── Per-ride/route ANCHOR state — instance fields, NOT tick locals ──────────────────────────────
    // These USED to be locals inside startTick(). A mid-ride host RECONNECT cancels the tick (onConnected)
    // and the replayed Recording relaunches startTick() — which, with locals, started the whole anchor
    // from scratch: projectorRoute=null → !sameRoute → D0/firstMove/lastGood/lapAgg all reset, the race
    // snapped to 0 and the lap was disqualified. As instance fields they SURVIVE the tick relaunch:
    // routeMode is preserved across reconnect, so projectorRoute === rm.path → the route-change reset is
    // skipped entirely and the race continues. They are reset only on a genuine new ride (resetRideAnchor,
    // from the Idle ride-end handler and stopTick) and on a genuine route change (the in-tick block).
    // @Volatile because the relaunched tick coroutine may run on a different pool thread than the old one.
    // (Named exactly as the former locals so the tick body needs no edits; reset by resetRideAnchor().)
    @Volatile private var projectorRoute: PolylinePath? = null
    @Volatile private var projectorPolyline: String? = null
    // Ride-elapsed (s) at first movement — the B2 race clock origin. Nulled at ride end/stop; PRESERVED
    // across a reroute (the route-agnostic integrator carries the lead, so the clock must stay continuous).
    @Volatile private var firstMoveElapsedS: Double? = null
    // ① Ghost-Pace clock origin — set ONCE per ride, NEVER re-nulled on a route change.
    @Volatile private var vpFirstMoveElapsedS: Double? = null
    // Consecutive MOVING ticks off the windowed line — arms the unambiguous global marker re-acquire.
    @Volatile private var emptyWindowTicks: Int = 0
    // The rider's last TRUSTWORTHY route distance (the monotone rail) + the odometer captured with it.
    @Volatile private var lastGoodRouteDistM: Double? = null
    @Volatile private var distMAtLastGoodM: Double? = null
    // One-shot "GPS lost" alert guard (re-armed when GPS recovers).
    @Volatile private var gpsAlertFired: Boolean = false
    // Previous tick's moving state (route mode) — drives the stationary→moving re-stamp edge.
    @Volatile private var wasMoving: Boolean = false
    // First-fix D0 confirmation candidate (position + odometer at that position).
    @Volatile private var d0CandPos: Double? = null
    @Volatile private var d0CandOdo: Double? = null
    // Start-distance of the recorded stretch the rider was on last tick — edge-triggers the segment alert.
    @Volatile private var prevSegStartM: Double? = null
    // Option B moving-time race clock: previous tick's ELAPSED_TIME (to hold raceElapsed while stopped so
    // the gap freezes instead of drifting) + whether the rider has COMPLETED the loaded route once (to hide
    // the one-lap map marker on a 2nd lap, where R wraps to the start; the NUMBER keeps racing).
    @Volatile private var prevTickElapsedS: Double? = null
    @Volatile private var crossedFinish: Boolean = false
    // Latched once the rider crosses the finish AND wraps back into the route's first half (= genuinely on a
    // 2nd lap). Stays true for the rest of the ride so the one-lap marker stays HIDDEN across the WHOLE 2nd+
    // lap (not just its first half). Reset at ride end / route change.
    @Volatile private var lap2Started: Boolean = false
    // B2 path-following ghost race engine (accrues historical time per ridden metre on the ACTUAL path).
    // Built lazily at first movement; nulled at ride end/stop. KEPT across a reroute — it is route-agnostic,
    // so the accrued lead carries to the new polyline (only the route-specific marker anchor re-bootstraps).
    @Volatile private var integrator: GhostIntegrator? = null
    // The last odometer distance handed to the integrator this ride — persisted in the checkpoint so a
    // mid-ride power-off resumes with the accrued lead (GhostIntegrator keeps lastRiderDist private).
    @Volatile private var integLastRiderDist: Double = 0.0
    // The pick the integrator is CURRENTLY racing — re-stamped every tick, because a pick-only repick
    // keeps the integrator (the pace lookup already reads eff.ghostPick live, so the number follows the
    // new pick immediately) and the checkpoint must record the pick the lead was actually earned under.
    // Consequence: cp.pick can no longer disagree with the live pick, so the paramMatch term below is
    // vestigial in the same way vpTimePerM is — kept for the persisted GhostCheckpoint schema. integVpTpm is
    // retained ONLY to satisfy GhostIntegrator's constructor and the GhostCheckpoint schema (persisted-format
    // compatibility) — since the neutral-fill change it no longer influences the accrued gap and no longer
    // gates the resume (see the paramMatch comment below). Do not confuse this with RouteGhost's map-marker
    // VP-fill pace (eff.targetSpeedMs), which IS still live — see the comment where RouteGhost.build is called.
    @Volatile private var integPick: GhostPick? = null
    @Volatile private var integVpTpm: Double = 0.0
    // Monotonic (elapsedRealtime ms) of the last checkpoint write — throttles the ~5 s periodic persist.
    @Volatile private var lastCheckpointMs: Long = 0L
    // Latest checkpoint SNAPSHOT, built on the Main tick (so the IO writer never reads the integrator's
    // non-volatile scalars cross-thread) and serialized by [checkpointMutex] (so the ~5 s tick write and
    // the Paused write can't clobber the shared temp file).
    @Volatile private var pendingCheckpoint: GhostCheckpoint? = null
    private val checkpointMutex = Mutex()
    // Serializes maybeAlertMissingPermission()'s load-decide-dispatch-write across concurrent
    // RideState.Recording emissions (e.g. a host reconnect storm firing it twice in quick
    // succession), so the second call's config read always observes the first call's write instead
    // of racing it — see maybeAlertMissingPermission() for the full reasoning.
    private val permAlertMutex = Mutex()
    // The last ghost route distance drawn while R was RELIABLE. When the rider goes off the route (R
    // unreliable) the marker HOLDS this frozen position instead of hiding — so the icon never disappears
    // while a route is loaded (incl. a Karoo reroute/deviation), it just pauses where you left the line and
    // snaps to the rejoin. Null until the first reliable draw (then the marker is simply not shown yet).
    @Volatile private var lastReliableGhostRouteDist: Double? = null

    /** Resets all per-ride/route anchor state to its cold-start values. Called at a genuine ride END
     *  (the Idle handler) and in stopTick — NOT on a host reconnect, where the anchor must survive so the
     *  race continues across the tick relaunch. */
    private fun resetRideAnchor() {
        projectorRoute = null
        projectorPolyline = null
        firstMoveElapsedS = null
        vpFirstMoveElapsedS = null
        emptyWindowTicks = 0
        lastGoodRouteDistM = null
        distMAtLastGoodM = null
        gpsAlertFired = false
        wasMoving = false
        d0CandPos = null
        d0CandOdo = null
        prevSegStartM = null
        prevTickElapsedS = null
        crossedFinish = false
        lap2Started = false
        integrator = null
        integLastRiderDist = 0.0
        integPick = null
        integVpTpm = 0.0
        lastCheckpointMs = 0L
        pendingCheckpoint = null
        lastReliableGhostRouteDist = null
    }

    // ---- B2 ghost checkpoint (scalar resume state) ---------------------------------------------
    // A tiny file persisted periodically so a mid-ride power-off / crash / service kill resumes the
    // race WITH the accrued lead instead of restarting from a 0 gap. Deleted at a clean ride end, so a
    // file present on the next start means the previous ride was interrupted → a candidate to resume.
    private val ghostCheckpointFile: File get() = File(applicationContext.filesDir, GHOST_CHECKPOINT_FILE)

    /** Reads the persisted checkpoint, or null if absent/corrupt (never throws). */
    private fun loadGhostCheckpoint(): GhostCheckpoint? = runCatching {
        val f = ghostCheckpointFile
        if (!f.exists()) null
        else jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), f.readText())
    }.getOrNull()

    /** Flushes the latest Main-built [pendingCheckpoint] snapshot atomically. Suspends; call on IO. The
     *  mutex serializes the ~5 s tick write and the Paused write so they can't clobber the shared temp.
     *  fsync off — a lost/torn checkpoint just means resume-from-zero (safe), not corruption. */
    private suspend fun flushGhostCheckpoint() {
        val cp = pendingCheckpoint ?: return
        checkpointMutex.withLock {
            runCatching {
                atomicWriteText(ghostCheckpointFile, jsonForStorage.encodeToString(GhostCheckpoint.serializer(), cp), fsync = false)
            }.onFailure { Timber.w(it, "ghost checkpoint write failed") }
        }
    }

    /** Deletes the checkpoint at a clean ride end so the next ride starts fresh. */
    private fun deleteGhostCheckpoint() {
        runCatching { ghostCheckpointFile.delete() }
    }

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
    internal data class RouteMode(
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
        /**
         * B2 path-following pace map for this route's area (2D `(cell,bearing)→pace`), built from the
         * overlapping history at match time and published ATOMICALLY with the route so the tick never
         * pairs a new route with a stale patch. Null when no history overlaps → the integrator neutral-fills
         * the whole route. Not persisted; rebuilt on every route load.
         */
        val pacePatch: PacePatch?,
        /**
         * Global, route-independent "my pace at gradient X" model (tier 2 of the pace lookup), LOADED
         * (never rebuilt) at route load and carried here so the tick reads a consistent (patch, model)
         * pair. Null when no import has run since the feature landed -> the fill stays neutral, exactly
         * as before.
         */
        val gradePace: GradePace?,
        val aggregate: PerRouteAggregate?,
    ) {
        fun withPick(pick: GhostPick, fillSpeedMs: Double): RouteMode {
            val aggregate = aggregate ?: return this
            val segments = aggregate.toLiveSegments(pick)
            return copy(segments = segments, routeGhost = RouteGhost.build(path.totalM, segments, fillSpeedMs))
        }
    }

    /**
     * Immutable snapshot the 1 Hz tick hands to the ~5 Hz map loop. [ghostDistM] is the EXACT ghost
     * route distance the tick published into the gap field this tick (so map and field are driven by
     * the same value); the loop glides BETWEEN consecutive snapshots via [MapGlide] (lag, never lead)
     * keyed on [monoMs]. No clock/curve here any more — the loop interpolates published values, it does
     * not re-derive the ghost, so it can never run ahead of the field.
     */
    private data class MapGhostState(
        val ghostDistM: Double,
        val path: PolylinePath,
        val monoMs: Long,   // SystemClock.elapsedRealtime — interval-only (glide), never an epoch
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
    private var matchGeneration = 0L
    // @Volatile like matchJob, not because rematchOnSettingsChange needs it (that is Main-only) but
    // because clearRouteMode cancels it and clearRouteMode can run on Default (see its callers).
    @Volatile
    private var repickJob: Job? = null
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
    // BYTES of the current ride's log already uploaded, so each cycle reads + sends only the new tail
    // (a byte offset, NOT chars: we seek into the file and never re-read the multi-MB prefix — at ride
    // end that re-read would spike CPU/IO exactly when the Karoo is saving + uploading the activity).
    // Reset to 0 (with [logChunkSeq]) on each new ride file; [sentLogFilePath] guards the file identity.
    @Volatile
    private var sentLogBytes: Long = 0L
    @Volatile
    private var sentLogFilePath: String? = null
    @Volatile
    private var logChunkSeq: Int = 0
    // Serializes the periodic drain and the ride-end drain (both advance sentLogBytes → could skip or
    // duplicate a chunk if interleaved). A MUTEX, not a boolean skip-guard: the ride-end flush must WAIT
    // for an in-flight periodic and then run, never be silently skipped (and left un-retried once the
    // tick — and so the periodic loop — has stopped).
    private val logSendMutex = Mutex()

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
    // Monotonic (elapsedRealtime ms) of the last ShowSymbols we emitted. Drives the GHOST_HEARTBEAT_MS re-assert so a
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
    // True once a ride has actually entered Recording, until the matching Idle handles its end. Gates the
    // ride-end log upload so it fires ONLY after a real ride: streamRide() replays the current state on
    // subscribe and onConnected re-subscribes on every host rebind, so a no-ride device emits Idle
    // repeatedly — without this guard each of those would upload the between-ride log. Set in Recording,
    // checked-and-cleared in Idle.
    @Volatile
    private var wasRecording = false
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
        // Same dead-binding reasoning for the gradient collector: a forgotten collector freezes on the old
        // binding for the rest of the ride. Null the value too, so a stale gradient can't survive the
        // reconnect and describe a hill the rider left minutes ago.
        gradeJob?.cancel()
        gradeJob = null
        lastGradePct = null
        lastGradeMs = 0L
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
                    wasRecording = true
                    // No map re-stamp needed on resume: the loop glides BETWEEN published ghost distances
                    // (it never extrapolates on wall-clock), so a stale paused snapshot can't lurch the
                    // marker forward — the first post-resume tick simply glides it to the new value.
                    startTick()
                    maybeAlertMissingPermission()
                }
                is RideState.Paused -> {
                    // The clock is tied to ELAPSED_TIME, which the ride app already pauses, so the tick
                    // freezes by receiving no emissions. Flag the pause so the map loop holds the ghost
                    // in place rather than extrapolating it forward by wall-clock.
                    ridePaused = true
                    // Flush the latest checkpoint snapshot on entering a pause: the tick stops emitting while
                    // paused, so this persists the most recent Main-built state (≤ CHECKPOINT_INTERVAL_MS old)
                    // before a possible power-off during a long café stop. No-op if nothing has been built yet.
                    scope.launch(Dispatchers.IO) { flushGhostCheckpoint() }
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
                    // Genuine ride END (Idle) — reset the per-ride/route anchor so the NEXT ride starts
                    // cold. NOT done on a reconnect (which never emits Idle while recording), so the anchor
                    // survives a mid-ride rebind. finishAndSaveRecording() already consumed the lap buffer.
                    resetRideAnchor()
                    // Clean ride end → drop the checkpoint so the NEXT ride starts fresh (a file present on
                    // start means the previous ride was interrupted, never reached Idle → a resume candidate).
                    deleteGhostCheckpoint()
                    GapStateHolder.clear()
                    SegmentInfoHolder.clear()
                    publishGhostMarker(null)
                    // Upload the ride's final log tail (GPS redacted) before the file rotates next ride,
                    // but ONLY if a ride was actually recording. streamRide() replays the current state on
                    // subscribe and onConnected re-subscribes on every host rebind, so an idle device emits
                    // Idle repeatedly — without this guard each emission would re-upload the between-ride log.
                    // maxChunks high so the remaining backlog drains; periodic normally kept it small.
                    if (wasRecording) {
                        wasRecording = false
                        scope.launch { runCatching { sendLogTail("ride-end", maxChunks = 200) } }
                    }
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
            var delayMs = LOG_SEND_INTERVAL_MS
            var sendFails = 0 // consecutive SEND_FAILED cycles → backoff
            while (true) {
                delay(delayMs)
                if (!FileLogTree.enabled || tickJob?.isActive != true) {
                    delayMs = LOG_SEND_INTERVAL_MS // idle / no active ride → relax to the normal cadence
                    sendFails = 0
                    continue
                }
                delayMs = when (sendLogTail("periodic", LOG_PERIODIC_MAX_CHUNKS)) {
                    // Nothing left → relax. RETRY (offline / made progress) → retry SOON to clear the
                    // backlog the moment the link returns; both reset the failure streak.
                    LogSendOutcome.CAUGHT_UP -> { sendFails = 0; LOG_SEND_INTERVAL_MS }
                    LogSendOutcome.RETRY -> { sendFails = 0; LOG_RETRY_INTERVAL_MS }
                    // Reachable but the send keeps failing with no progress → retry fast a few times,
                    // then fall back to the normal cadence so a doomed/rejected upload can't hammer.
                    LogSendOutcome.SEND_FAILED ->
                        if (++sendFails >= LOG_MAX_STALL_RETRIES) LOG_SEND_INTERVAL_MS else LOG_RETRY_INTERVAL_MS
                }
            }
        }
    }

    /** Outcome of one [sendLogTail] cycle, driving the [startLogSendLoop] retry cadence + backoff.
     *  - [CAUGHT_UP]: nothing left to send (or can't) → relax to the normal interval.
     *  - [RETRY]: more to send and the link looks usable — OFFLINE (cheap probe bail, catch coverage
     *    returning) OR a partial drain that made progress → retry SOON; does NOT count toward backoff,
     *    so a real no-coverage window keeps draining fast the moment it clears.
     *  - [SEND_FAILED]: reachable but the upload itself failed with ZERO progress (a doomed/rejected
     *    send — bad token, 429…) → retry soon, but back off after [LOG_MAX_STALL_RETRIES] so it can't
     *    hold an expensive 60s probe+POST loop for the whole ride. */
    private enum class LogSendOutcome { CAUGHT_UP, RETRY, SEND_FAILED }

    /**
     * Uploads the part of the current ride's log not yet sent ([sentLogBytes]) to the developer's
     * Telegram via [LogReporter] (which redacts every GPS coordinate first, so no location data leaves
     * the device), in chunks of [LOG_CHUNK_BYTES] — each `httpRequest` body MUST stay under the host
     * Binder transaction limit (~80 KB) or the whole POST fails. Sends at most [maxChunks] per call.
     *
     * Reads ONLY the not-yet-sent tail via a BYTE offset + [java.io.RandomAccessFile.seek], never the
     * whole file: at ride end the log is multi-MB and the periodic drains already sent the bulk, so a
     * `readText()` of the whole thing would be a CPU/IO spike landing exactly when the Karoo is saving
     * + uploading the activity (same Companion link, same CPU) — the rider feels it as "finishing the
     * ride got slower". Chunks are cut on a `\n` boundary and the offset advances by the chunk's
     * ENCODED byte length, so the next seek never splits a UTF-8 sequence.
     *
     * Runs entirely on [Dispatchers.IO] (the owning [scope] is Main — file IO + multipart build must
     * never touch Main → ANR). Advances [sentLogBytes] only per SUCCESSFUL chunk, so a failure just
     * retries from the same point next cycle; an in-flight guard stops the periodic and ride-end drains
     * overlapping, and [sentLogFilePath] resets the offset when the ride file changed under us.
     */
    private suspend fun sendLogTail(prefix: String, maxChunks: Int): LogSendOutcome = withContext(Dispatchers.IO) {
        if (!FileLogTree.enabled || !::karooSystem.isInitialized) return@withContext LogSendOutcome.CAUGHT_UP
        val file = FileLogTree.currentLogFile() ?: return@withContext LogSendOutcome.CAUGHT_UP
        // A concurrent send (periodic vs ride-end) WAITS here instead of being skipped — see logSendMutex.
        logSendMutex.withLock {
            // A new (or rotated) ride file → start its offset/sequence from the top.
            if (file.path != sentLogFilePath) {
                sentLogFilePath = file.path
                sentLogBytes = 0L
                logChunkSeq = 0
            }
            // Make sure the last second of buffered lines is on disk before we read.
            FileLogTree.requestFlush()
            delay(400)
            // Snapshot the length now; anything appended after is next call's tail.
            val len = runCatching { file.length() }.getOrNull() ?: return@withContext LogSendOutcome.RETRY
            if (len <= sentLogBytes) return@withContext LogSendOutcome.CAUGHT_UP
            val id = installId ?: runCatching { configManager.getOrCreateInstallId() }.getOrNull()
                ?.also { installId = it } ?: return@withContext LogSendOutcome.CAUGHT_UP
            // Coverage check BEFORE the heavy POSTs: out of range → bail in a few seconds (the probe
            // timeout) and let the caller retry on the short cadence, instead of the first doomed chunk
            // burning the full 60s send timeout. A live link falls straight through. Offline is RETRY
            // (cheap, keeps catching the link's return), NOT a SEND_FAILED (which would trip the backoff).
            if (!LogReporter.isReachable(karooSystem)) return@withContext LogSendOutcome.RETRY
            val sid = FileLogTree.sessionId
            val ver = BuildConfig.VERSION_NAME
            var sent = 0
            while (sentLogBytes < len && sent < maxChunks) {
                val want = minOf(LOG_CHUNK_BYTES.toLong(), len - sentLogBytes).toInt()
                val buf = ByteArray(want)
                val readOk = runCatching {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(sentLogBytes)
                        raf.readFully(buf)
                    }
                }.isSuccess
                if (!readOk) {
                    Timber.w("KVP log upload ($prefix) read failed at byte $sentLogBytes")
                    break
                }
                val atEof = sentLogBytes + want >= len
                // If not at EOF, cut at the last newline in the RAW bytes so the chunk is whole lines
                // and `consumed` is byte-precise — no UTF-8 re-encoding that could drift if the window
                // ended mid-sequence (e.g. splitting a ✓ across chunks).
                var consumed = want.toLong()
                var usedBuf = buf
                if (!atEof) {
                    val nl = buf.indexOfLast { it == '\n'.code.toByte() }
                    if (nl >= 0) {
                        consumed = (nl + 1).toLong()
                        usedBuf = buf.copyOf(nl + 1)
                    }
                }
                val text = String(usedBuf, Charsets.UTF_8)
                if (text.isEmpty()) break
                val lines = text.count { it == '\n' }
                val fileName = "kghost_v${ver}_${id}_${sid}_p${"%03d".format(logChunkSeq)}_$DEVICE_LABEL.log"
                val caption = "KGhost log ($prefix)\nAnon tag: $id\nSession: $sid | $DEVICE_LABEL | v$ver | $lines lines"
                val res = LogReporter.sendLogFile(text, fileName, caption, karooSystem)
                if (res.ok) {
                    sentLogBytes += consumed
                    logChunkSeq += 1
                    sent++
                } else {
                    Timber.w("KVP log upload ($prefix) chunk failed: ${res.message}")
                    break
                }
            }
            if (sent > 0) Timber.i("KVP log upload ($prefix) ✓ — $sent chunk(s), through byte $sentLogBytes")
            // Fully delivered → CAUGHT_UP. Made progress but hit maxChunks → RETRY (drain the rest soon).
            // Zero progress with bytes still pending → the reachable link accepted the probe but the
            // upload failed → SEND_FAILED (counts toward backoff).
            when {
                sentLogBytes >= len -> LogSendOutcome.CAUGHT_UP
                sent > 0 -> LogSendOutcome.RETRY
                else -> LogSendOutcome.SEND_FAILED
            }
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
            val now = SystemClock.elapsedRealtime()
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
     * It reads the [mapGhostState] snapshot the 1 Hz tick publishes and [MapGlide]-interpolates BETWEEN
     * the two most recent published ghost distances (lag, never lead — see [MapGlide]), then emits the
     * marker. Driving the marker off the SAME values the gap field publishes keeps the two coordinated;
     * the old wall-clock extrapolation ran the marker ahead of the field. The loop is the SOLE emitter
     * of the ghost during a ride (both show and hide), so there is no race with the tick — the tick only
     * flips [mapGhostState]. Started in [startTick], cancelled in [stopTick]/[stopTickAndJoin].
     */
    private fun startMapLoop() {
        if (mapLoopJob?.isActive == true) return
        mapLoopJob = scope.launch(Dispatchers.Default) {
            // The two most recent published ghost distances + their monotonic stamps. [MapGlide] interpolates
            // strictly between them so the marker trails the field's published ghost by ≤ one tick and
            // never leads it. Reset on hide so a re-show doesn't glide across the blank.
            var prevDistM = Double.NaN
            var prevMonoMs = 0L
            var curDistM = Double.NaN
            var curMonoMs = 0L
            while (isActive) {
                // Back off to a slow poll when there is no ghost to show (VP mode / before the first
                // route match), while the ride is paused — a paused ghost is frozen in place
                // (the tick republishes the same frozen distance), so the ~5 Hz rate buys nothing
                // during a café stop — OR while no map layer is attached. The host only calls
                // startMap() while the map page is rendered, so [mapEmitter] is null for every minute
                // the rider spends on a DATA page: without it in this condition the loop woke ~5×/s
                // for a whole route ride only to fall through the null-emitter hide below. The next
                // slow delay (≤ MAP_IDLE_REFRESH_MS) restores the fast rate, so re-attaching the map
                // costs ≤1 s before the first glide frame — the same latency the other idle paths
                // already accept.
                val s = mapGhostState
                delay(if (s == null || ridePaused || mapEmitter == null) MAP_IDLE_REFRESH_MS else mapRefreshMs)
                val s2 = mapGhostState
                if (s2 == null || mapEmitter == null || !activeConfig.value.showGhostOnMap) {
                    publishGhostMarker(null) // hide (idempotent — no-op when already hidden)
                    prevDistM = Double.NaN
                    curDistM = Double.NaN // don't glide across the gap when the ghost re-appears
                    continue
                }
                // A new tick published a distance? Shift current → previous so we glide from the value
                // the field last showed to the value it shows now. Keyed on monoMs (monotonic per tick).
                if (s2.monoMs != curMonoMs) {
                    prevDistM = curDistM
                    prevMonoMs = curMonoMs
                    curDistM = s2.ghostDistM
                    curMonoMs = s2.monoMs
                }
                val nowMs = SystemClock.elapsedRealtime()
                val ghostDistM = MapGlide.interpDistM(prevDistM, prevMonoMs, curDistM, curMonoMs, nowMs)
                val marker = GhostMapPresenter.marker(ghostDistM, s2.path, fresh = true)
                publishGhostMarker(marker)
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
            val generation = ++matchGeneration
            // Off Main: polyline decode, candidate file IO, and segment matching are all heavier
            // than a frame. Default is fine; the candidate load does file IO but never overlaps a save
            // in practice (save runs at ride-end, matching at route-load).
            matchJob = scope.launch(Dispatchers.Default) {
                val matchStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    val path = PolylinePath(Polyline.decode(routePolyline))
                    val bbox = BBox.around(path.points) ?: run {
                        if (lastMatchedPolyline == mine) clearRouteMode()
                        return@launch
                    }
                    currentCoroutineContext().ensureActive()
                    val eff = resolveProfile(activeConfig.value, activeProfileId)
                    val pick = eff.ghostPick
                    val key = routeKeyOf(state.name, path.totalM)
                    val store = trackStore()
                    val candidateIds = withContext(Dispatchers.IO) {
                        store.rankedCandidateIdsFor(bbox, CorridorSeeder.MAX_CANDIDATES)
                    }
                    val nowIds = candidateIds.toSet()
                    var agg = withContext(Dispatchers.IO) { aggregateStore().load(key) }
                    // Re-seed when the candidate SET changed enough (symmetric difference), not when its
                    // COUNT changed — auto-tidy churns the set at constant size, which a count gate misses.
                    val needsSeed = shouldReseed(agg, nowIds)
                    val justSeeded = needsSeed
                    // B2: parse the overlapping history on EVERY route load and build the path-following
                    // PacePatch (in-memory only, never persisted → must be rebuilt each load). The SAME
                    // track list also feeds the 1D CorridorSeeder when a (re)seed is needed, so we parse
                    // once here, off Main, rather than twice.
                    val tracks = withContext(Dispatchers.IO) { store.loadByIds(candidateIds) }
                    val pacePatch = PacePatch.build(tracks)
                    // Global gradient model: route-independent, so it is LOADED (not rebuilt) here.
                    val gradePace = GradePaceStore(TrackStorage.tracksDir(applicationContext)).load()
                    Timber.i("KVP route load: gradePace=%s", gradePace?.let { "coveredM=%.0f".format(it.coveredM) } ?: "absent")
                    if (needsSeed) {
                        val seedStartMs = SystemClock.elapsedRealtime()
                        // Store the no-parse id SET so the next load can diff against it.
                        val seeded = CorridorSeeder.seed(key, state.name, path, tracks).copy(seededTrackIds = nowIds.toList())
                        agg = withContext(Dispatchers.IO) { aggregateStore().save(seeded); seeded }
                        Timber.i(
                            "KVP grid: corridor-seeded $key from ${tracks.size} track(s) (set=${nowIds.size}) in ${SystemClock.elapsedRealtime() - seedStartMs}ms",
                        )
                        if (FileLogTree.enabled) {
                            val n = seeded.nodes.size
                            val ge1 = seeded.nodes.count { it.count >= 1 }
                            val ge2 = seeded.nodes.count { it.count >= AGG_MIN_LAPS }
                            val hist = seeded.nodes.groupingBy { it.count }.eachCount().toSortedMap()
                            Timber.i(
                                "KVP seed diag: nodes=%d covered(≥1)=%d (%d%%) avg(≥2)=%d (%d%%) counts=%s",
                                n, ge1, if (n > 0) ge1 * 100 / n else 0,
                                ge2, if (n > 0) ge2 * 100 / n else 0, hist,
                            )
                        }
                    }
                    val matched = agg?.toLiveSegments(pick).orEmpty()
                    if (matched.isEmpty()) {
                        Timber.i("KVP grid: no raceable $pick for '${state.name}' yet — Ghost-Pace until it warms up")
                    } else {
                        Timber.i("KVP grid: racing $pick on ${matched.size} stretch(es)${if (justSeeded) " (just seeded)" else ""}")
                    }
                    // Build the ONE continuous whole-route ghost (recorded stretches + VP-pace fills) that
                    // places the MAP MARKER. Fill pace = the always-present VP target (default 12 km/h), so
                    // the marker always flows across gaps with no recorded history. This VP-fill pace is
                    // UNRELATED to GhostIntegrator's (dead) vpTimePerM constructor arg above — that one no
                    // longer affects anything; this one genuinely drives where the map ghost is drawn.
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
                    if (matchStillOwns(mine, lastMatchedPolyline, generation, matchGeneration)) {
                        routeMode = RouteMode(path, mine, state.name, matched, routeGhost, state.routeDistance, pacePatch, gradePace, agg)
                        // Diagnostic for the scale question: the Karoo's routeDistance (the scale that
                        // DISTANCE_TO_DESTINATION is measured against) vs the decoded-polyline length (the
                        // scale segments + the ghost curve live on). A large delta means routeDist needs
                        // rescaling before it's compared to segment bounds / fed to the ghost.
                        Timber.d(
                            "route mode ON: ${matched.size} segment(s), routeGhost=${routeGhost != null} " +
                                "on '${state.name}' karooLen=${"%.0f".format(state.routeDistance)} " +
                                "polyLen=${"%.0f".format(path.totalM)} delta=${"%.0f".format(state.routeDistance - path.totalM)} " +
                                "matchMs=${SystemClock.elapsedRealtime() - matchStartMs}",
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
     * ONE exception: a pick-only repick rebuilds the ghost curve, and it does so from the CURRENT
     * target — so a pending Ghost-Pace edit also lands the next time the rider changes pick.
     */
    internal data class MatchSig(val active: Boolean, val raceEnabled: Boolean, val pick: GhostPick)

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
        val previous = lastMatchSig
        lastMatchSig = sig
        val current = routeMode
        when (rematchActionFor(previous, sig, current?.polyline, lastMatchedPolyline, pendingNavState != null)) {
            // Nothing claimed/stashed ⇒ nothing the change could have staled; the next nav event
            // resolves fresh.
            RematchAction.NONE -> return
            // A pick-only change can reuse the already loaded history, aggregate and pace models.
            // Preserve the path-following integrator and publish one coherent replacement for the
            // map curve.
            RematchAction.REPICK -> {
                current ?: return
                repickJob?.cancel()
                val eff = resolveProfile(activeConfig.value, activeProfileId)
                repickJob = scope.launch(Dispatchers.Default) {
                    val switched = current.withPick(sig.pick, eff.targetSpeedMs)
                    withContext(Dispatchers.Main) {
                        if (repickStillValid(lastMatchSig, sig, routeMode, current, lastMatchedPolyline)) {
                            routeMode = switched
                            Timber.i("KVP grid: repick → ${sig.pick} on ${switched.segments.size} stretch(es)")
                        }
                    }
                }
                return
            }
            // The initial match is still running: restart it so the latest pick is captured. No route
            // state or race anchor has been published yet, so clearing the claim is safe.
            RematchAction.RESTART_MATCH -> {
                matchJob?.cancel()
                matchGeneration++
                lastMatchedPolyline = null
                lastNavEvent?.let { onNavigationState(it) }
                return
            }
            RematchAction.FULL_REMATCH -> {
                Timber.i("KVP settings changed mid-route → re-matching ($sig)")
                clearRouteMode()
                lastNavEvent?.let { onNavigationState(it) }
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
        // Same reason: a repick resolved against the mode we are about to drop must not land after it.
        repickJob?.cancel()
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

    /**
     * On ride start, if all-files access is missing (and the master switch is on), fire the decaying
     * missing-permission reminder (see [PermissionAlertSchedule]) and persist the new schedule state so
     * the throttle survives process death. No-op when the permission is present or the master switch is
     * off. Reads/writes config on [Dispatchers.IO] via the existing ConfigurationManager.
     */
    private fun maybeAlertMissingPermission() {
        if (StoragePermission.hasAllFilesAccess(applicationContext)) return
        // Two near-simultaneous RideState.Recording emissions (e.g. a host reconnect storm) can each
        // launch this coroutine. Without serialization both would read the same (firedCount,
        // lastFiredEpoch) via loadConfigFlow().first() before either writes back, so both would pass
        // PermissionAlertSchedule.decide() and both dispatch an alert — a duplicate alert plus a lost
        // firedCount increment. permAlertMutex.withLock makes the whole load→decide→dispatch→write
        // sequence a single-flight critical section: the second coroutine only starts its read after
        // the first has already written, so it observes the updated (firedCount, lastFiredEpoch) and
        // decide() correctly throttles it.
        scope.launch(Dispatchers.IO) {
            permAlertMutex.withLock {
                val cfg = configManager.loadConfigFlow().first()
                if (!cfg.masterEnabled) return@withLock
                val now = System.currentTimeMillis()
                val next = PermissionAlertSchedule.decide(
                    PermAlertState(cfg.permAlertFiredCount, cfg.permAlertLastFiredEpoch), now,
                ) ?: return@withLock
                karooSystem.dispatch(
                    InRideAlert(
                        id = "kghost-perm-$now",
                        icon = R.drawable.ic_ghost,
                        title = applicationContext.getString(R.string.perm_alert_title),
                        detail = applicationContext.getString(R.string.perm_alert_detail),
                        autoDismissMs = 10_000L,
                        backgroundColor = R.color.perm_alert_bg,
                        textColor = R.color.perm_alert_text,
                    ),
                )
                configManager.updateConfig {
                    it.copy(permAlertFiredCount = next.firedCount, permAlertLastFiredEpoch = next.lastFiredEpoch)
                }
                Timber.d("KVP perm alert fired count=${next.firedCount}")
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
        // Ride-start config baseline: guarantees every ride's log records the active settings even if the
        // last config-change line rotated out of the file (answers "what settings was this ride on?").
        // lastNav/pendingNav surface WHETHER the nav stream has delivered any state by the time
        // recording starts — key for the "route loaded before the ride → ghost never appears" case:
        // `none` means streamNavigationState gave us nothing yet (a route settled before we subscribed,
        // or navigation isn't active), so route mode can't engage and the map ghost stays hidden.
        Timber.i(
            "KVP startTick START (route=${routeMode != null}) " +
                "lastNav=${lastNavEvent?.state?.let { it::class.simpleName } ?: "none"} " +
                "pendingNav=${pendingNavState != null} config: ${configSummary(activeConfig.value)}",
        )
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
            // Fresh ride file ⇒ nothing uploaded yet from it. Null the tracked path so the next drain
            // re-syncs the byte offset to the new file (belt-and-suspenders with sendLogTail's guard).
            sentLogBytes = 0L
            sentLogFilePath = null
            logChunkSeq = 0
            // Per-ride cadence probes: one ride, one distribution.
            locProbe.reset(); distProbe.reset(); fixAgeProbe.reset()
            locTrustedCount = 0L; tickCount = 0L; distRepeatTicks = 0L
            locRepeatCount = 0L; locRepeatMovingCount = 0L; probeRiderMoving = false
            lastLocLat = Double.NaN; lastLocLng = Double.NaN
            lastCadenceLogMs = 0L; lastTickDistM = Double.NaN
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
                    // LOC_BEARING (deg, 0..360) when present — used only to disambiguate the D0 bootstrap
                    // pass on a self-overlapping route. NaN when the fix carries no heading.
                    val hdg = dp.values[DataType.Field.LOC_BEARING]?.takeIf { it.isFinite() } ?: Double.NaN
                    lastFix = GpsFix(lat, lng, SystemClock.elapsedRealtime(), hdg)
                }
                val nowMs = SystemClock.elapsedRealtime()
                // Cadence probe BEFORE the log throttle — the throttled line below is exactly what
                // must never be used to infer this stream's rate again.
                locProbe.mark(nowMs)
                if (trusted) locTrustedCount++
                if (lat == lastLocLat && lng == lastLocLng) {
                    locRepeatCount++
                    if (probeRiderMoving) locRepeatMovingCount++
                }
                lastLocLat = lat; lastLocLng = lng
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
        // Live gradient consumer — the key into the global pace-vs-gradient model (tier 2), used only on
        // ground with no local history. Its own collector, not a tick combine() arm: the tick must never
        // stall waiting for a gradient sample, and an absent gradient is a legitimate state (the model is
        // simply not asked). Cancelled with the other stream jobs on reconnect/stop.
        if (gradeJob?.isActive != true) {
            gradeJob = karooSystem.streamDataFlow(DataType.Type.ELEVATION_GRADE).onEach { state ->
                val v = (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.ELEVATION_GRADE)
                // UNIT: assumed already a percentage. If a field log shows ratios (0.06 on a 6% ramp),
                // multiply by 100 HERE — the one place that knows the unit — never at the lookup site.
                lastGradePct = v?.takeIf { it.isFinite() }
                lastGradeMs = SystemClock.elapsedRealtime()
                if (!gradeUnitLogged) {
                    gradeUnitLogged = true
                    Timber.i("KVP grade sample: raw=%s", v?.toString() ?: "null")
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
                    lastDestChangeMs = SystemClock.elapsedRealtime()
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
        // Per-EPISODE GPS-loss diagnostic state (diagnostics only — nothing here feeds the rider).
        // One line is emitted when an episode ENDS (quality back to LIVE after COASTING/LONG_LOSS), so
        // a whole ride yields a handful of lines. Detection below is an enum compare per tick; the
        // string is built only on an episode end AND only when the rider's diagnostic log is on.
        var lossPrevQuality = CoastQuality.LIVE
        var lossStartElapsedS = 0.0
        var lossWorst = CoastQuality.LIVE
        var lossNullSpeedTicks = 0
        // Non-LIVE TICKS in this episode. Not derivable from durS: that is an ELAPSED_TIME span, which
        // freezes on a pause and is rounded to the second, so a one-tick slip and a one-tick-plus-pause
        // dropout print the same durS. The tick count is what separates the two populations.
        var lossTicks = 0
        var lossAlerted = false
        var lossClockS = 0.0
        // Cache the ghost curve and rebuild it only when the target speed changes.
        // GhostPaceSource.curve() allocates a fresh curve on every call, so building it
        // inside the 1 Hz tick would churn a curve per second; instead we remember the target
        // it was built for and recompute lazily when that target changes.
        var cachedCurve: GhostCurve? = null
        var cachedTargetMs: Double? = null

        // ② route-mode per-tick state. The per-ride/route ANCHOR (projectorRoute/Polyline, firstMoveElapsedS,
        // vpFirstMoveElapsedS, emptyWindowTicks, lastGoodRouteDistM, distMAtLastGoodM, gpsAlertFired,
        // wasMoving, prevSegStartM, crossedFinish, the integrator + checkpoint fields) lives in INSTANCE FIELDS
        // (declared above) so it SURVIVES a mid-ride host reconnect that cancels+relaunches this tick. It is
        // reset by resetRideAnchor() at a genuine ride end; a genuine route change resets only the route-
        // SPECIFIC marker/finish state (the integrator + race clock are KEPT so the lead carries — 58866d8).
        // Throttle for the per-tick route-mode diagnostic log (≤ ~once per DIAG_LOG_MS); local — a reset on
        // reconnect just costs one extra diag line.
        // Throttle stamp on the MONOTONIC clock (SystemClock.elapsedRealtime), NOT wall-clock: the
        // Karoo serves a cached pre-GPS time and corrects it mid-ride, which can move the wall clock
        // BACKWARD by days/weeks. A throttle on wall-clock (now - last >= interval) then goes permanently
        // negative and silences every diag line for the rest of the ride — exactly when (cold start /
        // GPS settle) the logs matter most. elapsedRealtime never goes backward.
        var lastDiagLogMs = 0L
        val diagLogMs = 2500L

        tickJob = scope.launch(Dispatchers.Default) {
            // Probe the RAW DISTANCE arrivals here: combine()+sample(REFRESH_MS) below discards this
            // timing, and it is the timing that decides whether a repeated sampled value is a genuine
            // freeze or just a stream slower than the tick.
            val distance = karooSystem.streamDataFlow(DataType.Type.DISTANCE)
                .onEach { distProbe.mark(SystemClock.elapsedRealtime()) }
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
                    // Cadence probes (diagnostics only). fixAge is THE quantity GPS_FIX_FRESH_MS is
                    // compared against; distRepeat counts ticks whose sampled DISTANCE equals the
                    // previous tick's — the phasing signature CoastingEstimator reads as a freeze.
                    // Emitted periodically as well as at ride end so a mid-ride power-off still
                    // leaves a usable sample behind.
                    tickCount++
                    probeRiderMoving = (speedMs ?: 0.0) >= StalenessLogic.MIN_MOVING_MS
                    lastFix?.let { fixAgeProbe.add(SystemClock.elapsedRealtime() - it.ms) }
                    if (distM == lastTickDistM) distRepeatTicks++
                    lastTickDistM = distM
                    val cadNow = SystemClock.elapsedRealtime()
                    if (cadNow - lastCadenceLogMs >= CADENCE_LOG_MS) {
                        lastCadenceLogMs = cadNow
                        logCadence("mid-ride")
                    }

                    // BEFORE the per-profile gate so the odometer stays in sync even while the extension
                    // is inert — otherwise re-activating after a disabled stretch would see a distance
                    // jump and misread it as a GPS freeze.
                    coast.update(distM, speedMs, elapsedS)

                    // ── GPS-loss EPISODE diagnostic (diagnostics only, no behaviour) ──────────────
                    // Answers three questions no ride log can answer today: (a) on recovery, does the
                    // host's own DISTANCE jump forward to cover the blind metres (rawStep ≈ coasted) or
                    // resume where it froze (rawStep ≈ 0)? — which decides whether discarding the
                    // dead-reckoned surplus is right; (b) does the SPEED stream ever actually go null on
                    // this device (speedNullTicks); (c) how often/how long losses happen while a route is
                    // loaded (mode=route), where nothing alerts today. One line per episode.
                    // ponytail: an episode still OPEN when the ride ends is never logged (the counters are
                    // locals of the tick job, so stopTick cannot see them). That is the one case where a
                    // real dropout stays invisible; hoist them to fields if a ride ever ends mid-loss and
                    // the silence matters.
                    val cq = coast.quality
                    if (cq == CoastQuality.LIVE) {
                        if (lossPrevQuality != CoastQuality.LIVE) {
                            // Episode over. Same gate as the seed diag: build nothing unless the rider
                            // has the file log on (it is the only sink for a Timber.i in a release build).
                            if (FileLogTree.enabled) {
                                Timber.i(
                                    "KVP gps-loss episode: mode=%s ticks=%d durS=%.0f lossS=%.0f " +
                                        "rawAtFreeze=%.0fm rawAtRecovery=%.0fm rawStep=%.0fm coasted=%.0fm " +
                                        "speedNullTicks=%d worst=%s alertFired=%b",
                                    if (routeMode != null) "route" else "no-route",
                                    lossTicks,
                                    elapsedS - lossStartElapsedS,
                                    lossClockS,
                                    coast.rawAtFreezeM,
                                    distM,
                                    distM - coast.rawAtFreezeM,
                                    coast.coastedSurplusM,
                                    lossNullSpeedTicks,
                                    lossWorst,
                                    lossAlerted || gpsAlertFired,
                                )
                            }
                        }
                    } else {
                        if (lossPrevQuality == CoastQuality.LIVE) {
                            // Episode begins (one tick's worth of freeze already banked in lossClockS).
                            lossStartElapsedS = elapsedS
                            lossNullSpeedTicks = 0
                            lossTicks = 0
                            lossAlerted = false
                            lossWorst = cq
                        }
                        lossTicks++
                        if (cq > lossWorst) lossWorst = cq
                        if (speedMs == null) lossNullSpeedTicks++
                        // handleGpsLoss() runs LATER in this tick, so a fire is observed on the next one
                        // — and on the recovery tick directly, before it re-arms.
                        if (gpsAlertFired) lossAlerted = true
                        lossClockS = coast.coastingSeconds // survives the reset on the recovery tick
                    }
                    lossPrevQuality = cq

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
                        lastFix?.let { recorder.onSample(it.lat, it.lng, distM, elapsedS) }
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
                        val nowMs = SystemClock.elapsedRealtime()
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
                    // The VP race clock is set ONCE per ride and never re-nulled (see its declaration).
                    if (vpFirstMoveElapsedS == null && speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS) {
                        vpFirstMoveElapsedS = elapsedS
                    }

                    // --- mode select: ② route mode vs ① Ghost Pace ---------------------
                    // Read the route-mode snapshot ONCE per tick so path + segments stay consistent
                    // even if the matcher publishes a new RouteMode mid-tick.
                    val rm = routeMode
                    if (rm != null) {
                        // ==================== B2 path-following ghost ====================
                        // The gap NUMBER comes from GhostIntegrator: it accrues the rider's HISTORICAL pace
                        // (PacePatch, neutral-fill on novel ground) over the metres ACTUALLY ridden, so reroutes /
                        // shortcuts / loops are irrelevant BY CONSTRUCTION — no route-distance projection feeds
                        // the number, so it can never teleport. The map MARKER is placed in the ROUTE frame
                        // from that gap + the historical-pace curve (rg): it trails you when you are AHEAD and
                        // runs AHEAD of you to chase when you are BEHIND. The route only positions the ICON —
                        // a bad projection tick jitters the marker, never the number.
                        //
                        // Route-change handling: a NEW polyline re-bootstraps only the route-SPECIFIC marker/finish
                        // state (the integrator + race clock are KEPT, so the accrued lead CARRIES — see the block
                        // below); a same-route re-match (a mid-ride settings change, a new path instance for the
                        // SAME polyline) changes nothing.
                        if (projectorRoute !== rm.path) {
                            val sameRoute = projectorPolyline == rm.polyline
                            projectorRoute = rm.path
                            projectorPolyline = rm.polyline
                            if (!sameRoute) {
                                // CARRY the advantage/deficit across a reroute: the path-following ghost is
                                // ROUTE-AGNOSTIC — it accrues historical time per ridden metre on the ACTUAL path —
                                // so a new polyline does NOT restart the race. KEEP the integrator, its accrued lead,
                                // and the race clock (firstMoveElapsedS); only the route-SPECIFIC marker anchor +
                                // finish state re-bootstrap on the new line (the pace lookup/neutral-fill and the route-
                                // ghost curve follow rm.pacePatch / rm.routeGhost automatically from the next tick).
                                // On the FIRST load these are all already null/fresh, so this is a normal cold start.
                                lastGoodRouteDistM = null // the held rider route position that anchors the marker
                                distMAtLastGoodM = null
                                emptyWindowTicks = 0
                                prevSegStartM = null
                                crossedFinish = false // a new route hasn't been completed yet
                                lap2Started = false
                                lastReliableGhostRouteDist = null
                                // Checkpoint: restore is gated on routeKey (name+length), so a DIFFERENT route can't
                                // restore a foreign lead; the carried integrator's next write stamps the new routeKey
                                // + the carried lead, so a power-off on the new route resumes correctly.
                            }
                        }
                        val rg = rm.routeGhost
                        val patch = rm.pacePatch
                        // Fair start: hold --- until the rider first rolls (a stationary wait for a lock is
                        // never a growing deficit). firstMoveElapsedS is stamped above every tick.
                        val moveStart0 = firstMoveElapsedS
                        if (moveStart0 == null) {
                            holdGap()
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d("KVP tick route(B2): waiting for first movement (race not started)")
                            }
                            return@runCatching
                        }
                        val riderDist = coast.effectiveDistanceM
                        // MOVING-TIME race clock (option B): while the rider isn't advancing on the ground
                        // (stopped at the finish or a light), HOLD raceElapsed by advancing the origin, so the
                        // gap FREEZES at the result instead of drifting BEHIND, and resumes cleanly on moving.
                        // Keyed on the odometer delta — the SAME signal the integrator accrues on (ghostTime only
                        // grows when dd>0), so the two stay consistent (both frozen while stopped). Auto-pause
                        // already freezes ELAPSED_TIME, so this only bites a stop-while-Recording (incl. the sim
                        // sitting at the line). NOT applied before a prior tick exists.
                        var moveStart = moveStart0
                        val prevEl = prevTickElapsedS
                        if (prevEl != null && elapsedS > prevEl && riderDist <= integLastRiderDist) {
                            moveStart += (elapsedS - prevEl)
                            firstMoveElapsedS = moveStart
                        }
                        prevTickElapsedS = elapsedS
                        // Build the integrator ONCE, at first movement — snapshot the pick (integVpTpm is also
                        // captured here but is vestigial, see the comment on its declaration above). A route
                        // change re-nulls it above → fresh race.
                        var integ = integrator
                        if (integ == null) {
                            val vpTpm = (1.0 / eff.targetSpeedMs.coerceAtLeast(0.1)).coerceIn(0.05, 20.0)
                            integ = GhostIntegrator(eff.ghostPick, vpTimePerM = vpTpm, decimateM = 20.0)
                            integrator = integ
                            integPick = eff.ghostPick
                            integVpTpm = vpTpm
                            lastCheckpointMs = 0L
                            // Resume an interrupted ride WITH the accrued lead: restore the persisted checkpoint
                            // iff same pick, RECENT enough, AND either the SAME recordingStartedEpoch
                            // (in-process tick relaunch / host reconnect) OR a CONTINUOUS odometer within a TIGHT
                            // margin (a power-off resume mints a fresh epoch but the ride's distance carries on;
                            // a genuinely new ride starts near 0, far from a stale checkpoint's lastRiderDist).
                            // restore() takes the LEAD, re-anchored on the next tick (no whole-ride inflation).
                            val cp = loadGhostCheckpoint()
                            val riderDistNow = coast.effectiveDistanceM
                            val curKey = routeKeyOf(rm.routeName, rm.path.totalM)
                            // routeKey match is REQUIRED: it deterministically blocks restoring a previous route's
                            // lead onto a new one (rideEpoch is unchanged across a route change), with no dependence
                            // on delete ordering; the stable name+length key also survives a host polyline re-encode.
                            if (cp != null) {
                                val recent = System.currentTimeMillis() - cp.savedAtEpoch in 0..CHECKPOINT_MAX_AGE_MS
                                val keyMatch = cp.routeKey == curKey
                                // vpTimePerM is deliberately NOT part of this gate: since the neutral-fill change
                                // it no longer influences the accrued gap, so gating resume on it only cost riders
                                // their lead whenever they changed the Ghost-Pace target mid-ride-lifecycle.
                                val paramMatch = cp.pick == eff.ghostPick
                                val continuous = cp.rideEpoch == recordingStartedEpoch ||
                                    kotlin.math.abs(riderDistNow - cp.lastRiderDist) <= CHECKPOINT_RESUME_MARGIN_M
                                if (recent && keyMatch && paramMatch && continuous) {
                                    integ.restore(cp.leadS, cp.lastRiderDist)
                                    integLastRiderDist = cp.lastRiderDist
                                    Timber.i(
                                        "KVP B2 checkpoint RESTORED: lead=${"%.0f".format(cp.leadS)}s " +
                                            "lastRiderDist=${"%.0f".format(cp.lastRiderDist)}m " +
                                            "riderNow=${"%.0f".format(riderDistNow)}m " +
                                            "epochMatch=${cp.rideEpoch == recordingStartedEpoch} — lead resumed",
                                    )
                                } else {
                                    // Rejected — log WHY so a silently-dead resume in the field is diagnosable.
                                    Timber.i(
                                        "KVP B2 checkpoint REJECTED: recent=$recent keyMatch=$keyMatch " +
                                            "paramMatch=$paramMatch continuous=$continuous " +
                                            "(cpKey=${cp.routeKey} curKey=$curKey ΔdistM=${"%.0f".format(riderDistNow - cp.lastRiderDist)})",
                                    )
                                }
                            }
                        }
                        // Rider odometer + the trusted GPS fix (lat/lng/heading). A null/stale fix → NaN, and
                        // the integrator neutral-fills (no history lookup) while the marker holds its last route pos.
                        val fix = lastFix
                        val gLat = fix?.lat ?: Double.NaN
                        val gLng = fix?.lng ?: Double.NaN
                        val gHdg = fix?.headingDeg ?: Double.NaN
                        // Historical pace at THIS tick's fix — computed ONCE and reused for both the integrator
                        // accrual and the SEG/GP tag below (the integrator's paceAt is called with the same
                        // lat/lng/heading, so the neighbourhood scan need not run twice per second). Null → neutral-fill
                        // (and GP tag). PacePatch.pace already returns null for a non-finite heading.
                        // FRESHNESS GATE: only a RECENT fix may decide the pace, for EVERY tier below.
                        // `lastFix` is overwritten on a trusted fix but never nulled on staleness, so a dropout
                        // otherwise looks up the history of the position where the fix FROZE and applies it to
                        // every dead-reckoned metre CoastingEstimator invents elsewhere (a 60 s loss on a climb
                        // minted ~+190 s of lead, tagged SEG). The route projection 40 lines down already
                        // refuses a stale fix on the same GPS_FIX_FRESH_MS gate; the number must too — stale ⇒
                        // null ⇒ neutral fill, so dead-reckoned metres get no verdict rather than a fabricated
                        // one. This is why tier 2 below is ALSO gated on fixFresh, not gradeFresh alone:
                        // ELEVATION_GRADE is barometric/odometer-derived, so it keeps streaming right through a
                        // GPS dropout — a stale gradeFresh check on its own would happily supply tier 2's
                        // verdict on the very metres CoastingEstimator invented during the loss.
                        // COAST GATE (same invariant, second layer): the fix can be perfectly FRESH while the
                        // odometer is dead-reckoned — a rider parked in the open, or the first seconds of a
                        // dropout before GPS_FIX_FRESH_MS expires. Those metres were invented, not ridden, so
                        // they must get a neutral fill too. Without this, coast OVERSHOOT is a one-signed
                        // ratchet: the odometer's snap BACK when the fix returns hits GhostIntegrator's dd<0
                        // branch, which keeps ghostTime by design, so the lead bought with phantom metres is
                        // never refunded (measured ~+7.0 s over a dropout, and explicitly NOT linear in
                        // the number of dropouts — the earlier "~+38 s per dropout, linear" figure came
                        // from an experiment that never froze the DISTANCE stream, so what it measured
                        // was a legitimate gain, not phantom metres).
                        // Both conjuncts live in engine.verdictAllowed so the test rigs cannot drift from
                        // this line — see its KDoc for the false green that motivated extracting it.
                        val fixAgeOk = fix != null && SystemClock.elapsedRealtime() - fix.ms <= GPS_FIX_FRESH_MS
                        val fixFresh = verdictAllowed(fixAgeOk, coast.quality)
                        // Tier 1: this exact road, ridden before (PacePatch). Tier 2: my historical pace at THIS
                        // gradient on a road I have never ridden (GradePace). Tier 3 lives in the integrator: a
                        // neutral fill that contributes 0. Both tier 1 and tier 2 require fixFresh (the GPS fix
                        // itself, not just the gradient sample, must be recent); tier 2 additionally requires a
                        // FRESH gradient — a stale gradient describes a hill the rider left minutes ago.
                        val paceHere = if (fixFresh) patch?.pace(gLat, gLng, gHdg, eff.ghostPick) else null
                        val gradeFresh = SystemClock.elapsedRealtime() - lastGradeMs <= GPS_FIX_FRESH_MS
                        val paceGrade = if (paceHere == null && fixFresh && gradeFresh) {
                            lastGradePct?.let { rm.gradePace?.pace(it, eff.ghostPick) }
                        } else {
                            null
                        }
                        val paceNow = paceHere ?: paceGrade
                        integ.onTick(riderDist, gLat, gLng, gHdg, elapsedS - moveStart) { _, _, _ -> paceNow }
                        integLastRiderDist = riderDist
                        // Persist the scalar race state ~every CHECKPOINT_INTERVAL_MS so a mid-ride power-off /
                        // crash resumes with the LEAD (integ.gapTimeS), not the raw ghostTime. The snapshot is
                        // built HERE (Main) so the IO writer never reads the integrator's non-volatile scalars
                        // cross-thread; the actual write is off-Main + serialized.
                        run {
                            val nowCp = SystemClock.elapsedRealtime()
                            // A pick-only repick preserves the integrator; keep checkpoint metadata
                            // aligned with the live settings so restart can restore the preserved lead.
                            integPick = eff.ghostPick
                            if (nowCp - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS && integPick != null) {
                                lastCheckpointMs = nowCp
                                pendingCheckpoint = GhostCheckpoint(
                                    rideEpoch = recordingStartedEpoch,
                                    leadS = integ.gapTimeS,
                                    lastRiderDist = integLastRiderDist,
                                    pick = integPick!!,
                                    vpTimePerM = integVpTpm,
                                    savedAtEpoch = System.currentTimeMillis(),
                                    routeKey = routeKeyOf(rm.routeName, rm.path.totalM),
                                )
                                scope.launch(Dispatchers.IO) { flushGhostCheckpoint() }
                            }
                        }
                        // Rider ROUTE position R — anchors the map marker AND triggers the finish freeze; the
                        // NUMBER never touches it (stays teleport-proof). PRIMARY per-tick projection is a small
                        // odometer-propagated WINDOW (route-distance space, so a loop's two passes sit far apart →
                        // can't be confused). BOOTSTRAP (global) and RECOVERY (a rejoin beyond the window) are
                        // HEADING-gated instead: heading disambiguates a loop's passes WITHOUT an odometer ceiling
                        // — a shortcut legitimately advances route position beyond metres ridden, so the marker
                        // must follow to the real rejoin (an odometer ceiling wrongly FROZE it behind forever).
                        // Held when off the line. Updates land in lastGoodRouteDistM (R) + distMAtLastGoodM.
                        val routeLenM = rm.path.totalM
                        run {
                            val fixR = lastFix
                            if (fixR != null && gLat.isFinite() && gLng.isFinite() &&
                                SystemClock.elapsedRealtime() - fixR.ms <= GPS_FIX_FRESH_MS
                            ) {
                                val base = lastGoodRouteDistM
                                val baseOdo = distMAtLastGoodM
                                val onLine = if (base != null && baseOdo != null) {
                                    // Primary: small odometer-propagated window — loop-safe by route-distance span.
                                    // Perp-gated (nearestProjectionInWindowOrNull returns the nearest IN-window point
                                    // at ANY perp, so without a perp gate it never nulls for an interior base — the
                                    // rider on a shortcut would drag R along the skipped arc and recovery could never
                                    // fire). Perp > MAX ⇒ off the line ⇒ null ⇒ hold ⇒ recovery re-acquires at the
                                    // rejoin. HEADING-gated too when a heading is available, so a hairpin/switchback's
                                    // OPPOSITE leg inside the window can't win on perp alone (the last min-perp
                                    // wrong-leg hole); falls back to perp-only when heading is NaN (stopped/pre-lock,
                                    // where the rider isn't traversing a hairpin anyway).
                                    val fwd = (distM - baseOdo).coerceIn(0.0, ROUTE_PROJ_FWD_MAX_M) + ROUTE_PROJ_FWD_M
                                    if (gHdg.isFinite()) {
                                        rm.path.nearestProjectionByHeadingInWindowOrNull(
                                            LatLng(gLat, gLng), gHdg, base, ROUTE_PROJ_BACK_M, fwd, ROUTE_PROJ_MAX_PERP_M, ROUTE_HEADING_TOL_DEG,
                                        )?.distanceAlongM?.takeIf { it.isFinite() && it <= routeLenM }
                                    } else {
                                        rm.path.nearestProjectionInWindowOrNull(LatLng(gLat, gLng), base, ROUTE_PROJ_BACK_M, fwd)
                                            ?.takeIf { it.perpDistM <= ROUTE_PROJ_MAX_PERP_M }
                                            ?.distanceAlongM?.takeIf { it.isFinite() && it <= routeLenM }
                                    }
                                } else if (base == null && gHdg.isFinite()) {
                                    // Bootstrap: HEADING-gated global so a closed loop (start point == finish point)
                                    // can't coin-flip R onto the end.
                                    rm.path.nearestProjectionByHeadingInWindowOrNull(
                                        LatLng(gLat, gLng), gHdg, 0.0, 0.0, routeLenM, ROUTE_PROJ_MAX_PERP_M, ROUTE_HEADING_TOL_DEG,
                                    )?.distanceAlongM
                                } else if (base == null) {
                                    // Bootstrap with no heading yet (GPS pre-lock): perp-gated global. Accepts the rare
                                    // loop start==end coin-flip (heading refines it next tick) — better than hiding the
                                    // marker for the first seconds of every ride.
                                    rm.path.nearestProjection(LatLng(gLat, gLng))
                                        .let { if (it.perpDistM <= ROUTE_PROJ_MAX_PERP_M) it.distanceAlongM else null }
                                } else {
                                    null
                                }
                                if (onLine != null) {
                                    emptyWindowTicks = 0
                                    lastGoodRouteDistM = onLine
                                    distMAtLastGoodM = distM
                                } else if (base != null && gHdg.isFinite()) {
                                    // Recovery: after RECOVER_EMPTY_TICKS moving ticks off the window (a shortcut that
                                    // rejoined far ahead), a GLOBAL perp+heading re-acquire that adopts a new R ONLY when
                                    // it is UNAMBIGUOUS (a single candidate cluster). If ambiguous (two far-apart passes
                                    // of a self-overlap) or nothing qualifies (still off-route), it returns null → R is
                                    // HELD and the icon HIDES (below) rather than latching a guess — so no wrong rail is
                                    // ever retained. Only scans while MOVING (a parked-off-route rider triggers no O(n)
                                    // sweep); retries every moving tick, counter reset only on a successful re-lock.
                                    val moving = speedMs != null && speedMs > StalenessLogic.MIN_MOVING_MS
                                    if (moving && emptyWindowTicks < RECOVER_EMPTY_TICKS) emptyWindowTicks++
                                    if (moving && emptyWindowTicks >= RECOVER_EMPTY_TICKS) {
                                        rm.path.nearestProjectionByHeadingUnambiguousOrNull(
                                            LatLng(gLat, gLng), gHdg, ROUTE_PROJ_MAX_PERP_M, ROUTE_HEADING_TOL_DEG,
                                            ROUTE_AMBIGUITY_GAP_M, ROUTE_AMBIGUITY_SPAN_M,
                                        )?.let {
                                            lastGoodRouteDistM = it.distanceAlongM
                                            distMAtLastGoodM = distM
                                            emptyWindowTicks = 0
                                        }
                                    }
                                }
                            }
                        }
                        val riderR = lastGoodRouteDistM
                        // R is RELIABLE when the primary window is locked or within the brief off-window grace
                        // (emptyWindowTicks < threshold). Once off-window past the threshold with no unambiguous
                        // recovery, R is a stale hold → the marker HIDES and the finish-freeze won't trip (the
                        // teleport-proof number carries). A GPS loss on-route holds emptyWindowTicks (the R block
                        // is skipped on a stale fix), so a dropout keeps the marker, only a real off-route hides it.
                        val markerReliable = emptyWindowTicks < RECOVER_EMPTY_TICKS
                        // Ghost's ROUTE position (drives the marker AND the behind-distance): the route distance
                        // whose historical time is `rg.timeAt(R) − gapTimeS` — AHEAD ⇒ it trails you on the route,
                        // BEHIND ⇒ it runs AHEAD to chase. rg saturates at routeLen (pin-at-finish).
                        val ghostRouteDist = if (rg != null && riderR != null) {
                            rg.distanceAt((rg.timeAt(riderR) - integ.gapTimeS).coerceAtLeast(0.0))
                        } else {
                            null
                        }
                        // The live gap from the integrator. GapState's sign convention is mathematical (ahead ⇒
                        // gapTimeS<0, gapDistanceM>0); the integrator is the opposite (ahead ⇒ gapTimeS>0).
                        val fresh = coast.quality != CoastQuality.LONG_LOSS
                        // (E) Distance gap while BEHIND: the integrator clamps gapDistM to 0 when the rider is
                        // behind (the ghost is on un-ridden ground — no breadcrumb to measure), so take the
                        // distance-behind from the ROUTE frame (how far the chase-ghost is ahead of you on the
                        // route) → the field shows a real "X behind" that MATCHES the map marker instead of "0".
                        // AHEAD keeps the integrator's teleport-proof breadcrumb distance.
                        val gapDistM = if (integ.gapTimeS < 0.0 && ghostRouteDist != null && riderR != null) {
                            -(ghostRouteDist - riderR).coerceAtLeast(0.0)
                        } else {
                            integ.gapDistM
                        }
                        val liveGap = GapState(
                            gapTimeS = -integ.gapTimeS,
                            gapDistanceM = gapDistM,
                            progressM = riderDist,
                            // Keep ghostProgressM in the ODOMETER frame (consistent with progressM); the (E)
                            // route-frame behind-distance lives only in gapDistanceM for display.
                            ghostProgressM = riderDist - integ.gapDistM,
                            ahead = integ.gapTimeS > 0.0,
                            estimated = !fresh,
                            active = true,
                        )
                        // FINISH FREEZE (#3): once R reaches the route end the race is over — capture the gap AND
                        // Remember the last ghost position drawn while R was reliable — the marker HOLDS this
                        // (frozen) when the rider goes off-route, instead of hiding, so the icon never disappears
                        // while a route is loaded (incl. a Karoo reroute/deviation): it pauses where you left the
                        // line and snaps to the rejoin. Non-latching — R is only ever re-locked UNAMBIGUOUSLY.
                        if (markerReliable && ghostRouteDist != null) lastReliableGhostRouteDist = ghostRouteDist
                        // Option B: the NUMBER never latches at the finish — it keeps racing while you MOVE and
                        // freezes when you STOP (the moving-time clock above). Mark the route COMPLETED the first
                        // time R reaches the end band having ridden ~the whole route, so the ONE-LAP map marker can
                        // be hidden on a 2nd lap (R wraps to the start; the one-lap route-ghost can't place the
                        // icon; the number keeps racing route-agnostically). The odometer corroboration (guards a
                        // spurious near-end R lock mid-route) SCALES to the route: max(routeLen−3km, half the
                        // route) — so it stays a real gate on a short route (< 3 km, where a fixed 3 km would be
                        // vacuously true and could hide the icon for a whole first lap).
                        val finishOdoFloor = (routeLenM - FINISH_ODO_MARGIN_M).coerceAtLeast(routeLenM * 0.5)
                        if (riderR != null && routeLenM > 2 * ROUTE_END_NEAR_M &&
                            routeLenM - riderR <= ROUTE_END_NEAR_M && distM >= finishOdoFloor
                        ) {
                            crossedFinish = true
                        }
                        // LATCH the 2nd-lap hide: once the rider has completed the route AND wrapped back into its
                        // first half, keep the marker hidden for the REST of the ride (else it reappears — pinned
                        // near the start — during the back half of every subsequent lap). A finisher who STOPS at
                        // the line (riderR≈routeLen, not yet wrapped) still sees the frozen icon.
                        if (crossedFinish && riderR != null && riderR < routeLenM * 0.5) lap2Started = true
                        val gap = liveGap
                        val markerDist: Double? = when {
                            lap2Started -> null // 2nd+ lap: the one-lap map has no meaningful ghost position
                            markerReliable -> ghostRouteDist
                            else -> lastReliableGhostRouteDist // off-route: hold the last reliable position
                        }
                        GapStateHolder.update(gap)
                        // SEG/GP tag: SEG when the number got a verdict this tick (PacePatch or GradePace, either
                        // tier), GP on neutral-fill where the number measures nothing. The field reads only
                        // non-null (SEG) — the label is unused, so a stable instance (deduped by the holder)
                        // avoids per-tick churn.
                        val onHistory = paceNow != null
                        if (onHistory) SegmentInfoHolder.set(B2_ON_HISTORY) else SegmentInfoHolder.clear()
                        // Marker (BOTH cases, ROUTE frame) — [markerDist] is the live ghostRouteDist, the FROZEN
                        // finish position, or (off-route) the frozen last-reliable position. Shown whenever we have
                        // ANY of those, so while a route is loaded the icon never disappears — it only pauses.
                        // Hidden only before the first reliable draw (no position yet) or with no route ghost.
                        mapGhostState = if (cfg.showGhostOnMap && markerDist != null) {
                            MapGhostState(markerDist, rm.path, SystemClock.elapsedRealtime())
                        } else {
                            null
                        }
                        run {
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastDiagLogMs >= diagLogMs) {
                                lastDiagLogMs = nowMs
                                Timber.d(
                                    "KVP tick route(B2): riderDist=${"%.0f".format(riderDist)} " +
                                        "gapT=${"%.0f".format(gap.gapTimeS)}s gapD=${"%.0f".format(gap.gapDistanceM)}m " +
                                        "${if (gap.ahead) "AHEAD" else "BEHIND"} ghostTime=${"%.0f".format(integ.ghostTime)} " +
                                        "seg=${if (paceHere != null) "SEG" else if (paceGrade != null) "GRADE" else "GP"} " +
                                        "grade=${lastGradePct?.let { "%.1f".format(it) } ?: "--"} " +
                                        "cov=${"%.0f".format(100.0 * integ.matchedM / (integ.matchedM + integ.filledM).coerceAtLeast(1.0))}% " +
                                        "riderR=${lastGoodRouteDistM?.let { "%.0f".format(it) } ?: "--"} " +
                                        "elapsed=${"%.0f".format(elapsedS)} fresh=$fresh " +
                                        "onRoute=$lastOnRoute rejoin=$lastRejoinActive " +
                                        "speed=${speedMs?.let { "%.1f".format(it) } ?: "null"} showMap=${cfg.showGhostOnMap}",
                                )
                            }
                        }
                        return@runCatching
                    } else {
                        // ① Ghost Pace mode — no route (or empty segments). coast was already
                        // updated above; the helper coasts the DISTANCE stream at the last known speed
                        // during a GPS gap (keeping the gap accurate as an estimate) and treats a genuine
                        // stop as legitimate (frozen distance). It never blanks for a GPS loss — only a
                        // missing target blanks — EXCEPT after a sustained (~3 min) loss, where
                        // handleGpsLoss() gives up and we blank rather than show a wild extrapolation.
                        publishSegment(null, fireExit = false)
                        mapGhostState = null // VP mode: no map ghost (the loop hides it)
                        // ① is reached ONLY with no route: this is the `else` of `if (rm != null)`, so the
                        // compiler proved the old `if (rm != null)` here always false. Under the path-
                        // following model a route with NO matching stretches still takes the route branch
                        // (the integrator races on neutral fill and the map curve is all VP-fill), so the
                        // "route loaded, no recorded stretches" VP case this once distinguished is gone.
                        val vpReason = "no route"
                        // Ride-once VP clock (never re-nulled on a route change) — see vpFirstMoveElapsedS.
                        val moveStart = vpFirstMoveElapsedS
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

    /** One line carrying the whole shape of every stream the freshness/coasting gates depend on.
     *  Gated on the file log like the other ride diagnostics — it is the only sink for a Timber.i in
     *  a release build, and building the string is pointless without one. */
    private fun logCadence(phase: String) {
        if (!FileLogTree.enabled || tickCount == 0L) return
        Timber.i(
            "KVP cadence (%s): %s emits=%d trusted=%d sameLatLng=%d (moving=%d) | %s emits=%d | %s | " +
                "ticks=%d distRepeat=%d (%.1f%%) freshGate=%dms",
            phase,
            locProbe.render(),
            locProbe.emissions,
            locTrustedCount,
            locRepeatCount,
            locRepeatMovingCount,
            distProbe.render(),
            distProbe.emissions,
            fixAgeProbe.render(),
            tickCount,
            distRepeatTicks,
            100.0 * distRepeatTicks / tickCount,
            GPS_FIX_FRESH_MS,
        )
    }

    private fun stopTick() {
        logCadence("ride-end")
        isRecording = false
        // Drop any route stashed during a preview: it must not survive into the NEXT ride's startTick
        // replay (it would activate route mode against a route the rider is no longer navigating).
        pendingNavState = null
        tickJob?.cancel()
        tickJob = null
        locationJob?.cancel()
        locationJob = null
        gradeJob?.cancel()
        gradeJob = null
        lastGradePct = null
        lastGradeMs = 0L
        // Reset the one-shot ELEVATION_GRADE diagnostic probe so it's reachable on every ride, not just
        // the first one after a process start.
        gradeUnitLogged = false
        destJob?.cancel()
        destJob = null
        // Forget the last GPS fix / route position so the NEXT ride starts genuinely cold: D0 is
        // computed from the first on-route fix, and carrying a previous ride's last-known values would
        // let a new ride compute a stale D0 on its first tick.
        lastFix = null
        lastDistToDestM = Double.NaN
        lastOnRoute = false
        lastDestChangeMs = 0L
        lastRejoinActive = false
        lastRejoinDistM = Double.NaN
        // onDestroy teardown — also reset the per-ride/route anchor (instance fields now, not tick locals).
        resetRideAnchor()
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
        gradeJob?.cancel()
        gradeJob = null
        lastGradePct = null
        lastGradeMs = 0L
        // Stop the map loop and clear its snapshot, then the Idle handler's publishGhostMarker(null)
        // hides. cancelAndJoin (not a bare cancel) so no in-flight loop iteration can re-Show the ghost
        // AFTER the hide — its publishGhostMarker has no suspension point, so a plain cancel wouldn't
        // stop a started iteration, leaving a stale marker stuck on the map after the ride.
        mapGhostState = null
        mapLoopJob?.cancelAndJoin()
        mapLoopJob = null
        // A first-ride AVERAGE seed runs INSIDE the match coroutine and saves the aggregate. Join it
        // before finishAndSaveRecording()'s own aggregate save so the two can't overlap (the seed save
        // would otherwise race the ride-end save of the same key). cancelAndJoin: a short ride can end
        // while a slow seed match is still in flight — finishing the ride supersedes it.
        matchJob?.cancelAndJoin()
        matchJob = null
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
        // No per-ride aggregate fold: the corridor model is the single source. The just-saved track
        // enters the next re-seed of any route crossing these cells (lazy, on history growth). Staleness
        // until then is ~3.6 % median per-node and non-accumulating — see the corridor design spec.
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

/** What a mid-ride settings change has to do to the current route match. */
internal enum class RematchAction { NONE, REPICK, RESTART_MATCH, FULL_REMATCH }

/**
 * Which of the four paths a settings change takes. Pure, and top-level for the same reason as
 * [elapsedMsToSeconds]: the orchestration around it (cancelling jobs, bumping the match generation,
 * replaying the nav event) touches a live KarooSystemService and cannot be unit-tested, but the
 * DECISION can — and the decision is where a wrong branch silently strands the rider on stale
 * settings. Callers must have already established that [next] differs from [previous].
 *
 * [routeModePolyline] is `routeMode?.polyline`, so null means no published mode.
 */
internal fun rematchActionFor(
    previous: KGhostExtension.MatchSig?,
    next: KGhostExtension.MatchSig,
    routeModePolyline: String?,
    lastMatchedPolyline: String?,
    hasPendingNav: Boolean,
): RematchAction = when {
    routeModePolyline == null && lastMatchedPolyline == null && !hasPendingNav -> RematchAction.NONE
    // Pick-only, on the route that is actually claimed. Anything else that moved in the signature
    // changes what the match itself would produce, so it cannot reuse the loaded models.
    previous != null && previous.active == next.active && previous.raceEnabled == next.raceEnabled &&
        previous.pick != next.pick && routeModePolyline != null &&
        routeModePolyline == lastMatchedPolyline -> RematchAction.REPICK
    routeModePolyline == null && !hasPendingNav && lastMatchedPolyline != null -> RematchAction.RESTART_MATCH
    else -> RematchAction.FULL_REMATCH
}

/**
 * Whether a resolved repick may still be published. The MATCH publishes `routeMode` on
 * `Dispatchers.Default` while the repick publishes on Main, so this is a cross-thread check-then-act:
 * a new route can claim [lastMatchedPolyline] and publish between the guard and the assignment. The
 * identity term alone does NOT catch that — the polyline term is what stops a superseded repick
 * overwriting the new route's mode with the old one, which the nav dedup would then make permanent.
 */
internal fun repickStillValid(
    liveSig: KGhostExtension.MatchSig?,
    resolvedFor: KGhostExtension.MatchSig,
    liveMode: KGhostExtension.RouteMode?,
    resolvedFrom: KGhostExtension.RouteMode,
    lastMatchedPolyline: String?,
): Boolean = liveSig == resolvedFor && liveMode === resolvedFrom &&
    lastMatchedPolyline == resolvedFrom.polyline

/**
 * Whether a finished match still owns the route it claimed. The claim is the polyline STRING, so a
 * settings-driven re-match of the SAME route re-claims an equal string and the string test alone
 * would let a cancelled-but-surviving match publish over its replacement; [generation] is what
 * distinguishes two claims on identical polylines.
 */
internal fun matchStillOwns(
    claimed: String,
    lastMatchedPolyline: String?,
    generation: Long,
    liveGeneration: Long,
): Boolean = lastMatchedPolyline == claimed && liveGeneration == generation
