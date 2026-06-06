package com.enderthor.kvpartner.geo

import kotlinx.serialization.Serializable

/** One recorded sample: position + cumulative ride distance (m) + elapsed time (s). */
data class TrackPoint(val lat: Double, val lng: Double, val distanceM: Double, val timeS: Double)

/** Serializable DTO (kept separate so the in-memory model can evolve independently). */
@Serializable
data class TrackPointDto(val lat: Double, val lng: Double, val distanceM: Double, val timeS: Double)

/** A persisted ride track. */
@Serializable
data class RecordedTrack(val id: String, val startedAtEpoch: Long, val points: List<TrackPointDto>)

fun TrackPoint.toDto() = TrackPointDto(lat, lng, distanceM, timeS)
fun TrackPointDto.toModel() = TrackPoint(lat, lng, distanceM, timeS)

/**
 * Stateful distance-based decimator: keeps a sample only when it is at least [minSpacingM] metres
 * (by cumulative ride distance) from the last kept sample. Pure (no Android), so it is unit-tested
 * directly; [TrackRecorder] drives it from the live streams.
 */
class TrackDecimator(private val minSpacingM: Double = 20.0) {
    private var lastKeptDistanceM: Double? = null

    fun shouldKeep(lat: Double, lng: Double, distanceM: Double): Boolean {
        val last = lastKeptDistanceM
        if (last == null || distanceM - last >= minSpacingM) {
            lastKeptDistanceM = distanceM
            return true
        }
        return false
    }

    fun reset() {
        lastKeptDistanceM = null
    }
}
