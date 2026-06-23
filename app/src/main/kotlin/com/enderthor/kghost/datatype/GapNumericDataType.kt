package com.enderthor.kghost.datatype

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GapDisplay
import com.enderthor.kghost.engine.GapDisplayLogic
import com.enderthor.kghost.engine.GapState
import com.enderthor.kghost.engine.GapStateHolder
import com.enderthor.kghost.engine.RenderPrefs
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Numeric gap data field — the simpler "option A" readout.
 *
 * Renders the time/distance gap to the Ghost Pace as plain text. The engine uses a
 * mathematical sign convention (ahead ⇒ gapTimeS negative), so this field flips the sign
 * for human-readable display: ahead shows `+M:SS` in green, behind shows `-M:SS` in red.
 *
 * The field shows `---` in the neutral day/night colour (NOT a disabled grey) ONLY when the gap is
 * not [GapState.active] (no target / not recording / no first data). A GPS loss does NOT blank: the
 * value keeps dead-reckoning and, once it is a prolonged-loss estimate ([GapState.estimated]), is
 * rendered in the amber estimate colour so the rider can tell it is extrapolated, not measured. A
 * legitimate stop (the ghost keeps moving) shows the real gap normally; only a truly sustained loss
 * (the extension eventually gives up and clears the state) returns to `---`.
 *
 * This is a passive readout, so there is no tap PendingIntent — it just observes
 * [GapStateHolder.state] (combined with the rider's [GapDisplay] preference from [RenderPrefs])
 * and emits a RemoteViews per distinct change.
 */
class GapNumericDataType(
    private val context: Context,
) : DataTypeImpl("kghost", "kghost-gap-num") {

    private companion object {
        const val PLACEHOLDER = "---"

        /**
         * Slow re-emit cadence so a frame ALWAYS lands after karoo-ext 1.1.9's 900 ms ViewEmitter
         * throttle window. On a ride re-start the host re-calls [startView], but the synchronous seed
         * frame can be throttle-dropped ("ignoring updateView, too soon"); the change-driven render
         * loop then emits only on a real state CHANGE, so a static/inactive gap state leaves the
         * field stuck showing its NAME (the host's default header) forever. Re-rendering the CURRENT
         * state every [HEARTBEAT_MS] guarantees a post-throttle frame even when nothing changes. The
         * build is cheap and ~3 s is spaced well beyond the 900 ms throttle so it always lands.
         */
        const val HEARTBEAT_MS = 3000L

        /**
         * Synthetic state shown in the profile-editor gallery (config.preview = true).
         * Represents being ~1:30 ahead and ~60 m in front — a readable, non-trivial snapshot.
         */
        val DEMO_STATE = GapState(
            gapTimeS = -90.0,
            gapDistanceM = 60.0,
            progressM = 0.0,
            ghostProgressM = 0.0,
            ahead = true,
            estimated = false,
            active = true,
        )
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("KVP gap-numeric startView preview=${config.preview}")
        // Each placement gets its OWN independent scope, cancelled ONLY by its own setCancellable
        // below — never a shared "cancel the previous scope" guard. The rider can put this field on
        // TWO pages, which the host serves from this ONE DataTypeImpl instance via separate startView
        // calls; cancelling a sibling scope here would FREEZE the other page's field.
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        // Seed one frame synchronously with the night-aware neutral text colour, so the field is
        // never white-on-white (invisible) in day mode during the pre-first-emission window.
        // karoo-ext 1.1.9's ViewEmitter.updateView hard-drops any call within 900 ms of the
        // previous one (keeping the EARLIER one), so the collect coroutine's first real frame
        // (a few ms later) would be silently dropped — on mid-ride page re-entry that left the
        // field showing `---` for ~1 s while a valid gap existed. Seeding with the LIVE current
        // state (the same values the dropped frame would carry) makes that drop harmless.
        emitter.updateView(
            buildView(
                if (config.preview) DEMO_STATE else GapStateHolder.state.value,
                RenderPrefs.gapDisplay.value,
                RenderPrefs.imperialDistance.value,
            ),
        )

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                // Wall-clock (ms) of the last frame we actually emitted. Makes the heartbeat
                // IDLE-ONLY: it re-emits only when no change frame went out in the last HEARTBEAT_MS,
                // so it no longer collides with the ~1 Hz change emits (which the host would drop with
                // "ignoring updateView, too soon") while still guaranteeing a post-throttle frame when
                // the state is static (anti-stuck).
                var lastEmitMs = 0L
                // Cheap render-key dedup: the gap doubles jitter every ~1 Hz tick but the DISPLAYED
                // value (whole seconds / metres + colour flags) changes far less often, so we skip the
                // RemoteViews rebuild + updateView IPC whenever the key is unchanged. lastRv caches the
                // last emitted view so the heartbeat can re-assert it without rebuilding.
                var lastKey: GapRenderKey? = null
                var lastRv: RemoteViews? = null
                // Change-driven source: emits on every real state/pref change (isHeartbeat = false).
                // GapStateHolder.state is a StateFlow (dedups its own value); the key dedup below
                // handles the sub-display-resolution jitter. The heartbeat MUST NOT be deduped against
                // it (it intentionally re-asserts the SAME current value), so it's merged in separately.
                // imperialDistance is folded in so a mid-ride unit flip (km↔mi) repaints
                // immediately instead of waiting for the next gap change or the heartbeat; the
                // value itself is read via .value at render time (below), same as the heartbeat.
                val changes = combine(
                    GapStateHolder.state, RenderPrefs.gapDisplay, RenderPrefs.imperialDistance,
                ) { state, gapDisplay, _ ->
                    Triple(state, gapDisplay, false)
                }
                // Heartbeat (isHeartbeat = true): re-emits the CURRENT state every HEARTBEAT_MS so a
                // throttle-dropped seed/first-frame can't leave the field stuck on its name. Merged
                // INTO this single collect so all rendering stays in one coroutine.
                val heartbeat = flow {
                    while (true) {
                        delay(HEARTBEAT_MS)
                        emit(Triple(GapStateHolder.state.value, RenderPrefs.gapDisplay.value, true))
                    }
                }
                merge(changes, heartbeat)
                    .collect { (liveState, gapDisplay, isHeartbeat) ->
                        val now = System.currentTimeMillis()
                        // Drop the periodic heartbeat if a real frame already went out recently — this
                        // is what removes the every-3 s "too soon" collisions during a ride.
                        if (isHeartbeat && now - lastEmitMs < HEARTBEAT_MS) return@collect
                        // In preview (profile editor gallery) render a synthetic demo state so the
                        // field shows a meaningful sample instead of the inactive `---` placeholder.
                        val state = if (config.preview) DEMO_STATE else liveState
                        val imperial = RenderPrefs.imperialDistance.value
                        val key = gapRenderKey(
                            state, gapDisplay, isRoute = false,
                            dark = context.isKarooNightMode(), imperial = imperial,
                        )
                        if (isHeartbeat) {
                            // Anti-stuck re-assert: re-emit the cached frame without rebuilding it.
                            val cached = lastRv
                            if (cached != null && key == lastKey) {
                                emitter.updateView(cached)
                                lastEmitMs = now
                                return@collect
                            }
                        } else if (key == lastKey && lastRv != null) {
                            return@collect // pixel-identical change → skip the rebuild + IPC
                        }
                        val rv = buildView(state, gapDisplay, imperial)
                        emitter.updateView(rv)
                        lastKey = key
                        lastRv = rv
                        lastEmitMs = now
                    }
            } catch (_: CancellationException) {
                // normal — field removed from the page.
            } catch (e: Exception) {
                Timber.e(e, "GapNumericDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    private fun buildView(state: GapState, gapDisplay: GapDisplay, imperial: Boolean): RemoteViews {
        val dark = context.isKarooNightMode()
        val neutral = if (dark) Color.WHITE else Color.BLACK
        val neutralHint = if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()

        // Waiting for data: neutral `---`, NOT a disabled grey. We ONLY blank on !active (no target /
        // not recording / no first data). A GPS loss does NOT blank: the value keeps coasting and is
        // rendered in the estimate colour (see below) instead — a navigator should keep showing a
        // best estimate through a dropout, not go dark.
        val waiting = !state.active

        val main: String
        val hint: String
        val mainColor: Int
        val hintColor: Int

        if (waiting) {
            main = PLACEHOLDER
            hint = ""
            mainColor = neutral
            hintColor = neutralHint
        } else {
            // Three-state classification with a small epsilon so an exactly-on-pace gap renders
            // neutral (no sign, day/night colour) rather than a misleading green "+0:00". While the
            // value is a prolonged-GPS-loss estimate, override the status hue with the amber estimate
            // colour so the rider can tell it's extrapolated, not measured.
            val status = GapDisplayLogic.gapStatus(state.gapTimeS)
            val stateColor =
                if (state.estimated) context.gapEstimateColor(dark)
                else context.gapStatusColor(status, neutral, dark)
            val timeText = fmtTime(state.gapTimeS, status)
            val distText = fmtDistance(state.gapDistanceM, imperial)
            when (gapDisplay) {
                GapDisplay.TIME -> {
                    main = timeText
                    hint = ""
                }
                GapDisplay.DISTANCE -> {
                    main = distText
                    hint = ""
                }
                GapDisplay.BOTH -> {
                    main = timeText
                    hint = distText
                }
            }
            mainColor = stateColor
            hintColor = stateColor
        }

        return RemoteViews(context.packageName, R.layout.field_numeric).apply {
            setTextViewText(R.id.field_main, main)
            setTextViewText(R.id.field_hint, hint)
            setViewVisibility(R.id.field_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_main, "setGravity", Gravity.CENTER)
            setInt(R.id.field_hint, "setGravity", Gravity.CENTER)
            setTextColor(R.id.field_main, mainColor)
            setTextColor(R.id.field_hint, hintColor)
        }
    }
}
