package com.enderthor.kghost.engine

import com.enderthor.kghost.geo.LatLng
import com.enderthor.kghost.geo.Polyline
import com.enderthor.kghost.geo.PolylinePath
import com.enderthor.kghost.geo.RecordedTrack
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/**
 * Builds a per-route [PerRouteAggregate] from the rider's WHOLE history by matching each 25 m route
 * node to the pace samples of every track that passed through that spot IN THAT DIRECTION — not by
 * projecting tracks as forward laps of this exact route. This is what makes AVERAGE reflect "my pace
 * on this road across all rides" instead of "laps of this one route" (which, for a varied history,
 * is almost always a single recording -> permanent LAST/GP). See
 * docs/superpowers/specs/2026-06-28-corridor-pace-model-design.md.
 *
 * Each track segment is anchored not just at its END point but densified into anchors placed ALONG
 * the segment at <= [ANCHOR_SPACING_M], so corridor coverage is independent of the track's native
 * point spacing (coarse / imported / fast-descent tracks no longer leave 25 m nodes uncovered).
 *
 * Pure (no Android / no IO). The output is identical in shape to the old seed, so [toLiveSegments],
 * [RouteGhost], the tick and the picks are unchanged.
 */
object CorridorSeeder {

    /** Max candidate tracks the seed folds, ranked by route overlap. Far above a normal rider's
     *  overlapping-track count (so a no-op for typical use), it bounds seed parse cost + the stored id
     *  set on dense areas where hundreds of rides cross a route's bbox. Much larger than the old top-12
     *  cap, so it does NOT reintroduce the coverage starvation that the corridor model fixed. */
    const val MAX_CANDIDATES = 250

    private data class Sample(
        val lat: Double, val lng: Double,
        val bearingDeg: Double, val timePerM: Double,
        val trackId: String, val epoch: Long,
    )

    fun seed(
        routeKey: String,
        routeName: String,
        route: PolylinePath,
        tracks: List<RecordedTrack>,
        stepM: Double = AGG_STEP_M,
    ): PerRouteAggregate {
        val nodeCount = (floor(route.totalM / stepM) + 1).toInt().coerceAtLeast(1)
        val nodes = Array(nodeCount) { AggregateNode() }
        if (route.points.size < 2) {
            return PerRouteAggregate(routeKey, routeName, route.totalM, stepM, AGG_SCHEMA_VERSION, nodes.asList())
        }

        val refLat = route.points.first().lat
        val latStep = TrackSamples.MATCH_RADIUS_M / 111_320.0
        val lngStep = TrackSamples.MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * cos(Math.toRadians(refLat)))
        fun ci(lat: Double) = floor(lat / latStep).toInt()
        fun cj(lng: Double) = floor(lng / lngStep).toInt()
        fun cellKey(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

        val grid = HashMap<Long, MutableList<Sample>>()
        for (track in tracks) {
            TrackSamples.forEach(track) { s ->
                grid.getOrPut(cellKey(ci(s.lat), cj(s.lng))) { ArrayList() }
                    .add(Sample(s.lat, s.lng, s.bearingDeg, s.timePerM, s.trackId, s.epoch))
            }
        }

        for (k in 1 until nodeCount) {
            val rs = route.sampleAt(k * stepM)
            val baseI = ci(rs.location.lat); val baseJ = cj(rs.location.lng)
            val nearestByTrack = HashMap<String, Sample>()
            val nearestDistByTrack = HashMap<String, Double>()
            for (di in -1..1) for (dj in -1..1) {
                val bucket = grid[cellKey(baseI + di, baseJ + dj)] ?: continue
                for (s in bucket) {
                    val dist = Polyline.haversineM(rs.location, LatLng(s.lat, s.lng))
                    if (dist > TrackSamples.MATCH_RADIUS_M) continue
                    if (Polyline.bearingDiffDeg(s.bearingDeg, rs.bearingDeg) > TrackSamples.BEARING_TOL_DEG) continue
                    val cur = nearestDistByTrack[s.trackId]
                    if (cur == null || dist < cur) {
                        nearestDistByTrack[s.trackId] = dist
                        nearestByTrack[s.trackId] = s
                    }
                }
            }
            if (nearestByTrack.isEmpty()) continue
            val ordered = nearestByTrack.values.sortedBy { it.epoch }
            var ema = 0.0; var minTpm = 0.0; var lastTpm = 0.0; var cnt = 0
            for (s in ordered) {
                val tpm = s.timePerM
                ema = when {
                    cnt == 0 -> tpm
                    cnt < AGG_SEED_LAPS -> (ema * cnt + tpm) / (cnt + 1)
                    else -> AGG_ALPHA * tpm + (1.0 - AGG_ALPHA) * ema
                }
                minTpm = if (cnt == 0) tpm else min(minTpm, tpm)
                lastTpm = tpm
                cnt++
            }
            nodes[k] = AggregateNode(
                dtS = stepM * ema,
                count = cnt,
                minDtS = stepM * minTpm,
                lastDtS = stepM * lastTpm,
            )
        }

        return PerRouteAggregate(
            routeKey = routeKey,
            routeName = routeName,
            routeLenM = route.totalM,
            stepM = stepM,
            schemaVersion = AGG_SCHEMA_VERSION,
            nodes = nodes.asList(),
        )
    }
}
