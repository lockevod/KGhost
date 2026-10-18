package com.enderthor.kghost.extension

import com.enderthor.kghost.engine.GhostCheckpoint
import com.enderthor.kghost.geo.atomicWriteText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File

/** The B2 ghost checkpoint file: a tiny scalar resume state persisted periodically so a mid-ride power-off
 *  / crash / service kill resumes the race WITH the accrued lead. Deleted when the race stops owning the
 *  lead (clean ride end, post-route entry, race end), so a file present on the next start means a resume
 *  candidate.
 *
 *  Every write and every delete runs under [mutex] (shared with the VP-origin writer), and [flush] reads
 *  [pending] INSIDE the lock: an [invalidate] can therefore never be undone by a flush that was already in
 *  flight (paused before or inside the lock) — that flush either finds `pending == null` or writes before
 *  the delete. Callers AWAIT [invalidate] so no later tick can publish a checkpoint ahead of the delete.
 *
 *  Crash boundary: dying before an awaited [invalidate] completes = dying while the route race still owned
 *  the lead, so restoring that checkpoint on the next start is correct. */
internal class GhostCheckpointFile(
    private val file: File,
    private val mutex: Mutex,
    private val write: (File, String) -> Unit = { f, s -> atomicWriteText(f, s, fsync = false) },
    private val beforeLock: suspend () -> Unit = {}, // test seam: runs in flush() before the lock is taken
) {
    /** Latest snapshot, built on the Main tick (so the IO writer never reads the integrator's non-volatile
     *  scalars cross-thread). */
    @Volatile var pending: GhostCheckpoint? = null

    /** Reads the persisted checkpoint, or null if absent/corrupt (never throws). */
    fun load(): GhostCheckpoint? = runCatching {
        if (!file.exists()) null
        else jsonForStorage.decodeFromString(GhostCheckpoint.serializer(), file.readText())
    }.getOrNull()

    /** Writes [pending] atomically; null → no write. Suspends; call on IO. fsync off — a lost/torn
     *  checkpoint just means resume-from-zero (safe), not corruption. */
    suspend fun flush() {
        beforeLock()
        mutex.withLock {
            val cp = pending ?: return
            runCatching { write(file, jsonForStorage.encodeToString(GhostCheckpoint.serializer(), cp)) }
                .onFailure { Timber.w(it, "ghost checkpoint write failed") }
        }
    }

    /** Drops the snapshot, then deletes the file under the writer's lock. Suspends; call on IO, awaited. */
    suspend fun invalidate() {
        pending = null
        mutex.withLock { runCatching { file.delete() } }
    }
}
