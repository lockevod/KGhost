package com.enderthor.kghost.extension

import android.os.SystemClock
import io.hammerhead.karooext.models.StreamState
import timber.log.Timber

/**
 * Diagnostic: what a stream delivers BEFORE any filtering. The field log of 2026-10-02 could only say "no
 * usable position arrived for 17 min" — not whether callbacks stopped, or kept arriving as Searching /
 * NotAvailable / a point without coordinates, because every consumer drops those before logging. This logs
 * each change of kind (with how long the previous kind lasted) and renders a one-line snapshot for the
 * GPS-lost alert. Capped per ride so a flapping stream cannot flood the uploaded log.
 */
class StreamArrivals(
    private val label: String,
    /** Kind of a Streaming point — must mirror what the CONSUMER accepts, or the log says "ok" while the
     *  consumer drops every point (the exact case this exists for). Default: a finite singleValue. */
    private val classify: (StreamState.Streaming) -> String = { if (it.dataPoint.singleValue?.isFinite() == true) "ok" else "no-value" },
) {
    @Volatile private var lastMs = 0L
    @Volatile private var kind: String? = null
    @Volatile private var kindSinceMs = 0L
    @Volatile private var logged = 0

    fun on(state: StreamState) {
        val now = SystemClock.elapsedRealtime()
        lastMs = now
        val k = when (state) {
            is StreamState.Streaming -> classify(state)
            is StreamState.Searching -> "searching"
            is StreamState.NotAvailable -> "not-available"
            is StreamState.Idle -> "idle"
        }
        if (k == kind) return
        val prev = kind
        val heldS = if (prev == null) 0L else (now - kindSinceMs) / 1000
        kind = k
        kindSinceMs = now
        if (logged < MAX_LOGGED) {
            logged++
            Timber.i("KVP stream %s: %s → %s (after %ds)%s", label, prev ?: "start", k, heldS,
                if (logged == MAX_LOGGED) " — further changes not logged this ride" else "")
        }
    }

    /** e.g. `LOCATION=searching/12s` — the current kind and seconds since the last callback of any kind. */
    fun snapshot(): String {
        val k = kind ?: return "$label=never"
        return "$label=$k/${(SystemClock.elapsedRealtime() - lastMs) / 1000}s"
    }

    fun reset() { lastMs = 0L; kind = null; kindSinceMs = 0L; logged = 0 }

    private companion object { const val MAX_LOGGED = 30 }
}
