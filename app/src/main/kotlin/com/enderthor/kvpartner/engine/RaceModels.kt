package com.enderthor.kvpartner.engine

/** Which stored ghost to race when multiple past tracks cover the same stretch. */
enum class GhostPick { BEST, LAST }

/** A stretch of the loaded route that overlaps recorded history, with its ghost curve. */
data class LiveSegment(
    val routeStartM: Double,
    val routeEndM: Double,
    val ghost: GhostCurve,
    val ghostLabel: String,
    val hasElevation: Boolean,
    val elevationProfile: List<Pair<Double, Double>>?,
)

/** Render-only subset of [LiveSegment] published to the data field. */
data class SegmentInfo(
    val routeStartM: Double,
    val routeEndM: Double,
    val label: String,
    val hasElevation: Boolean,
    val elevationProfile: List<Pair<Double, Double>>?,
)

/** Maps a [LiveSegment] to its render-only [SegmentInfo] (drops the ghost curve). */
fun LiveSegment.toInfo() = SegmentInfo(routeStartM, routeEndM, ghostLabel, hasElevation, elevationProfile)
