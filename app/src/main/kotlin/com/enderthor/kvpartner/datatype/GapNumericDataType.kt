package com.enderthor.kvpartner.datatype

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Numeric gap data field — the simpler "option A" readout.
 *
 * Renders the time/distance gap to the virtual partner as plain text. The engine uses a
 * mathematical sign convention (ahead ⇒ gapTimeS negative), so this field flips the sign
 * for human-readable display: ahead shows `+M:SS` in green, behind shows `-M:SS` in red.
 *
 * When the gap is not [GapState.active] or the source is [GapState.stale], the field shows
 * `---` in the neutral day/night text colour (NOT a disabled grey) — the rider is waiting
 * for data, the field is not turned off.
 *
 * This is a passive readout, so there is no tap PendingIntent — it just observes
 * [GapStateHolder.state] (combined with the rider's [GapDisplay] preference) and emits a
 * RemoteViews per distinct change.
 */
class GapNumericDataType(
    private val context: Context,
) : DataTypeImpl("kvpartner", "kvpartner-gap-num") {

    private val configManager = ConfigurationManager(context)

    private companion object {
        const val PLACEHOLDER = "---"
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scopeJob = Job()
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
                    .collect { (state, gapDisplay) -> emitter.updateView(buildView(state, gapDisplay)) }
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

        // Waiting for data: neutral `---`, NOT a disabled grey.
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
            val stateColor = if (state.ahead) ahead() else behind()
            val timeText = fmtTime(state.gapTimeS)
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
 * Formats the gap as a signed time string, flipping the engine's mathematical sign so that
 * being ahead reads as a positive number on screen ("+0:20" when 20 s ahead).
 */
internal fun fmtTime(gapTimeS: Double): String {
    val shown = -gapTimeS // ahead (gapTimeS < 0) → positive on screen
    val sign = if (shown >= 0) "+" else "-"
    val a = abs(shown).toInt()
    return String.format(Locale.US, "%s%d:%02d", sign, a / 60, a % 60)
}

/**
 * Formats the gap distance in metres, presenting an explicit sign so ahead reads positive
 * ("+120 m" when 120 m ahead). The engine already uses positive = ahead for distance.
 */
internal fun fmtDistance(gapDistanceM: Double): String {
    val m = gapDistanceM.roundToInt()
    val sign = if (m >= 0) "+" else "-"
    return String.format(Locale.US, "%s%d m", sign, abs(m))
}
