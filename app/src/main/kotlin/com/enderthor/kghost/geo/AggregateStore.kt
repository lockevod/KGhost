package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.PerRouteAggregate
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File

/**
 * On-disk store for the per-route average-ghost aggregates — one `<routeKey>.json` per route under
 * [dir] (typically `<storage>/aggregates`, a sibling of `tracks/`).
 *
 * Deliberately tiny: a route's aggregate is a single self-contained [PerRouteAggregate] blob, so
 * unlike [TrackStore] there is no shared index or dedup bookkeeping to coordinate. All IO is
 * synchronous — callers run it on `Dispatchers.IO`. Takes a plain [File] (not a `Context`) so it is
 * unit-testable against a temp folder.
 */
class AggregateStore(private val dir: File) {

    // Process-wide-per-dir write lock (symmetric with TrackStore.indexLock). Since the AVERAGE
    // seeding writes from the match coroutine (Dispatchers.Default) while the ride-end EMA update
    // writes from Dispatchers.IO, two saves of the SAME key could otherwise share atomicWriteText's
    // fixed `<key>.json.tmp` and produce a torn (corrupt) blob. Serialising save() closes that window.
    private val writeLock: Any = lockFor(dir)

    /** Loads the aggregate for [routeKey], or null if absent/corrupt (logged, never throws). */
    fun load(routeKey: String): PerRouteAggregate? {
        val f = File(dir, fileNameFor(routeKey))
        if (!f.isFile) return null
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<PerRouteAggregate>(f.readText())
                .takeIf { it.schemaVersion == com.enderthor.kghost.engine.AGG_SCHEMA_VERSION }
                ?: run {
                    Timber.i("aggregate %s is a stale schema; discarding (will re-seed)", routeKey)
                    null
                }
        }.getOrElse { e ->
            Timber.w(e, "aggregate %s present but failed to parse; ignoring (corrupt?)", routeKey)
            null
        }
    }

    /** Persists [agg] atomically + durably (shared fsync-temp-then-rename, see [atomicWriteText]). */
    fun save(agg: PerRouteAggregate) = synchronized(writeLock) {
        if (!dir.exists()) dir.mkdirs()
        atomicWriteText(File(dir, fileNameFor(agg.routeKey)), jsonForStorage.encodeToString(agg))
    }

    /**
     * Deletes stale aggregate blobs, plus any day-old `.tmp` leftovers from interrupted writes.
     * Routes get renamed or deleted and their aggregates (keyed by name+length) would otherwise pile
     * up forever — there is no other hygiene for this store, unlike the track library's auto-clean.
     * Every save refreshes the file's mtime, so "recently ridden" is the liveness signal: a blob not
     * updated within [maxAgeMs] is dead weight, and beyond [maxFiles] the least-recently-updated go
     * first. Returns the number of files deleted.
     */
    fun sweep(
        maxFiles: Int = SWEEP_MAX_FILES,
        maxAgeMs: Long = SWEEP_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val all = dir.listFiles()?.filter { it.isFile } ?: return 0
        var deleted = 0
        for (f in all.filter { it.name.endsWith(".tmp") && nowMs - it.lastModified() > 24 * 3600_000L }) {
            if (f.delete()) deleted++
        }
        val blobs = all.filter { it.name.endsWith(".json") }
        val (fresh, stale) = blobs.partition { nowMs - it.lastModified() <= maxAgeMs }
        for (f in stale) if (f.delete()) deleted++
        if (fresh.size > maxFiles) {
            for (f in fresh.sortedBy { it.lastModified() }.take(fresh.size - maxFiles)) {
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) Timber.i("aggregate sweep: deleted %d stale file(s)", deleted)
        return deleted
    }

    companion object {
        const val DIR_NAME = "aggregates"

        /** Process-wide write locks, keyed by canonical dir path, so two AggregateStore instances over
         *  the same dir serialise their saves (symmetric with TrackStore's dirLocks). */
        private val dirLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(dir: File): Any {
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            return dirLocks.computeIfAbsent(key) { Any() }
        }

        /** Aggregates kept at most (least-recently-updated pruned beyond this). */
        const val SWEEP_MAX_FILES = 200

        /** An aggregate not updated in this long (~18 months) is dead weight and is pruned. */
        const val SWEEP_MAX_AGE_MS = 540L * 24 * 3600_000L

        /**
         * Maps a route key to a safe file name. [routeKeyOf] already yields a `[a-z0-9-]`-only stem,
         * but a defensive re-sanitize keeps the store robust to any caller-supplied key.
         */
        private fun fileNameFor(routeKey: String): String {
            val safe = routeKey.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").ifEmpty { "route" }
            return "$safe.json"
        }
    }
}
