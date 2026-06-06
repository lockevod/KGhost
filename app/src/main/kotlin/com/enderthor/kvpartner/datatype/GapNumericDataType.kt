package com.enderthor.kvpartner.datatype

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GapDisplay
import com.enderthor.kvpartner.engine.GapDisplayLogic
import com.enderthor.kvpartner.engine.GapState
import com.enderthor.kvpartner.engine.GapStateHolder
import com.enderthor.kvpartner.engine.GapStatus
import com.enderthor.kvpartner.engine.RenderPrefs
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Neutral placeholder shown when there is no data or the value is non-finite. */
internal const val GAP_PLACEHOLDER = "---"

/**
 * Numeric gap data field — the simpler "option A" readout.
 *
 * Renders the time/distance gap to the virtual partner as plain text. The engine uses a
 * mathematical sign convention (ahead ⇒ gapTimeS negative), so this field flips the sign
 * for human-readable display: ahead shows `+M:SS` in green, behind shows `-M:SS` in red.
 *
 * When the gap is not [GapState.active] or is [GapState.stale] the field shows `---` in the
 * neutral day/night text colour (NOT a disabled grey) — the rider is waiting for / cannot trust
 * data, the field is not turned off. Staleness is speed-gated upstream (see
 * [com.enderthor.kvpartner.engine.StalenessLogic]): a legitimate stop (the ghost keeps moving)
 * stays trustworthy and visible, while a distance frozen WHILE moving (real GPS loss, e.g. a
 * tunnel) is marked stale so the field honestly blanks instead of showing a wrong, snapping gap.
 *
 * This is a passive readout, so there is no tap PendingIntent — it just observes
 * [GapStateHolder.state] (combined with the rider's [GapDisplay] preference from [RenderPrefs])
 * and emits a RemoteViews per distinct change.
 */
class GapNumericDataType(
    private val context: Context,
) : DataTypeImpl("kvpartner", "kvpartner-gap-num") {

    /**
     * Tracks the coroutine scope of the currently active view so a re-entrant [startView]
     * (the Karoo host can call it again for the same field) cancels the previous scope first,
     * avoiding two render loops fighting over the same emitter.
     */
    @Volatile
    private var activeScopeJob: Job? = null

    private companion object {
        const val PLACEHOLDER = "---"

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
            stale = false,
            active = true,
        )
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Re-entry guard: cancel any previous render scope before starting a new one.
        activeScopeJob?.cancel()

        val scopeJob = Job()
        activeScopeJob = scopeJob
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        // Seed one frame synchronously with the night-aware neutral text colour, so the field is
        // never white-on-white (invisible) in day mode during the pre-first-emission window.
        // karoo-ext 1.1.9's ViewEmitter.updateView hard-drops any call within 900 ms of the
        // previous one (keeping the EARLIER one), so the collect coroutine's first real frame
        // (a few ms later) would be silently dropped — on mid-ride page re-entry that left the
        // field showing `---` for ~1 s while a valid gap existed. Seeding with the LIVE current
        // state (the same values the dropped frame would carry) makes that drop harmless.
        emitter.updateView(
            buildView(
                if (config.preview) DEMO_STATE else GapStateHolder.state.value,
                RenderPrefs.gapDisplay.value,
            ),
        )

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                combine(GapStateHolder.state, RenderPrefs.gapDisplay) { state, gapDisplay -> state to gapDisplay }
                    .distinctUntilChanged()
                    .collect { (liveState, gapDisplay) ->
                        // In preview (profile editor gallery) render a synthetic demo state so the
                        // field shows a meaningful sample instead of the inactive `---` placeholder.
                        val state = if (config.preview) DEMO_STATE else liveState
                        emitter.updateView(buildView(state, gapDisplay))
                    }
            } catch (_: CancellationException) {
                // normal — field removed from the page.
            } catch (e: Exception) {
                Timber.e(e, "GapNumericDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    private fun buildView(state: GapState, gapDisplay: GapDisplay): RemoteViews {
        val dark = context.isKarooNightMode()
        val neutral = if (dark) Color.WHITE else Color.BLACK
        val neutralHint = if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()

        // Waiting for data: neutral `---`, NOT a disabled grey. We blank on !active (no target /
        // not recording / no first data) AND on state.stale. Staleness is now speed-gated upstream:
        // a legitimate stop (speed < 0.5 m/s) stays trustworthy and visible, while a frozen
        // distance WHILE moving (real GPS loss, e.g. a tunnel) is marked stale → honest `---`
        // instead of a believable-but-wrong gap that snaps on recovery.
        val waiting = !state.active || state.stale

        val main: String
        val hint: String
        val mainColor: Int
        val hintColor: Int

        if (waiting) {
            main = PLACEHOLDER
            hint = ""
            mainColor = neutral
            hintColor = neutralHint
        } else {
            // Three-state classification with a small epsilon so an exactly-on-pace gap renders
            // neutral (no sign, day/night colour) rather than a misleading green "+0:00".
            val status = GapDisplayLogic.gapStatus(state.gapTimeS)
            val stateColor = when (status) {
                GapStatus.NEUTRAL -> neutral
                GapStatus.AHEAD -> ahead()
                GapStatus.BEHIND -> behind()
            }
            val timeText = fmtTime(state.gapTimeS, status)
            val distText = fmtDistance(state.gapDistanceM)
            when (gapDisplay) {
                GapDisplay.TIME -> {
                    main = timeText
                    hint = ""
                }
                GapDisplay.DISTANCE -> {
                    main = distText
                    hint = ""
                }
                GapDisplay.BOTH -> {
                    main = timeText
                    hint = distText
                }
            }
            mainColor = stateColor
            hintColor = stateColor
        }

        return RemoteViews(context.packageName, R.layout.field_numeric).apply {
            setTextViewText(R.id.field_main, main)
            setTextViewText(R.id.field_hint, hint)
            setViewVisibility(R.id.field_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_main, "setGravity", Gravity.CENTER)
            setInt(R.id.field_hint, "setGravity", Gravity.CENTER)
            setTextColor(R.id.field_main, mainColor)
            setTextColor(R.id.field_hint, hintColor)
        }
    }

    private fun ahead(): Int = context.getColor(R.color.gap_ahead)
    private fun behind(): Int = context.getColor(R.color.gap_behind)
}

