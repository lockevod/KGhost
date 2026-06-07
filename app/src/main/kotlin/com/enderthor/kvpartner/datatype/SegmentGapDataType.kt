package com.enderthor.kvpartner.datatype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.widget.RemoteViews
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GapDisplay
import com.enderthor.kvpartner.engine.GapDisplayLogic
import com.enderthor.kvpartner.engine.GapState
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.engine.RenderPrefs
import com.enderthor.kvpartner.engine.SegmentInfo
import com.enderthor.kvpartner.engine.SegmentInfoHolder
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Adaptive segment gap data field — the "race your own ghost over this stretch" view.
 *
 * Unlike [GapGraphicDataType] (which always draws the abstract two-dot track), this field reads
 * BOTH [GapStateHolder.state] AND [SegmentInfoHolder.info] and switches render mode by whether the
 * live segment carries elevation:
 *
 *  - **Profile render (A)** — when [SegmentInfo.hasElevation] is true: draws the segment's
 *    elevation silhouette from [SegmentInfo.elevationProfile] (a list of
 *    `(distanceInSegmentM, altitudeM)` points). Your marker sits at your fraction along the
 *    segment ([GapState.progressM] mapped into `routeStartM..routeEndM`); the ghost marker sits at
 *    the ghost's fraction ([GapState.ghostProgressM] over the segment length). The gap-vs-PR time
 *    is drawn big (green ahead / red behind / neutral on-pace, via [GapDisplayLogic.gapStatus] +
 *    the shared [gapStatusColor]/[fmtTime]), with the segment label beneath.
 *  - **Track render (B)** — otherwise: the same two-dot abstract track as ① (you coloured by
 *    status, ghost grey) plus the big gap time, a small distance, and the segment label.
 *
 * When there is no live segment ([SegmentInfoHolder.info] is null) or the gap is not
 * [GapState.active] / is [GapState.stale], the field draws a neutral `---` (day/night colour, NOT a
 * disabled grey) — waiting for / cannot trust data, not turned off.
 *
 * Passive readout, so there is no tap PendingIntent.
 *
 * ## Concurrency / render-buffer ownership
 * ALL mutable render state (the reused Bitmap+Canvas, every Paint that is mutated during draw, the
 * Path used for the silhouette, and the Rect used for text bounds) lives in a [FrameRenderer] that
 * is created fresh INSIDE the render coroutine of each [startView]. The buffers are reused across
 * frames within that one coroutine (so we avoid per-frame allocation of a large bitmap at 1 Hz over
 * multi-hour rides) but are never shared between coroutines. This matters because [startView] can
 * be re-entered (page change) and the re-entry guard's [Job.cancel] is asynchronous and
 * non-joining; per-coroutine ownership removes any cross-scope mutation. Mirrors ①'s
 * [GapGraphicDataType] exactly.
 */
