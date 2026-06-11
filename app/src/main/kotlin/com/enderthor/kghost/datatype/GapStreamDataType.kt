package com.enderthor.kghost.datatype

import com.enderthor.kghost.engine.GapState
import com.enderthor.kghost.engine.GapStateHolder
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Numeric gap STREAM — publishes the live gap as a Karoo data stream so OTHER extensions/apps can
 * consume it (the same inter-extension mechanism karoo-headwind uses to republish weather):
 *
 * ```kotlin
 * karooSystem.streamDataFlow("TYPE_EXT::kghost::kghost-gap-time").collect { state ->
 *     val gapS = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
 *     // gapS > 0 → rider is AHEAD of the ghost
 * }
 * ```
 *
 * Two instances are registered (one variable per typeId, Headwind-style, so a consumer only needs
 * `singleValue`):
 *  - `kghost-gap-time` — gap to the ghost in SECONDS.
 *  - `kghost-gap-dist` — gap to the ghost in METRES.
 *
 * Sign convention on the wire is the HUMAN one (matching the on-screen fields, NOT the engine's
 * internal math convention): **positive = ahead of the ghost, negative = behind**. [GapState] keeps
 * ahead ⇒ gapTimeS negative internally, so the time selector flips the sign at the boundary;
 * gapDistanceM is already positive-when-ahead.
 *
 * Each DataPoint also carries [FIELD_ESTIMATED] (1.0/0.0) so a consumer can tell an ESTIMATED gap
 * from a measured one — it mirrors [GapState.estimated], which fires whenever the rider's position
 * is uncertain: a prolonged GPS loss (dead-reckoned), off-route/rejoin (position frozen), or a
 * stale/spurious route fix. While there is nothing to publish
 * (not recording / no first data / GPS give-up — [GapState.active] = false) the stream sits in
 * [StreamState.Searching], which consumers see as "no data" and the host renders as searching.
 *
 * Because these are declared in extension_info.xml WITHOUT `graphical="true"`, the Karoo also
 * renders them natively as plain numeric fields — a free bonus readout; the rich UI stays in
 * [GapNumericDataType]/[GapGraphicDataType]. The host only calls [startStream] while at least one
 * consumer (a field on the active page, or another extension) is subscribed, so this costs nothing
 * when unused.
 */
class GapStreamDataType(
    typeId: String,
    /** Maps the live [GapState] to the streamed single value (positive = ahead). */
    private val select: (GapState) -> Double,
) : DataTypeImpl("kghost", typeId) {

    companion object {
        /** Gap in seconds, positive = ahead. Full id: `TYPE_EXT::kghost::kghost-gap-time`. */
        const val TYPE_ID_TIME = "kghost-gap-time"

        /** Gap in metres, positive = ahead. Full id: `TYPE_EXT::kghost::kghost-gap-dist`. */
        const val TYPE_ID_DIST = "kghost-gap-dist"

        /** Extra DataPoint field: 1.0 while the gap is an estimate (GPS loss / off-route), else 0.0. */
        const val FIELD_ESTIMATED = "estimated"

        /** The two instances [com.enderthor.kghost.extension.KGhostExtension.types] registers. */
        fun all() = listOf(
            // Engine convention is ahead ⇒ negative time; flip to the human positive-ahead here.
            GapStreamDataType(TYPE_ID_TIME) { -it.gapTimeS },
            GapStreamDataType(TYPE_ID_DIST) { it.gapDistanceM },
        )
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Timber.d("KVP $typeId startStream")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch {
            try {
                // GapStateHolder.state is a StateFlow: the subscriber gets the CURRENT value
                // immediately, then one emission per ~1 Hz tick change — no extra throttling needed,
                // and identical states are deduped by the StateFlow itself.
                GapStateHolder.state.collect { state ->
                    emitter.onNext(
                        if (!state.active) {
                            StreamState.Searching
                        } else {
                            StreamState.Streaming(
                                DataPoint(
                                    dataTypeId,
                                    values = mapOf(
                                        DataType.Field.SINGLE to select(state),
                                        FIELD_ESTIMATED to if (state.estimated) 1.0 else 0.0,
                                    ),
                                    sourceId = extension,
                                ),
                            )
                        },
                    )
                }
            } catch (_: CancellationException) {
                // normal — last consumer unsubscribed.
            } catch (e: Exception) {
                Timber.e(e, "KVP $typeId stream error")
                // If onNext threw because the HOST process died (DeadObjectException), onError does
                // another IPC on the same dead binder and would throw out of this root coroutine →
                // process death. Best-effort only; a live host gets the error, a dead one is dropped.
                runCatching { emitter.onError(e) }
            }
        }
        emitter.setCancellable {
            Timber.d("KVP $typeId stopStream")
            scope.cancel()
        }
    }
}
