package com.enderthor.kghost.datatype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.widget.RemoteViews
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GapDisplay
import com.enderthor.kghost.engine.GapDisplayLogic
import com.enderthor.kghost.engine.GapState
import com.enderthor.kghost.engine.GapStateHolder
import com.enderthor.kghost.engine.RenderPrefs
import com.enderthor.kghost.engine.SegmentInfoHolder
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Graphical gap data field — the "option B" track + two-dots view.
 *
 * Draws a horizontal track bar with two dots:
 *  - Your dot is fixed near the centre of a visible window (you are the frame of reference).
 *  - The ghost dot is offset from centre by the current [GapState.gapDistanceM] within a
 *    ±[WINDOW_M]-metre window. When you are ahead the ghost sits behind you (to the left);
 *    when you are behind it sits ahead (to the right). This gives the "closing in / falling
 *    back" reading without needing the route geometry (that arrives in sub-project ②).
 *
 * Your dot is green when ahead and red when behind (reserved state hues). Below the track the
 * gap text is drawn (time big, distance small, per the rider's [GapDisplay] preference).
 *
 * The field draws a neutral `---` (day/night colour, NOT a disabled grey) ONLY when the gap is not
 * [GapState.active] (no target / not recording / no first data). A GPS loss does NOT blank: the value
 * keeps dead-reckoning and, once it is a prolonged-loss estimate ([GapState.estimated]), the gap text
 * is drawn in the amber estimate colour (the YOU dot keeps its ahead/behind hue). A legitimate stop
 * shows the real gap; only a truly sustained loss (the extension gives up and clears) returns to `---`.
 *
 * Passive readout, so there is no tap PendingIntent.
 *
 * ## Concurrency / render-buffer ownership
 * ALL mutable render state (the reused Bitmap+Canvas, every Paint that is mutated during draw,
 * and the Rect used for text bounds) lives in a [FrameRenderer] that is created fresh INSIDE the
 * render coroutine of each [startView]. The buffers are reused across frames within that one
 * coroutine (so we still avoid per-frame allocation of a ~600 KB bitmap at 1 Hz over multi-hour
 * rides) but are never shared between coroutines. This matters because [startView] can be
 * re-entered (page change) and the re-entry guard's [Job.cancel] is asynchronous and non-joining;
 * since [FrameRenderer.draw] has no suspension points, an old (cancelled-but-still-running)
 * coroutine could otherwise be mid-draw on the SAME shared bitmap/canvas/paints while a new one
 * starts. Per-coroutine ownership removes that cross-scope mutation entirely.
 */
class GapGraphicDataType(
    private val context: Context,
) : DataTypeImpl("kghost", "kghost-gap") {

    private companion object {
        const val PLACEHOLDER = "---"

        /**
         * Slow re-emit cadence so a frame ALWAYS lands after karoo-ext 1.1.9's 900 ms ViewEmitter
         * throttle window. On a ride re-start the host re-calls [startView], but the synchronous seed
         * frame can be throttle-dropped ("ignoring updateView, too soon"); the change-driven render
         * loop then emits only on a real state CHANGE, so a static/inactive gap state leaves the
         * field stuck showing its NAME (the host's default header) forever. Re-rendering the CURRENT
         * state every [HEARTBEAT_MS] guarantees a post-throttle frame even when nothing changes. The
         * render is cheap (reused bitmap) and ~3 s is spaced well beyond the 900 ms throttle so it
         * always lands.
         */
        const val HEARTBEAT_MS = 3000L

        /** Half-width of the visible window in metres: ghost is clamped to ±this around you.
         *  Shown on the field as the "±N m" scale label. Larger = more range before the dot pins
         *  to an edge, at the cost of less sensitivity to small gaps near the centre. */
        const val WINDOW_M = 300.0

        /** Fallback bitmap size when [ViewConfig.viewSize] is unavailable or non-positive. */
        const val FALLBACK_W = 200
        const val FALLBACK_H = 120

        /** Upper bound so an oversized field can't allocate a huge bitmap each frame. */
        const val MAX_W = 480
        const val MAX_H = 320

        /**
         * Synthetic state shown in the profile-editor gallery (config.preview = true).
         * Represents being ~1:30 ahead and ~60 m in front — a readable, non-trivial snapshot.
         */
        val DEMO_STATE = GapState(
            gapTimeS = -90.0,
            gapDistanceM = 60.0,
            progressM = 0.0,
            ghostProgressM = 0.0,
            ahead = true,
            estimated = false,
            active = true,
        )
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("KVP gap-graphic startView preview=${config.preview}")
        // Each placement gets its OWN independent scope, cancelled ONLY by its own setCancellable
        // below — never a shared "cancel the previous scope" guard. The rider can put this field on
        // TWO pages, which the host serves from this ONE DataTypeImpl instance via separate startView
        // calls; cancelling a sibling scope here would FREEZE the other page's field. Each startView
        // already builds its own FrameRenderer, so concurrent render loops never share buffers.
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        // Render buffers are OWNED by this startView invocation — created here, then used by the
        // synchronous seed below AND reused across frames in viewJob's collect loop. The seed runs
        // synchronously on the host thread and MUST fully complete before viewJob is launched, so
        // the seed draw and every loop draw are strictly sequential on this one renderer; they never
        // run concurrently. Each startView still creates its OWN renderer (re-entry safe — a
        // cancelled-but-still-running prior startView owns a different renderer). See class KDoc.
        val renderer = FrameRenderer(context)

        // Seed one frame synchronously from the LIVE current state to beat karoo-ext 1.1.9's
        // ViewEmitter.updateView 900 ms throttle (it hard-drops any call within 900 ms of the
        // previous one, keeping the EARLIER one). Without this, viewJob's first real frame (a few ms
        // later) would be silently dropped, leaving the graphic field blank/`---` for ~1 s on a
        // mid-ride page re-entry while a valid gap existed. Seeding with the same values the dropped
        // frame would carry makes that drop harmless.
        run {
            val (sw, sh) = bitmapSize(config)
            val seedState = if (config.preview) DEMO_STATE else GapStateHolder.state.value
            // Mode tag: route segment (②) when SegmentInfoHolder.info is non-null, else fixed-pace
            // Ghost Pace (①). Preview always shows the Ghost-Pace sample.
            val seedIsRoute = if (config.preview) false else SegmentInfoHolder.info.value != null
            val seedBmp = renderer.draw(
                sw, sh, seedState, RenderPrefs.gapDisplay.value, seedIsRoute,
                RenderPrefs.imperialDistance.value,
            )
            val seedRv = RemoteViews(context.packageName, R.layout.field_gap)
            seedRv.setImageViewBitmap(R.id.field_gap_image, seedBmp)
            emitter.updateView(seedRv)
        }

        val viewJob = scope.launch {
            // Reuses the SAME renderer the seed used (do NOT create a second renderer here). The
            // seed has already completed synchronously before this launch, so there is no concurrent
            // access to the renderer.
            try {
                // Wall-clock (ms) of the last frame we actually emitted. Makes the heartbeat
                // IDLE-ONLY: it re-emits only when no change frame went out in the last HEARTBEAT_MS,
                // so it no longer collides with the ~1 Hz change emits (which the host would drop with
                // "ignoring updateView, too soon") while still guaranteeing a post-throttle frame when
                // the state is static (anti-stuck).
                var lastEmitMs = 0L
                // One-shot diagnostic: log the FIRST frame this view actually emits with an ACTIVE gap
                // (the `--- → number` transition). Confirms from the ride log whether the field started
                // rendering numbers as soon as the gap went active, or appeared stuck. Drop once verified.
                var loggedFirstActive = false
                // Cheap render-key dedup: the gap doubles jitter every ~1 Hz tick but the dots/text
                // change far less often, so skip the bitmap redraw + updateView IPC when the key is
                // unchanged. lastRv caches the last view so the heartbeat re-asserts it without redraw.
                var lastKey: GapRenderKey? = null
                var lastRv: RemoteViews? = null
                // Change-driven source (isHeartbeat = false): emits on every real state/pref/mode
                // change. GapStateHolder.state is a StateFlow (dedups its own value); the key dedup
                // below drops the sub-display-resolution jitter. The heartbeat MUST NOT be deduped
                // against it (it re-asserts the SAME current value), so it's merged in separately.
                val changes = combine(
                    GapStateHolder.state,
                    RenderPrefs.gapDisplay,
                    // Map to Boolean so we don't re-render on every segment-detail change — only on
                    // the route/Ghost-Pace mode transition.
                    SegmentInfoHolder.info.map { it != null },
                    // Folded in so a mid-ride unit flip (km↔mi) repaints immediately rather than
                    // waiting for the next gap change or the heartbeat; read via .value at render.
                    RenderPrefs.imperialDistance,
                ) { state, gapDisplay, isRoute, _ -> Triple(state, gapDisplay, isRoute) to false }
                // Heartbeat (isHeartbeat = true): re-emits the CURRENT state every HEARTBEAT_MS so a
                // throttle-dropped seed/first-frame can't leave the field stuck on its name. Merged
                // INTO this single collect (NOT a separate coroutine) so the per-coroutine
                // renderer/buffers are never touched from another coroutine — see the class KDoc.
                val heartbeat = flow {
                    while (true) {
                        delay(HEARTBEAT_MS)
                        emit(
                            Triple(
                                GapStateHolder.state.value,
                                RenderPrefs.gapDisplay.value,
                                SegmentInfoHolder.info.value != null,
                            ) to true,
                        )
                    }
                }
                merge(changes, heartbeat)
                    .collect { (data, isHeartbeat) ->
                        val now = System.currentTimeMillis()
                        // Drop the periodic heartbeat if a real frame already went out recently — this
                        // is what removes the every-3 s "too soon" collisions during a ride.
                        if (isHeartbeat && now - lastEmitMs < HEARTBEAT_MS) return@collect
                        val (liveState, gapDisplay, liveIsRoute) = data
                        // In preview (profile editor gallery) render a synthetic demo state so the
                        // field shows a meaningful sample instead of the inactive `---` placeholder.
                        val state = if (config.preview) DEMO_STATE else liveState
                        val isRoute = if (config.preview) false else liveIsRoute
                        val imperial = RenderPrefs.imperialDistance.value
                        val key = gapRenderKey(
                            state, gapDisplay, isRoute,
                            dark = context.isKarooNightMode(), imperial = imperial,
                        )
                        if (isHeartbeat) {
                            val cached = lastRv
                            if (cached != null && key == lastKey) {
                                emitter.updateView(cached) // re-assert cached frame, no redraw
                                lastEmitMs = now
                                return@collect
                            }
                        } else if (key == lastKey && lastRv != null) {
                            return@collect // pixel-identical change → skip redraw + IPC
                        }
                        val (w, h) = bitmapSize(config)
                        val bmp = renderer.draw(w, h, state, gapDisplay, isRoute, imperial)
                        val rv = RemoteViews(context.packageName, R.layout.field_gap)
                        rv.setImageViewBitmap(R.id.field_gap_image, bmp)
                        emitter.updateView(rv)
                        if (!config.preview && !loggedFirstActive && state.active) {
                            loggedFirstActive = true
                            Timber.d("KVP gap-graphic: first ACTIVE frame emitted (gap now rendering)")
                        }
                        lastKey = key
                        lastRv = rv
                        lastEmitMs = now
                    }
            } catch (_: CancellationException) {
                Timber.d("KVP gap-graphic loop cancelled (field removed)")
            } catch (e: Exception) {
                Timber.e(e, "GapGraphicDataType error: ${e.message}")
            } finally {
                // Safe: same coroutine, after the collect loop has stopped — no draw can be in
                // flight here.
                renderer.recycle()
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
            // No render buffers to clean up here: they are owned by viewJob's coroutine and
            // recycled in its `finally`. This cancellable runs on the host thread, so it must NOT
            // touch the (possibly in-flight) bitmap/canvas — doing so would risk a
            // use-after-recycle from a different thread.
        }
    }

    /** Derives a bounded bitmap size from the field's [ViewConfig.viewSize]. */
    private fun bitmapSize(config: ViewConfig): Pair<Int, Int> {
        val w = config.viewSize.first.takeIf { it > 0 } ?: FALLBACK_W
        val h = config.viewSize.second.takeIf { it > 0 } ?: FALLBACK_H
        return w.coerceIn(1, MAX_W) to h.coerceIn(1, MAX_H)
    }

    /**
     * Per-[startView] render state. Holds the reused Bitmap+Canvas and all Paint/Rect objects,
     * which are mutated during [draw] (colour, stroke width, text size, text bounds). Instantiated
     * once per render coroutine and confined to it, so concurrent render coroutines each have their
     * own buffers and never mutate shared state. Reuses its buffers across frames within that one
     * coroutine; recreates the bitmap only when the target size changes.
     */
    private class FrameRenderer(private val context: Context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF888888.toInt()
        }
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Colour set per draw (day/night-aware) — see ghostPaint.color in draw().
        }
        private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        // Dedicated paint for the top-band tags (mode tag left, scale label right). Kept separate
        // from hintPaint so we never clobber hintPaint's CENTER alignment (used for the small gap
        // value). textAlign/colour/size are set per draw. Pre-allocated — no per-frame allocation.
        private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textBounds = Rect()

        private var reuseBitmap: Bitmap? = null
        private var reuseCanvas: Canvas? = null

        /** Releases the reused bitmap. Call once, from the owning coroutine, after the loop ends. */
        fun recycle() {
            reuseBitmap?.recycle()
            reuseBitmap = null
            reuseCanvas = null
        }

        fun draw(w: Int, h: Int, state: GapState, gapDisplay: GapDisplay, isRoute: Boolean, imperial: Boolean): Bitmap {
            // Reuse the bitmap+Canvas across frames; only recreate when the target size changes
            // (e.g. config.viewSize changed). Same-coroutine: this recreate cannot race a draw from
            // another scope. RemoteViews copies the bitmap into the Binder parcel at updateView
            // time, so reusing the buffer on the next frame does not corrupt an already-dispatched
            // frame. The whole bitmap is repainted each frame (background first), so no stale pixels.
            var bmp = reuseBitmap
            var canvas = reuseCanvas
            if (bmp == null || canvas == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                canvas = Canvas(bmp)
                reuseBitmap = bmp
                reuseCanvas = canvas
            }

            // Night mode: black background (matches Karoo dark UI, white text readable).
            // Day mode: white background (sunlight-readable; black text on black = invisible).
            val dark = context.isKarooNightMode()
            val bgColor = if (dark) Color.BLACK else Color.WHITE
            canvas.drawColor(bgColor)

            val neutral = if (dark) Color.WHITE else Color.BLACK

            // Blank ONLY on !active (no target / not recording / no first data). A GPS loss does NOT
            // blank: the value keeps coasting and is drawn in the estimate colour instead (a navigator
            // should keep showing a best estimate through a dropout, not go dark).
            val waiting = !state.active
            if (waiting) {
                // Neutral `---` centred — waiting for data, not disabled.
                textPaint.color = neutral
                textPaint.textSize = h * 0.4f
                textPaint.getTextBounds(PLACEHOLDER, 0, PLACEHOLDER.length, textBounds)
                canvas.drawText(PLACEHOLDER, w / 2f, h / 2f - textBounds.exactCenterY(), textPaint)
                return bmp
            }

            // Layout: track in the top ~55%, text in the bottom ~45%.
            val trackCy = h * 0.30f
            val margin = w * 0.06f
            val left = margin
            val right = w - margin
            val span = right - left

            // Top-band tags (only on the active/non-stale path, i.e. when the dots/gap are shown).
            // Mode tag top-left: "SEG" for a route segment (②), "GP" for the fixed-pace Ghost Pace
            // (①). Scale label top-right: the ±WINDOW_M window the dot spread represents.
            tagPaint.color = neutral
            tagPaint.textSize = h * 0.13f
            val tagY = h * 0.13f
            tagPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(if (isRoute) "SEG" else "GP", left, tagY, tagPaint)
            tagPaint.textAlign = Paint.Align.RIGHT
            val windowLabel = if (imperial) "±${(WINDOW_M * FEET_PER_METRE).toInt()} ft" else "±${WINDOW_M.toInt()} m"
            canvas.drawText(windowLabel, right, tagY, tagPaint)

            // Track bar.
            trackPaint.strokeWidth = (h * 0.04f).coerceAtLeast(2f)
            canvas.drawLine(left, trackCy, right, trackCy, trackPaint)

            // Your dot fixed at centre; ghost offset by gapDistanceM within ±WINDOW_M.
            // gapDistanceM > 0 means you are ahead → ghost is behind you (to the left).
            val frac = (-state.gapDistanceM / WINDOW_M).coerceIn(-1.0, 1.0) // -1..1, 0 = centre
            val youX = left + span * 0.5f
            val ghostX = (left + span * (0.5 + 0.5 * frac)).toFloat()

            // Three-state classification with a small epsilon: an exactly-on-pace gap renders neutral
            // (day/night colour, no leading sign) rather than a misleading green "+0:00".
            val status = GapDisplayLogic.gapStatus(state.gapTimeS)
            val stateColor = context.gapStatusColor(status, neutral, dark)

            val dotR = (h * 0.07f).coerceIn(3f, 14f)
            // Ghost dot (grey) first so an overlap draws your dot on top. Day/night-aware: a darker
            // grey on the white day background (light grey would be low-contrast), lighter grey on
            // the black night background. Kept clearly distinct from the green/red YOUR dot.
            ghostPaint.color = if (dark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
            canvas.drawCircle(ghostX, trackCy, dotR, ghostPaint)
            youPaint.color = stateColor
            canvas.drawCircle(youX, trackCy, dotR, youPaint)

            // Gap text below the track. While the value is a prolonged-GPS-loss estimate, the TEXT is
            // drawn amber (the YOU dot keeps its ahead/behind status hue) so the rider can tell the
            // number is extrapolated, not measured.
            val timeText = fmtTime(state.gapTimeS, status)
            val distText = fmtDistance(state.gapDistanceM, imperial)
            val textColor = if (state.estimated) context.gapEstimateColor(dark) else stateColor
            when (gapDisplay) {
                GapDisplay.TIME -> drawBig(canvas, w, h, timeText, textColor)
                GapDisplay.DISTANCE -> drawBig(canvas, w, h, distText, textColor)
                GapDisplay.BOTH -> drawBigAndSmall(canvas, w, h, timeText, distText, textColor)
            }
            return bmp
        }

        /** Draws a single big value centred in the lower text area. */
        private fun drawBig(canvas: Canvas, w: Int, h: Int, text: String, color: Int) {
            textPaint.color = color
            textPaint.textSize = h * 0.34f
            canvas.drawText(text, w / 2f, h * 0.82f, textPaint)
        }

        /** Draws a big primary value with a small secondary value beneath it. */
        private fun drawBigAndSmall(canvas: Canvas, w: Int, h: Int, big: String, small: String, color: Int) {
            textPaint.color = color
            textPaint.textSize = h * 0.30f
            canvas.drawText(big, w / 2f, h * 0.72f, textPaint)
            hintPaint.color = color
            hintPaint.textSize = h * 0.18f
            canvas.drawText(small, w / 2f, h * 0.94f, hintPaint)
        }
    }
}
