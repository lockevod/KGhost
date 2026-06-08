package com.enderthor.kvpartner.datatype

import android.content.Context
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GapDisplay
import com.enderthor.kvpartner.engine.GapDisplayLogic
import com.enderthor.kvpartner.engine.GapState
import com.enderthor.kvpartner.engine.GapStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared gap formatting + colour/sign helpers used by both gap data fields
 * ([GapNumericDataType] and [GapGraphicDataType]). Kept in one place so the
 * sign/neutral rules, the `M:SS`/`H:MM:SS` rollover, the distance rounding and the
 * [GapStatus]-driven colour selection have exactly one implementation (DRY).
 *
 * The pure string helpers ([fmtTime]/[fmtDistance]) are intentionally free of Android types and
 * live as top-level `internal` functions (covered by `FmtTimeTest`); the colour helper takes a
 * [Context] for the day/night-aware ahead/behind/neutral resources.
 */

/** Neutral placeholder shown when there is no data or the value is non-finite. */
internal const val GAP_PLACEHOLDER = "---"

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

/**
 * Resolves the display colour for a [GapStatus]: the day/night-aware neutral for on-pace
 * ([GapStatus.NEUTRAL]), a green when ahead and a red when behind. ALL three states are
 * day/night-aware so they stay sunlight-readable: the caller passes the same [dark] flag it used to
 * pick [neutral] (white-on-black night / black-on-white day), and ahead/behind likewise select the
 * bright `*_night` hues on the black night background or the darkened `*_day` hues on the white day
 * background (the bright originals were nearly invisible on white in direct sun). The same
 * three-state colour is used for both the marker/dot hue and the gap text across all fields.
 *
 * @param neutral the already-resolved neutral colour for the current day/night mode.
 * @param dark the current day/night mode (the SAME flag used to pick [neutral]): true = night.
 */
internal fun Context.gapStatusColor(status: GapStatus, neutral: Int, dark: Boolean): Int = when (status) {
    GapStatus.NEUTRAL -> neutral
    GapStatus.AHEAD -> getColor(if (dark) R.color.gap_ahead_night else R.color.gap_ahead_day)
    GapStatus.BEHIND -> getColor(if (dark) R.color.gap_behind_night else R.color.gap_behind_day)
}

/**
 * Day/night-aware amber used to render the gap value while it is a dead-reckoned ESTIMATE during a
 * prolonged GPS loss ([com.enderthor.kvpartner.engine.GapState.estimated]). Signals "extrapolated,
 * not measured" without blanking. Bright amber on the black night background, darker amber on the
 * white day background (sunlight contrast). Overrides the green/red status hue while estimating.
 */
internal fun Context.gapEstimateColor(dark: Boolean): Int =
    getColor(if (dark) R.color.gap_estimate_night else R.color.gap_estimate_day)

/**
 * Cheap equality key for a field's RENDERED output: two [GapState]s that map to the same key produce
 * a pixel-identical field, so the (expensive) Canvas redraw + the `updateView` IPC can both be skipped
 * for a change emission that doesn't reach the screen. The continuous gap doubles are quantized to
 * exactly what is displayed — whole seconds ([fmtTime]) and whole metres ([fmtDistance]) — plus the
 * flags that drive colour/placeholder. Non-finite values collapse to a sentinel so they don't churn.
 *
 * [segKey]/[youM]/[ghostM]/[hasElev] are only used by the segment field (its markers move on the
 * elevation silhouette); numeric/graphic leave them at their defaults.
 */
internal data class GapRenderKey(
    val active: Boolean,
    val estimated: Boolean,
    val status: GapStatus,
    val timeSec: Int,
    val distM: Int,
    val gapDisplay: GapDisplay,
    val dark: Boolean,
    val isRoute: Boolean = false,
)

private fun Double.toQuantOrSentinel(): Int = if (isFinite()) roundToInt() else Int.MIN_VALUE

/**
 * Render key for the gap data fields. [dark] (the day/night mode) MUST be part of the key: the colours
 * and the day/night background are chosen by it, so a mode flip while the gap value is static has to
 * force a redraw — otherwise the cached frame keeps the wrong scheme (worst case an inactive `---`
 * going white-on-white) until the next value change.
 */
internal fun gapRenderKey(state: GapState, gapDisplay: GapDisplay, isRoute: Boolean, dark: Boolean): GapRenderKey {
    if (!state.active) {
        return GapRenderKey(false, false, GapStatus.NEUTRAL, 0, 0, gapDisplay, dark, isRoute)
    }
    return GapRenderKey(
        active = true,
        estimated = state.estimated,
        status = GapDisplayLogic.gapStatus(state.gapTimeS),
        timeSec = if (state.gapTimeS.isFinite()) state.gapTimeS.toInt() else Int.MIN_VALUE,
        distM = state.gapDistanceM.toQuantOrSentinel(),
        gapDisplay = gapDisplay,
        dark = dark,
        isRoute = isRoute,
    )
}
