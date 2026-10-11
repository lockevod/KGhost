package com.enderthor.kghost.geo

import com.enderthor.kghost.engine.GRADE_SCHEMA_VERSION
import com.enderthor.kghost.engine.GradePace
import com.enderthor.kghost.engine.GradePaceDto
import com.enderthor.kghost.extension.jsonForStorage
import com.enderthor.kghost.extension.jsonWithUnknownKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File

/**
 * One-file persistence for the global [GradePace] model. Tiny (41 bins), rewritten only when an import
 * finishes, read once per route load.
 *
 * An unservable schema (below [MIN_SERVABLE_SCHEMA]) or an unparseable file is DISCARDED, never partially
 * trusted: the caller then falls back
 * to the neutral fill, which is a silence rather than a wrong verdict. Same policy as [AggregateStore].
 */
class GradePaceStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)

    /**
     * The model to SERVE: any schema in [MIN_SERVABLE_SCHEMA]..[GRADE_SCHEMA_VERSION] (same DTO layout), so
     * an input-set bump keeps the old model answering until the rebuild it owes ([isCurrent]) completes.
     */
    fun load(): GradePace? = readDto()
        ?.takeIf { it.schemaVersion in MIN_SERVABLE_SCHEMA..GRADE_SCHEMA_VERSION }
        ?.let(GradePace::fromDto)

    /** False when the model is missing, unreadable or of an older schema: a rebuild is OWED. */
    fun isCurrent(): Boolean = readDto()?.schemaVersion == GRADE_SCHEMA_VERSION

    private fun readDto(): GradePaceDto? {
        val f = file
        if (!f.isFile) return null
        return runCatching { jsonWithUnknownKeys.decodeFromString<GradePaceDto>(f.readText()) }.getOrElse {
            Timber.w(it, "grade-pace model unreadable; discarding")
            null
        }
    }

    /**
     * True only if the model reached disk: [atomicWriteText] preserves the old file on an IO error and
     * reports it solely through its Boolean, so callers clearing a "model dirty" debt must see it.
     */
    fun save(model: GradePace): Boolean = runCatching {
        if (!dir.exists()) dir.mkdirs()
        atomicWriteText(file, jsonForStorage.encodeToString(model.toDto()))
    }.onFailure { Timber.w(it, "could not persist the grade-pace model") }.getOrDefault(false)

    /** What [rebuild] did: the model, whether it reached disk, and the counts its log line reports. */
    class RebuildResult(
        val model: GradePace,
        val saved: Boolean,
        val parsed: Int,
        val cached: Int,
        val suppressed: Int,
        val liveFiles: Int,
        val archiveFiles: Int,
    )

    /**
     * Rebuilds and saves the model from the WHOLE history: every track file in the live dir AND in
     * [TrackStore.ARCHIVE_SUBDIR]. The archive is where the same-ride rule puts a recording's FIT twin (the
     * only copy carrying altitude) and where the size rule puts pruned rides, so a live-only rebuild
     * silently forgot most of a rider's history. Each file is reduced to its [GradePace.Fold] and cached
     * in [FOLDS_FILE_NAME] keyed by (where, id) with size + mtime, so only new/changed files are parsed —
     * streamed, one at a time, [checkCancel] per file. A file moved live -> archive (rename keeps size and
     * mtime) reuses its fold; vanished files drop out; a corrupt, missing or stale-schema cache means a
     * full recompute. Known limit: an external same-size, same-mtime replacement reuses a stale fold (the
     * app's own writes go through an atomic rename, which gives a new mtime).
     *
     * One contribution per PHYSICAL ride ([dedupe]):
     *  a. the same id live AND archived (a rebuildAll re-decode) is one candidate — the fold with more metres;
     *  b. candidates in descending total metres (tie: lowest id) are accepted unless their own interval
     *     `[startedAtEpoch, startedAtEpoch + lastPoint.timeS]` overlaps an already-ACCEPTED one by MORE than 50 %
     *     of their OWN duration. Pairwise against accepted folds only — no transitive clusters, so a long
     *     ride B straddling A and C cannot chain A and C into one. An UNDATABLE interval — `startedAtEpoch
     *     <= 0`, end <= start (a backward clock jump) or longer than [MAX_RIDE_SPAN_MS] (a forward jump, e.g.
     *     the FIT-replay simulator moving the system clock) — is exempt from (b) both ways: it never
     *     suppresses and is never suppressed, so a months-long bogus span cannot swallow the real rides in it.
     * Accepted residuals (deliberately NOT handled):
     *  - a friend's overlapping FIT imported by hand replaces the owner's fold when it has more metres
     *    (before, both counted);
     *  - a ride split across two partial files keeps only the larger part;
     *  - a clock-shifted re-export of the same ride counts twice (as before);
     *  - an altitude-bearing RECORDED track's interval uses ELAPSED time (shorter, same start), so its FIT
     *    twin still overlaps it by more than 50 % of its own duration and one of them is suppressed.
     *
     * The cache is written atomically AFTER the model save; a cache write failure only costs a re-parse. A
     * cancelled (or failed) pass still writes the folds it parsed, so a cold rebuild converges across runs.
     */
    fun rebuild(checkCancel: () -> Unit = {}): RebuildResult {
        val t0 = System.nanoTime()
        val old = loadFolds()
        val entries = ArrayList<FoldEntry>()
        var parsed = 0
        var cached = 0
        var liveFiles = 0
        var archiveFiles = 0
        try {
            for (where in listOf(LIVE, ARCHIVE)) {
                val d = if (where == LIVE) dir else File(dir, TrackStore.ARCHIVE_SUBDIR)
                for (f in d.listFiles().orEmpty()) {
                    if (!TrackStore.isTrackFile(f)) continue
                    checkCancel()
                    if (where == LIVE) liveFiles++ else archiveFiles++
                    val id = TrackStore.trackIdOf(f)
                    val size = f.length()
                    val mtime = f.lastModified()
                    // Same place first, then the other one: a live -> archive move keeps size and mtime.
                    val hit = listOf(where, if (where == LIVE) ARCHIVE else LIVE)
                        .firstNotNullOfOrNull { w -> old[w to id]?.takeIf { it.size == size && it.mtime == mtime } }
                    if (hit != null) {
                        entries += hit.copy(where = where)
                        cached++
                        continue
                    }
                    // An unreadable file gets NO entry, so a transient IO error is retried next time; an
                    // undecodable one is cached as empty (it is not a track) until its size/mtime change.
                    val text = runCatching { f.readText() }.getOrElse {
                        Timber.w(it, "grade-pace rebuild: could not read %s", f.name)
                        null
                    } ?: continue
                    parsed++
                    val track = runCatching { jsonWithUnknownKeys.decodeFromString<RecordedTrack>(text) }.getOrNull()
                    val fold = track?.let(GradePace::foldOf)
                    val start = track?.startedAtEpoch ?: 0L
                    val end = start + ((track?.points?.lastOrNull()?.timeS ?: 0.0) * 1000.0).toLong()
                    entries += FoldEntry(where, id, size, mtime, start, end, fold?.sumDt.orEmpty(), fold?.sumDd.orEmpty())
                }
            }
        } catch (e: Throwable) {
            // Cut short (an auto run is cancelled when a ride starts): keep what this pass parsed, plus the old
            // entries it never reached, so a cold rebuild converges across cancelled runs. No model save.
            val seen = entries.mapTo(HashSet()) { it.where to it.id }
            writeFolds(entries + old.filterKeys { it !in seen }.values)
            throw e
        }
        val (folds, suppressed) = dedupe(entries)
        val model = GradePace.reduce(folds)
        val saved = save(model)
        if (!writeFolds(entries)) Timber.w("grade-pace fold cache not persisted; the next rebuild re-parses")
        Timber.i(
            "grade-pace rebuild: %d ms, files live=%d archive=%d, parsed=%d cached=%d, rides=%d suppressed=%d, coveredM=%.0f",
            (System.nanoTime() - t0) / 1_000_000, liveFiles, archiveFiles, parsed, cached, folds.size, suppressed,
            model.coveredM,
        )
        return RebuildResult(model, saved, parsed, cached, suppressed, liveFiles, archiveFiles)
    }

    private val foldsFile: File get() = File(dir, FOLDS_FILE_NAME)

    private fun writeFolds(entries: List<FoldEntry>): Boolean = runCatching {
        atomicWriteText(foldsFile, jsonForStorage.encodeToString(FoldCacheDto(GRADE_SCHEMA_VERSION, entries)))
    }.getOrDefault(false)

    /** The previous run's folds; empty (= full recompute) when missing, corrupt or of another schema. */
    private fun loadFolds(): Map<Pair<String, String>, FoldEntry> {
        val f = foldsFile
        if (!f.isFile) return emptyMap()
        return runCatching {
            jsonWithUnknownKeys.decodeFromString<FoldCacheDto>(f.readText())
                .takeIf { it.schemaVersion == GRADE_SCHEMA_VERSION }
                ?.entries?.associateBy { it.where to it.id }
        }.getOrNull() ?: emptyMap()
    }

    /** One track file's cached fold. Empty sums = no usable altitude (or not a track): don't re-parse. */
    @Serializable
    internal data class FoldEntry(
        val where: String,
        val id: String,
        val size: Long,
        val mtime: Long,
        val startEpoch: Long,
        val endEpoch: Long,
        val sumDt: Map<Int, Double>,
        val sumDd: Map<Int, Double>,
    ) {
        val totalM: Double get() = sumDd.values.sum()

        /** A plausible ride interval; only these take part in rule (b) of [rebuild]. */
        val datable: Boolean get() = startEpoch > 0 && endEpoch > startEpoch && endEpoch - startEpoch <= MAX_RIDE_SPAN_MS
    }

    @Serializable
    internal data class FoldCacheDto(val schemaVersion: Int, val entries: List<FoldEntry>)

    companion object {
        const val FILE_NAME = "gradepace.json"
        const val FOLDS_FILE_NAME = "gradepace-folds.json"
        internal const val LIVE = "live"
        internal const val ARCHIVE = "archive"

        /** Oldest persisted model [load] still serves. 1 -> 2 changed only the input set (the DTO is the
         *  same), so a v1 model keeps the ghost going while the v2 rebuild is owed. Raise it to the new
         *  version when a bump changes the DTO or the meaning of a field. */
        internal const val MIN_SERVABLE_SCHEMA = 1

        /** Longest interval rule (b) believes. Real rides, ultras included, fit; a clock-jumped FIT does not. */
        internal const val MAX_RIDE_SPAN_MS = 24 * 3_600_000L

        /**
         * The duplicate suppression of [rebuild] (rules a and b there). Pure. Returns the surviving folds
         * (in id order, so the reduce's epoch-tie order is deterministic) and how many candidates were
         * suppressed.
         */
        internal fun dedupe(entries: List<FoldEntry>): Pair<List<GradePace.Fold>, Int> {
            val withAltitude = entries.filter { it.sumDd.isNotEmpty() }
            // (a) one candidate per id: the copy with more metres.
            val byId = withAltitude.groupBy { it.id }.values.map { copies -> copies.maxBy { it.totalM } }
            var suppressed = withAltitude.size - byId.size
            // (b) greedy, most metres first, pairwise against ACCEPTED candidates only.
            val accepted = ArrayList<FoldEntry>()
            for (c in byId.sortedWith(compareByDescending<FoldEntry> { it.totalM }.thenBy { it.id })) {
                val dur = c.endEpoch - c.startEpoch
                val dup = c.datable && accepted.any { a ->
                    a.datable && minOf(a.endEpoch, c.endEpoch) - maxOf(a.startEpoch, c.startEpoch) > dur / 2.0
                }
                if (dup) suppressed++ else accepted += c
            }
            return accepted.sortedBy { it.id }.map { GradePace.Fold(it.startEpoch, it.sumDt, it.sumDd) } to suppressed
        }
    }
}
