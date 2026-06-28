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

    /** Max distance (m) from a route node to a track sample for it to count as "this spot". Recall
     *  plateaus by ~18 m on real data; the tight end maximises precision against parallel roads. */
    const val MATCH_RADIUS_M = 18.0

    /** Max heading difference (deg) between a sample and the route's local bearing. Separates
     *  uphill/downhill without losing recall (97 % vs 98 % direction-agnostic on real data). */
    const val BEARING_TOL_DEG = 45.0

    /** Max along-segment spacing (m) of the interpolated pace anchors. Decoupling coverage from the
     *  track's native point spacing: a single END-only anchor needs point spacing <= ~36 m (2×radius)
     *  to keep 25 m nodes covered, which the 20 m decimation FLOOR does not guarantee; placing anchors
     *  every <= 12 m along each segment makes any node within radius of the ridden line match. */
    const val ANCHOR_SPACING_M = 12.0

    /** A straight-line jump (m) between two consecutive recorded points longer than this is a GPS
     *  dropout / decimation gap, not riding — skip it (don't fabricate interpolated anchors across
     *  terrain the rider never rode in a straight line; that would spread a made-up pace to many nodes). */
    const val DROPOUT_GAP_M = 200.0

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
        val latStep = MATCH_RADIUS_M / 111_320.0
        val lngStep = MATCH_RADIUS_M / kotlin.math.max(1.0, 111_320.0 * cos(Math.toRadians(refLat)))
        fun ci(lat: Double) = floor(lat / latStep).toInt()
        fun cj(lng: Double) = floor(lng / lngStep).toInt()
        fun cellKey(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

        val grid = HashMap<Long, MutableList<Sample>>()
        for (track in tracks) {
            val pts = track.points
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val d = b.distanceM - a.distanceM
                val dt = b.timeS - a.timeS
                if (d <= 0.0 || dt <= 0.0) continue
                val speed = d / dt
                if (speed > AGG_MAX_SPEED_MS) continue
                var timePerM = dt / d
                if (speed < AGG_MIN_SPEED_MS) timePerM = 1.0 / AGG_MIN_SPEED_MS
                if (!timePerM.isFinite()) continue
                // Anchor count/placement is based on the GEODESIC CHORD between the two recorded points,
                // not the odometer delta d: d follows the road's curves and on a GPS-dropout segment can be
                // huge while the chord is what we actually interpolate along. A chord longer than
                // DROPOUT_GAP_M is a dropout/gap → skip (its pace is real but the straight-line path is not).
                val chord = Polyline.haversineM(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
                if (chord > DROPOUT_GAP_M) continue
                val bearing = Polyline.bearingDeg(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
                // Densify: place anchors ALONG the segment at <= ANCHOR_SPACING_M, each carrying this
                // segment's pace + bearing, so coverage does NOT depend on the track's native point
                // spacing (imported / smart-recording / fast-descent tracks can be 40-60 m apart, which a
                // single END-only anchor + 18 m radius would leave 25 m nodes uncovered → false Ghost-Pace).
                // f runs (0,1]: the END point (f=1) is always emitted, so a node sitting exactly on a 25 m
                // boundary still matches the segment ENDING there (nearest-per-track dedup makes that exact
                // 0 m hit win); the START (f=0, owned by the previous segment) is never emitted.
                val subAnchors = kotlin.math.max(1, kotlin.math.ceil(chord / ANCHOR_SPACING_M).toInt())
                for (sIdx in 1..subAnchors) {
                    val f = sIdx.toDouble() / subAnchors
                    val sLat = a.lat + f * (b.lat - a.lat)
                    val sLng = a.lng + f * (b.lng - a.lng)
                    grid.getOrPut(cellKey(ci(sLat), cj(sLng))) { ArrayList() }
                        .add(Sample(sLat, sLng, bearing, timePerM, track.id, track.startedAtEpoch))
                }
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
                    if (dist > MATCH_RADIUS_M) continue
                    if (Polyline.bearingDiffDeg(s.bearingDeg, rs.bearingDeg) > BEARING_TOL_DEG) continue
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
