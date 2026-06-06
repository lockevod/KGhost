package com.enderthor.kvpartner.datatype

import android.content.Context
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.engine.GapDisplayLogic
import com.enderthor.kvpartner.engine.GapStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared gap formatting + colour/sign helpers used by ALL gap data fields
 * ([GapNumericDataType], [GapGraphicDataType] and [SegmentGapDataType]). Kept in one place so the
 * sign/neutral rules, the `M:SS`/`H:MM:SS` rollover, the distance rounding and the
 * [GapStatus]-driven colour selection have exactly one implementation (DRY).
 *
 * The pure string helpers ([fmtTime]/[fmtDistance]) are intentionally free of Android types and
 * live as top-level `internal` functions (covered by `FmtTimeTest`); the colour helper takes a
 * [Context] for the day/night-aware neutral and the `gap_ahead`/`gap_behind` resources.
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
 * ([GapStatus.NEUTRAL]), `gap_ahead` (green) when ahead and `gap_behind` (red) when behind. The
 * same three-state colour is used for both the marker/dot hue and the gap text across all fields.
 *
 * @param neutral the already-resolved neutral colour for the current day/night mode.
 */
internal fun Context.gapStatusColor(status: GapStatus, neutral: Int): Int = when (status) {
    GapStatus.NEUTRAL -> neutral
    GapStatus.AHEAD -> getColor(R.color.gap_ahead)
    GapStatus.BEHIND -> getColor(R.color.gap_behind)
}
