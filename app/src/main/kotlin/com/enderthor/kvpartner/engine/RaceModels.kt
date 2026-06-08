package com.enderthor.kvpartner.engine

/** Which stored ghost to race when multiple past tracks cover the same stretch. */
enum class GhostPick { BEST, LAST }

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
 * Virtual Partner (VP). Carries no gap/elevation data — the gap itself comes from [GapStateHolder].
 */
data class SegmentInfo(
    val routeStartM: Double,
    val routeEndM: Double,
    val label: String,
)

/** Maps a [LiveSegment] to its render-only [SegmentInfo] (drops the ghost curve). */
fun LiveSegment.toInfo() = SegmentInfo(routeStartM, routeEndM, ghostLabel)
