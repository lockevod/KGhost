package com.enderthor.kvpartner.engine

/** Source of the ghost: produces the (distance, time) curve to compare against. */
interface GhostSource {
    fun curve(): GhostCurve
    val label: String
}

/**
 * Ghost at a constant speed of [targetSpeedMs] (m/s). Produces a 2-point linear curve
 * covering up to 1,000,000 m — sufficient for any ride. Special case of spec model C.
 *
 * @param targetSpeedMs Target speed in metres per second; must be > 0.
 * @param label Human-readable name shown in the UI.
 */
class VirtualPartnerSource(
    val targetSpeedMs: Double,
    override val label: String = "Virtual Partner",
) : GhostSource {
    init { require(targetSpeedMs > 0.0) { "targetSpeedMs must be > 0" } }

    private val maxDistanceM = 1_000_000.0

    override fun curve(): GhostCurve = GhostCurve(
        listOf(
            GhostSample(0.0, 0.0),
            GhostSample(maxDistanceM, maxDistanceM / targetSpeedMs),
        )
    )
}
