package com.enderthor.kghost.geo

import timber.log.Timber
import java.io.File

/**
 * Writes [text] to [target] atomically and durably: serialize to a temp file in the same
 * directory, fsync the temp file's data to disk, then [File.renameTo] (an atomic move on the same
 * filesystem) so a reader never observes a half-written file. If the rename fails (returns false —
 * e.g. some exotic filesystem) we fall back to a direct truncate-then-write as best effort and log
 * the degradation via Timber.
 *
 * The `fd.sync()` BEFORE the rename is the durability guarantee that matters on the Karoo: without
 * it, the rename can be journaled while the temp file's data blocks are still only in the page
 * cache, so an abrupt power-off (common at ride-end, exactly when the stores write) leaves a
 * zero-length / torn file after reboot — which readers treat as "empty", silently dropping the
 * stored data until something rewrites it.
 *
 * Shared by [TrackStore] (tracks / index / sourcekeys) and [AggregateStore] (per-route averages) so
 * the crash-safety semantics live in exactly one place.
 */
internal fun atomicWriteText(target: File, text: String, fsync: Boolean = true) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    val written = runCatching {
        java.io.FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            // fsync forces data to stable storage before the rename exposes it. Skipped (fsync=false)
            // for re-creatable per-track files during a bulk import to avoid an N-file fsync storm;
            // always on for the durability-critical index/sourcekeys and the ride-end saves.
            if (fsync) fos.fd.sync()
        }
    }.recoverCatching {
        // fsync unsupported/failed on this filesystem — fall back to a plain write so we still
        // produce a complete temp file to rename (durability degrades, atomicity preserved).
        Timber.w(it, "fsynced temp write failed for %s; falling back to plain write", target.name)
        tmp.writeText(text)
    }.isSuccess

    // Only expose the temp via rename if it was actually written in full. If BOTH the fsynced
    // write AND the plain fallback failed (e.g. disk full / IO error), tmp is empty or truncated —
    // renaming it would atomically CLOBBER a previously-good target with garbage. Preserve the old
    // target instead.
    if (!written) {
        Timber.w("temp write failed for %s; preserving the previous file", target.name)
        tmp.delete()
        return
    }
    if (!tmp.renameTo(target)) {
        Timber.w("atomic rename failed for %s; falling back to direct write", target.name)
        runCatching { target.writeText(text) }
            .onFailure { Timber.w(it, "fallback direct write failed for %s", target.name) }
        tmp.delete()
    }
}
