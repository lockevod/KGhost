package com.enderthor.kvpartner.datatype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.widget.RemoteViews
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GapDisplay
import com.enderthor.kvpartner.engine.GapState
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.managers.ConfigurationManager
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
import kotlinx.coroutines.flow.map
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
 * When the gap is not [GapState.active] or the source is [GapState.stale], the field draws a
 * neutral `---` (day/night colour, NOT a disabled grey) — waiting for data, not turned off.
 *
 * Passive readout, so there is no tap PendingIntent. All `Paint`/`Rect`/`RectF` are
 * pre-allocated at class level and reused per frame (no per-frame allocation). The bitmap is
 * the only per-frame allocation, sized from [ViewConfig.viewSize] (with a sensible fallback).
 */
class GapGraphicDataType(
    private val context: Context,
) : DataTypeImpl("kvpartner", "kvpartner-gap") {

    private val configManager = ConfigurationManager(context)

    /**
     * Tracks the coroutine scope of the currently active view so a re-entrant [startView]
     * (the Karoo host can call it again for the same field) cancels the previous scope first,
     * avoiding two render loops fighting over the same emitter. KSafe's data fields cancel via
     * `setCancellable`; we additionally guard re-entry here as the plan calls out.
     */
    @Volatile
    private var activeScopeJob: Job? = null

    private companion object {
        const val PLACEHOLDER = "---"

        /** Half-width of the visible window in metres: ghost is clamped to ±this around you. */
        const val WINDOW_M = 120.0

        /** Fallback bitmap size when [ViewConfig.viewSize] is unavailable or non-positive. */
        const val FALLBACK_W = 200
        const val FALLBACK_H = 120

        /** Upper bound so an oversized field can't allocate a huge bitmap each frame. */
        const val MAX_W = 480
        const val MAX_H = 320
    }

    // ── Pre-allocated drawing primitives (reused every frame — no per-frame allocation) ──
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF888888.toInt()
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
    private val textBounds = Rect()

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

        val viewJob = scope.launch {
            try {
                val display = configManager.loadConfigFlow().map { it.gapDisplay }
                combine(GapStateHolder.state, display) { state, gapDisplay -> state to gapDisplay }
                    .distinctUntilChanged()
                    .collect { (state, gapDisplay) ->
                        val (w, h) = bitmapSize(config)
                        val bmp = drawFrame(w, h, state, gapDisplay)
                        val rv = RemoteViews(context.packageName, R.layout.field_gap)
                        rv.setImageViewBitmap(R.id.field_gap_image, bmp)
                        emitter.updateView(rv)
                    }
            } catch (_: CancellationException) {
                // normal — field removed from the page.
            } catch (e: Exception) {
                Timber.e(e, "GapGraphicDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    /** Derives a bounded bitmap size from the field's [ViewConfig.viewSize]. */
    private fun bitmapSize(config: ViewConfig): Pair<Int, Int> {
        val w = config.viewSize.first.takeIf { it > 0 } ?: FALLBACK_W
        val h = config.viewSize.second.takeIf { it > 0 } ?: FALLBACK_H
        return w.coerceIn(1, MAX_W) to h.coerceIn(1, MAX_H)
    }

    private fun drawFrame(w: Int, h: Int, state: GapState, gapDisplay: GapDisplay): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(context.getColor(R.color.gap_bg))

        val dark = context.isKarooNightMode()
        val neutral = if (dark) Color.WHITE else Color.BLACK

        val waiting = !state.active || state.stale
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

        // Track bar.
        trackPaint.strokeWidth = (h * 0.04f).coerceAtLeast(2f)
        canvas.drawLine(left, trackCy, right, trackCy, trackPaint)

        // Your dot fixed at centre; ghost offset by gapDistanceM within ±WINDOW_M.
        // gapDistanceM > 0 means you are ahead → ghost is behind you (to the left).
        val frac = (-state.gapDistanceM / WINDOW_M).coerceIn(-1.0, 1.0) // -1..1, 0 = centre
        val youX = left + span * 0.5f
        val ghostX = (left + span * (0.5 + 0.5 * frac)).toFloat()

        val dotR = (h * 0.07f).coerceIn(3f, 14f)
        // Ghost dot (grey) first so an overlap draws your dot on top.
        canvas.drawCircle(ghostX, trackCy, dotR, ghostPaint)
        youPaint.color = if (state.ahead) context.getColor(R.color.gap_ahead)
        else context.getColor(R.color.gap_behind)
        canvas.drawCircle(youX, trackCy, dotR, youPaint)

        // Gap text below the track.
        val timeText = fmtTime(state.gapTimeS)
        val distText = fmtDistance(state.gapDistanceM)
        val textColor = youPaint.color
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