/**
 * Formats the gap as a time string. For [GapStatus.NEUTRAL] (on-pace) it shows the magnitude
 * with NO leading sign ("0:00"); for AHEAD/BEHIND it flips the engine's mathematical sign so
 * being ahead reads positive on screen ("+0:20" when 20 s ahead, "-0:20" when 20 s behind).
 *
 * Gaps under one hour render as `M:SS` (unbounded was ambiguous, e.g. "-90:00"); a gap of an hour
 * or more rolls over to `H:MM:SS` (e.g. "-1:30:00"). The sign and NEUTRAL (no-sign) rules are
 * preserved in both cases.
 */
internal fun fmtTime(gapTimeS: Double, status: GapStatus = GapDisplayLogic.gapStatus(gapTimeS)): String {
    // Guard non-finite gap (NaN/±Inf): toInt() on those is undefined/garbage — render the
    // neutral placeholder instead.
    if (!gapTimeS.isFinite()) return GAP_PLACEHOLDER
    val a = abs(gapTimeS).toInt()
    val sign = when (status) {
        GapStatus.NEUTRAL -> ""
        GapStatus.AHEAD -> "+"
        GapStatus.BEHIND -> "-"
    }
    return if (a >= 3600) {
        String.format(Locale.US, "%s%d:%02d:%02d", sign, a / 3600, (a % 3600) / 60, a % 60)
    } else {
        String.format(Locale.US, "%s%d:%02d", sign, a / 60, a % 60)
    }
}

/**
 * Formats the gap distance in metres. The value is rounded ONCE to the nearest metre and both the
 * dead-band and the magnitude derive from that rounded value: an exact 0 m renders with NO leading
 * sign ("0 m"), otherwise it shows an explicit sign so ahead reads positive ("+120 m"). The engine
 * already uses positive = ahead for distance.
 */
internal fun fmtDistance(gapDistanceM: Double): String {
    // Guard non-finite gap (NaN/±Inf): roundToInt() on those throws/overflows — render the
    // neutral placeholder instead.
    if (!gapDistanceM.isFinite()) return GAP_PLACEHOLDER
    // Round ONCE and derive both the dead-band and the magnitude from the same rounded value, so
    // sign and magnitude can never disagree (e.g. "2 m" unsigned while 1.9 m behind).
    val m = gapDistanceM.roundToInt()
    val sign = when {
        m == 0 -> ""
        m > 0 -> "+"
        else -> "-"
    }
    return String.format(Locale.US, "%s%d m", sign, abs(m))
}
