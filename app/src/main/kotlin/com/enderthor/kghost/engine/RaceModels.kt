package com.enderthor.kghost.engine

import kotlinx.serialization.Serializable

/**
 * Which stored ghost to race when multiple past tracks cover the same stretch.
 *
 * [BEST]/[LAST] are resolved per overlapping group by [com.enderthor.kghost.geo.SegmentMatcher].
 * [AVERAGE] is different: it bypasses the matcher entirely and races the per-route EMA aggregate
 * (mean of recent laps of the loaded route — see [RouteAggregate]); the extension only falls back to
 * the matcher (as BEST) while the aggregate is still warming up.
 */
@Serializable
enum class GhostPick { BEST, LAST, AVERAGE }

/**
 * Formats seconds as `m:ss` (Locale.US) — the shared ghost-label time format ("PR 4:32",
 * "Last 5:01", "AVG 4:48"). One implementation so the labels can never drift apart.
 */
internal fun mmss(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
}

/** A stretch of the loaded route that overlaps recorded history, with its ghost curve. */
data class LiveSegment(
    val routeStartM: Double,
    val routeEndM: Double,
    val ghost: GhostCurve,
    val ghostLabel: String,
)

/**
 * Render-only identity of the currently-active recorded stretch. Published to [SegmentInfoHolder] so
 * the gap data fields can tell "racing your past self on a recorded stretch" (SEG) from the fixed-pace
 * Ghost Pace (VP). Carries no gap/elevation data — the gap itself comes from [GapStateHolder].
 */
data class SegmentInfo(
    val routeStartM: Double,
    val routeEndM: Double,
    val label: String,
)

/** Maps a [LiveSegment] to its render-only [SegmentInfo] (drops the ghost curve). */
fun LiveSegment.toInfo() = SegmentInfo(routeStartM, routeEndM, ghostLabel)
