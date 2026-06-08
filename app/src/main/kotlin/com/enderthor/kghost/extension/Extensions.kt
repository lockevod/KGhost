package com.enderthor.kghost.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

/**
 * Tolerant Json instance used everywhere we decode persisted config from DataStore.
 * - [ignoreUnknownKeys] = true  → removed fields are silently skipped instead of throwing.
 * - [coerceInputValues] = true  → stale/removed enum values fall back to the field default.
 * - [isLenient] = true          → tolerate minor JSON syntax quirks.
 */
val jsonWithUnknownKeys = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Json instance for writing persisted state to DataStore.
 * No [encodeDefaults]: missing fields are filled from Kotlin defaults on read, which saves bytes
 * and lets future default changes propagate automatically to stored blobs that predate the change.
 */
val jsonForStorage = Json {
    // Equivalent to the default Json instance. Named explicitly to signal "this is the
    // storage write path" at every call site.
}

/**
 * Wraps [KarooSystemService.addConsumer] for a data-type stream into a [Flow].
 * Emits every [StreamState] update for the given [dataTypeId].
 */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer<OnStreamState>(
        params = OnStreamState.StartStreaming(dataTypeId),
        onEvent = { trySend(it.state) },
    )
    awaitClose { removeConsumer(listenerId) }
}

/**
 * Wraps [KarooSystemService.addConsumer] for ride state into a [Flow].
 * Emits the current [RideState] immediately on subscription, then on every change.
 */
fun KarooSystemService.streamRide(): Flow<RideState> = callbackFlow {
    val listenerId = addConsumer<RideState>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(listenerId) }
}

/**
 * Wraps [KarooSystemService.addConsumer] for navigation state into a [Flow].
 * Hook for sub-project ② (route-ghost); only used for logging in sub-project ①.
 * Emits every [OnNavigationState] update (route selection, destination, idle).
 */
fun KarooSystemService.streamNavigationState(): Flow<OnNavigationState> = callbackFlow {
    val listenerId = addConsumer<OnNavigationState>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(listenerId) }
}

/**
 * Wraps [KarooSystemService.addConsumer] for GPS location events into a [Flow].
 * Emits every [OnLocationChanged] update from the Karoo GPS stream.
 *
 * Consumers access coordinates via [OnLocationChanged.lat] and [OnLocationChanged.lng].
 * Used in ② to feed [com.enderthor.kghost.engine.RouteProjectedProgress] and
 * [com.enderthor.kghost.geo.TrackRecorder] with live GPS samples.
 */
fun KarooSystemService.streamLocation(): Flow<OnLocationChanged> = callbackFlow {
    val id = addConsumer<OnLocationChanged>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(id) }
}

/**
 * Wraps [KarooSystemService.addConsumer] for the map zoom level into a [Flow] of the zoom value
 * (`[8.0, 18.0]`, smaller = more zoomed out). Used to auto-scale the on-map ghost icon.
 */
fun KarooSystemService.streamMapZoom(): Flow<Double> = callbackFlow {
    val id = addConsumer<OnMapZoomLevel>(onEvent = { trySend(it.zoomLevel) })
    awaitClose { removeConsumer(id) }
}

/**
 * Wraps [KarooSystemService.addConsumer] for the rider's [UserProfile] into a [Flow]. Used to read
 * the configured unit system (`preferredUnit.distance` = METRIC / IMPERIAL) so the gap distance and
 * the Ghost Pace speed/pace are shown in the rider's units.
 */
fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = callbackFlow {
    val id = addConsumer<UserProfile>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(id) }
}