class SegmentGapDataType(
    private val context: Context,
) : DataTypeImpl("kvpartner", "kvpartner-segment") {

    /**
     * Tracks the coroutine scope of the currently active view so a re-entrant [startView]
     * (the Karoo host can call it again for the same field) cancels the previous scope first,
     * avoiding two render loops fighting over the same emitter.
     */
    @Volatile
    private var activeScopeJob: Job? = null

    private companion object {
        const val PLACEHOLDER = "---"

        /** Fallback bitmap size when [ViewConfig.viewSize] is unavailable or non-positive. */
        const val FALLBACK_W = 200
        const val FALLBACK_H = 120

        /** Upper bound so an oversized field can't allocate a huge bitmap each frame. */
        const val MAX_W = 480
        const val MAX_H = 320

        /**
         * Synthetic state shown in the profile-editor gallery (config.preview = true).
         * Represents being ~0:45 ahead, ~60 % through the segment with the ghost just behind.
         */
        val DEMO_STATE = GapState(
            gapTimeS = -45.0,
            gapDistanceM = 40.0,
            progressM = 600.0,
            ghostProgressM = 560.0,
            ahead = true,
            stale = false,
            active = true,
        )

        /**
         * Synthetic segment shown in preview: a 1000 m climb with a simple elevation silhouette so
         * the profile (A) render shows a meaningful sample instead of `---`.
         */
        val DEMO_INFO = SegmentInfo(
            routeStartM = 0.0,
            routeEndM = 1000.0,
            label = "Col preview",
            hasElevation = true,
            elevationProfile = listOf(
                0.0 to 100.0,
                250.0 to 140.0,
                500.0 to 210.0,
                750.0 to 250.0,
                1000.0 to 300.0,
            ),
        )
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Re-entry guard: cancel any previous render scope before starting a new one.
        activeScopeJob?.cancel()

        val scopeJob = Job()
        activeScopeJob = scopeJob
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        // Render buffers are OWNED by this startView invocation — created here, then used by the
        // synchronous seed below AND reused across frames in viewJob's collect loop. The seed runs
        // synchronously on the host thread and MUST fully complete before viewJob is launched, so
        // the seed draw and every loop draw are strictly sequential on this one renderer; they never
        // run concurrently. Each startView still creates its OWN renderer (re-entry safe).
        val renderer = FrameRenderer(context)

        // Seed one frame synchronously from the LIVE current state to beat karoo-ext's
        // ViewEmitter.updateView 900 ms throttle (it hard-drops any call within 900 ms of the
        // previous one, keeping the EARLIER one). Without this, viewJob's first real frame would be
        // silently dropped, leaving the field blank/`---` for ~1 s on a mid-ride page re-entry while
        // a valid segment existed. Seeding with the same values the dropped frame would carry makes
        // that drop harmless.
        run {
            val (sw, sh) = bitmapSize(config)
            val seedState = if (config.preview) DEMO_STATE else GapStateHolder.state.value
            val seedInfo = if (config.preview) DEMO_INFO else SegmentInfoHolder.info.value
            val seedBmp = renderer.draw(sw, sh, seedState, seedInfo, RenderPrefs.gapDisplay.value)
            val seedRv = RemoteViews(context.packageName, R.layout.field_segment)
            seedRv.setImageViewBitmap(R.id.field_segment_image, seedBmp)
            emitter.updateView(seedRv)
        }

        val viewJob = scope.launch {
            // Reuses the SAME renderer the seed used (do NOT create a second renderer here). The
            // seed has already completed synchronously before this launch, so there is no concurrent
            // access to the renderer.
            try {
                combine(
                    GapStateHolder.state,
                    SegmentInfoHolder.info,
                    RenderPrefs.gapDisplay,
                ) { state, info, gapDisplay -> Triple(state, info, gapDisplay) }
                    .distinctUntilChanged()
                    .collect { (liveState, liveInfo, gapDisplay) ->
                        // In preview (profile editor gallery) render a synthetic demo so the field
                        // shows a meaningful sample instead of the inactive `---` placeholder.
                        val state = if (config.preview) DEMO_STATE else liveState
                        val info = if (config.preview) DEMO_INFO else liveInfo
                        val (w, h) = bitmapSize(config)
                        val bmp = renderer.draw(w, h, state, info, gapDisplay)
                        val rv = RemoteViews(context.packageName, R.layout.field_segment)
                        rv.setImageViewBitmap(R.id.field_segment_image, bmp)
                        emitter.updateView(rv)
                    }
            } catch (_: CancellationException) {
                // normal — field removed from the page.
            } catch (e: Exception) {
                Timber.e(e, "SegmentGapDataType error: ${e.message}")
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
            // No render buffers to clean up here: they are owned by viewJob's coroutine and recycled
            // in its `finally`. This cancellable runs on the host thread, so it must NOT touch the
            // (possibly in-flight) bitmap/canvas — doing so would risk a use-after-recycle from a
            // different thread.
        }
    }

    /** Derives a bounded bitmap size from the field's [ViewConfig.viewSize]. */
    private fun bitmapSize(config: ViewConfig): Pair<Int, Int> {
        val w = config.viewSize.first.takeIf { it > 0 } ?: FALLBACK_W
        val h = config.viewSize.second.takeIf { it > 0 } ?: FALLBACK_H
        return w.coerceIn(1, MAX_W) to h.coerceIn(1, MAX_H)
    }

    /**
     * Per-[startView] render state. Holds the reused Bitmap+Canvas and all Paint/Path/Rect objects,
     * which are mutated during [draw] (colour, stroke width, text size, text bounds, the silhouette
     * path). Instantiated once per render coroutine and confined to it, so concurrent render
     * coroutines each have their own buffers and never mutate shared state. Reuses its buffers
     * across frames within that one coroutine; recreates the bitmap only when the target size
     * changes.
     */
    private class FrameRenderer(private val context: Context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF888888.toInt()
        }
        private val profileLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF888888.toInt()
        }
        private val profileFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFAAAAAA.toInt() // grey ghost dot
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
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val profilePath = Path()
        private val textBounds = Rect()

        private var reuseBitmap: Bitmap? = null
        private var reuseCanvas: Canvas? = null

        /** Releases the reused bitmap. Call once, from the owning coroutine, after the loop ends. */
        fun recycle() {
            reuseBitmap?.recycle()
            reuseBitmap = null
            reuseCanvas = null
        }

        fun draw(w: Int, h: Int, state: GapState, info: SegmentInfo?, gapDisplay: GapDisplay): Bitmap {
            // Reuse the bitmap+Canvas across frames; only recreate when the target size changes.
            // Same-coroutine: this recreate cannot race a draw from another scope. RemoteViews
            // copies the bitmap into the Binder parcel at updateView time, so reusing the buffer on
            // the next frame does not corrupt an already-dispatched frame. The whole bitmap is
            // repainted each frame (background first), so no stale pixels.
            var bmp = reuseBitmap
            var canvas = reuseCanvas
            if (bmp == null || canvas == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                canvas = Canvas(bmp)
                reuseBitmap = bmp
                reuseCanvas = canvas
            }

            // Night mode: black background; Day mode: white background (sunlight-readable).
            val dark = context.isKarooNightMode()
            val bgColor = if (dark) Color.BLACK else Color.WHITE
            canvas.drawColor(bgColor)

            val neutral = if (dark) Color.WHITE else Color.BLACK

            // Blank on no-segment, !active, or stale → neutral `---`, NOT a disabled grey.
            val waiting = info == null || !state.active || state.stale
            if (waiting) {
                textPaint.color = neutral
                textPaint.textSize = h * 0.4f
                textPaint.getTextBounds(PLACEHOLDER, 0, PLACEHOLDER.length, textBounds)
                canvas.drawText(PLACEHOLDER, w / 2f, h / 2f - textBounds.exactCenterY(), textPaint)
                return bmp
            }

            val status = GapDisplayLogic.gapStatus(state.gapTimeS)
            val stateColor = context.gapStatusColor(status, neutral, dark)
            val timeText = fmtTime(state.gapTimeS, status)

            if (info.hasElevation && !info.elevationProfile.isNullOrEmpty()) {
                drawProfile(canvas, w, h, state, info, neutral, stateColor, timeText)
            } else {
                drawTrack(canvas, w, h, state, info, neutral, stateColor, timeText, gapDisplay)
            }
            return bmp
        }

        /**
         * Profile render (A): the elevation silhouette with your/ghost markers, the big gap time
         * and the segment label. The segment-fraction for each marker is derived from the engine's
         * progress on the ghost's distance axis mapped into the segment length.
         */
        private fun drawProfile(
            canvas: Canvas,
            w: Int,
            h: Int,
            state: GapState,
            info: SegmentInfo,
            neutral: Int,
            stateColor: Int,
            timeText: String,
        ) {
            val profile = info.elevationProfile ?: return
            val segLen = (info.routeEndM - info.routeStartM).let { if (it.isFinite() && it > 0.0) it else 1.0 }

            // Plot area: silhouette in the top ~52 %, leaving the lower band for gap time + label.
            val margin = w * 0.06f
            val plotLeft = margin
            val plotRight = w - margin
            val plotTop = h * 0.08f
            val plotBottom = h * 0.52f
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            // Distance + altitude extents over the finite points only.
            var minD = Double.POSITIVE_INFINITY
            var maxD = Double.NEGATIVE_INFINITY
            var minA = Double.POSITIVE_INFINITY
            var maxA = Double.NEGATIVE_INFINITY
            for ((d, a) in profile) {
                if (!d.isFinite() || !a.isFinite()) continue
                if (d < minD) minD = d
                if (d > maxD) maxD = d
                if (a < minA) minA = a
                if (a > maxA) maxA = a
            }
            if (!minD.isFinite() || !maxD.isFinite() || !minA.isFinite() || !maxA.isFinite()) {
                // No usable points — degrade to the abstract track render so we never blank a live
                // segment just because the silhouette is unusable.
                drawTrack(canvas, w, h, state, info, neutral, stateColor, timeText, GapDisplay.TIME)
                return
            }
            val dSpan = (maxD - minD).let { if (it > 0.0) it else 1.0 }
            val aSpan = (maxA - minA).let { if (it > 0.0) it else 1.0 }

            fun xOf(d: Double): Float = (plotLeft + plotW * ((d - minD) / dSpan)).toFloat()
            fun yOf(a: Double): Float = (plotBottom - plotH * ((a - minA) / aSpan)).toFloat()

            // Filled silhouette (subtle) + outline.
            profilePath.rewind()
            var started = false
            var firstX = plotLeft
            var lastX = plotRight
            for ((d, a) in profile) {
                if (!d.isFinite() || !a.isFinite()) continue
                val x = xOf(d)
                val y = yOf(a)
                if (!started) {
                    profilePath.moveTo(x, y)
                    firstX = x
                    started = true
                } else {
                    profilePath.lineTo(x, y)
                }
                lastX = x
            }
            if (started) {
                profileLinePaint.color = neutral
                profileLinePaint.strokeWidth = (h * 0.018f).coerceAtLeast(1.5f)
                canvas.drawPath(profilePath, profileLinePaint)
                // Close down to the baseline for a faint fill.
                profilePath.lineTo(lastX, plotBottom)
                profilePath.lineTo(firstX, plotBottom)
                profilePath.close()
                profileFillPaint.color = (0x22 shl 24) or (neutral and 0x00FFFFFF)
                canvas.drawPath(profilePath, profileFillPaint)
            }

            // Marker fractions along the segment (0 = entry, 1 = exit), clamped to the plot.
            val youFrac = (state.progressM / segLen).coerceIn(0.0, 1.0)
            val ghostFrac = (state.ghostProgressM / segLen).coerceIn(0.0, 1.0)
            val youX = (plotLeft + plotW * youFrac).toFloat()
            val ghostX = (plotLeft + plotW * ghostFrac).toFloat()

            val dotR = (h * 0.06f).coerceIn(3f, 12f)
            // Ghost (grey) first so an overlap draws your marker on top.
            canvas.drawCircle(ghostX, elevAtX(profile, ghostFrac, minD, dSpan, ::yOf, plotBottom), dotR, ghostPaint)
            youPaint.color = stateColor
            canvas.drawCircle(youX, elevAtX(profile, youFrac, minD, dSpan, ::yOf, plotBottom), dotR, youPaint)

            // Big gap time below the silhouette.
            textPaint.color = stateColor
            textPaint.textSize = h * 0.26f
            canvas.drawText(timeText, w / 2f, h * 0.74f, textPaint)

            // Segment label at the very bottom.
            drawLabel(canvas, w, h, info.label, neutral)
        }

        /** Interpolates the silhouette y for a segment fraction so the marker sits on the curve. */
        private fun elevAtX(
            profile: List<Pair<Double, Double>>,
            frac: Double,
            minD: Double,
            dSpan: Double,
            yOf: (Double) -> Float,
            baseline: Float,
        ): Float {
            val targetD = minD + dSpan * frac
            var prev: Pair<Double, Double>? = null
            for (pt in profile) {
                if (!pt.first.isFinite() || !pt.second.isFinite()) continue
                if (pt.first >= targetD) {
                    val p = prev
                    if (p == null) return yOf(pt.second)
                    val span = (pt.first - p.first)
                    if (span <= 0.0) return yOf(pt.second)
                    val t = ((targetD - p.first) / span).coerceIn(0.0, 1.0)
                    val a = p.second + (pt.second - p.second) * t
                    return yOf(a)
                }
                prev = pt
            }
            return prev?.let { yOf(it.second) } ?: baseline
        }

        /**
         * Track render (B): the abstract two-dot track (you coloured by status, ghost grey) plus the
         * big gap time, a small distance and the segment label. You are the centred frame of
         * reference; the ghost is offset within a ±[WINDOW_M] window.
         */
        private fun drawTrack(
            canvas: Canvas,
            w: Int,
            h: Int,
            state: GapState,
            info: SegmentInfo,
            neutral: Int,
            stateColor: Int,
            timeText: String,
            gapDisplay: GapDisplay,
        ) {
            val trackCy = h * 0.26f
            val margin = w * 0.06f
            val left = margin
            val right = w - margin
            val span = right - left

            trackPaint.strokeWidth = (h * 0.035f).coerceAtLeast(2f)
            canvas.drawLine(left, trackCy, right, trackCy, trackPaint)

            // gapDistanceM > 0 means you are ahead → ghost sits behind you (to the left).
            val frac = (-state.gapDistanceM / WINDOW_M).coerceIn(-1.0, 1.0)
            val youX = left + span * 0.5f
            val ghostX = (left + span * (0.5 + 0.5 * frac)).toFloat()

            val dotR = (h * 0.06f).coerceIn(3f, 12f)
            canvas.drawCircle(ghostX, trackCy, dotR, ghostPaint)
            youPaint.color = stateColor
            canvas.drawCircle(youX, trackCy, dotR, youPaint)

            // Big gap time; small distance beneath when the rider asked for BOTH/DISTANCE.
            val distText = fmtDistance(state.gapDistanceM)
            when (gapDisplay) {
                GapDisplay.DISTANCE -> {
                    textPaint.color = stateColor
                    textPaint.textSize = h * 0.26f
                    canvas.drawText(distText, w / 2f, h * 0.60f, textPaint)
                }
                GapDisplay.BOTH -> {
                    textPaint.color = stateColor
                    textPaint.textSize = h * 0.24f
                    canvas.drawText(timeText, w / 2f, h * 0.56f, textPaint)
                    hintPaint.color = stateColor
                    hintPaint.textSize = h * 0.15f
                    canvas.drawText(distText, w / 2f, h * 0.74f, hintPaint)
                }
                GapDisplay.TIME -> {
                    textPaint.color = stateColor
                    textPaint.textSize = h * 0.26f
                    canvas.drawText(timeText, w / 2f, h * 0.60f, textPaint)
                }
            }

            drawLabel(canvas, w, h, info.label, neutral)
        }

        /** Draws the segment label centred at the very bottom in the neutral colour. */
        private fun drawLabel(canvas: Canvas, w: Int, h: Int, label: String, neutral: Int) {
            if (label.isBlank()) return
            labelPaint.color = neutral
            labelPaint.textSize = h * 0.13f
            canvas.drawText(label, w / 2f, h * 0.95f, labelPaint)
        }

        private companion object {
            /** Half-width of the abstract track window in metres: ghost is clamped to ±this. */
            const val WINDOW_M = 120.0
        }
    }
}
