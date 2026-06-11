package com.enderthor.kghost.geo

import android.content.Context
import android.os.Environment
import com.enderthor.kghost.managers.StoragePermission
import timber.log.Timber
import java.io.File

/**
 * Resolves the directory the [TrackStore] uses for processed tracks.
 *
 * Prefers shared external `/sdcard/KGhost/tracks` so the user can browse, back up and restore
 * their tracks and they survive an uninstall — but only when all-files access is granted (needed to
 * write shared storage). Without that permission it falls back to internal `filesDir/tracks` so
 * recording never breaks. On first use of the external dir it migrates any existing internal tracks
 * across (copy, not delete — internal stays as a backup).
 *
 * ALL components (the extension's recorder, the import UI, the track-count reader) must call this so
 * the store is never split across two locations.
 *
 * NOTE: [tracksDir] performs file IO (mkdirs + a one-time migration copy), so it MUST be called off
 * the main thread.
 */
object TrackStorage {
    private const val DIR_NAME = "tracks"

    fun tracksDir(context: Context): File = resolveDir(context, DIR_NAME, ::migrateIfNeeded)

    /**
     * Resolves the directory for the per-route average-ghost aggregates — a sibling of [tracksDir]
     * (`/sdcard/KGhost/aggregates` when all-files access is granted, else `filesDir/aggregates`), with
     * the same permission/fallback logic and one-time internal→external migration (copy, not delete).
     * Like [tracksDir], this does file IO and MUST be called off the main thread.
     */
    fun aggregatesDir(context: Context): File =
        resolveDir(context, AggregateStore.DIR_NAME, ::migrateFilesIfNeeded)

    /**
     * Shared dir resolution: external `/sdcard/KGhost/<dirName>` when all-files access is granted
     * (running [migrate] once to copy any internal files across), else internal `filesDir/<dirName>`.
     * Any external failure falls back to internal so storage never breaks.
     */
    private fun resolveDir(context: Context, dirName: String, migrate: (File, File) -> Int): File {
        val internal = File(context.filesDir, dirName)
        if (!StoragePermission.hasAllFilesAccess(context)) {
            internal.mkdirs()
            return internal
        }
        val external = File(Environment.getExternalStorageDirectory(), "KGhost/$dirName")
        return runCatching {
            external.mkdirs()
            migrate(internal, external)
            external
        }.getOrElse { e ->
            // Shared storage unexpectedly unwritable → keep working on internal.
            Timber.w(e, "external $dirName dir unusable; falling back to internal")
            internal.mkdirs()
            internal
        }
    }

    /**
     * Copies every internal aggregate missing from [external] across (never overwrites, never
     * deletes internal). The flat-directory variant of [migrateIfNeeded] (no archive subdir) — used
     * for the aggregates store. Running it on EVERY dir resolution (not just first use) also heals
     * the split-brain left by a revoke→re-grant of all-files access: aggregates updated on internal
     * while access was revoked get copied across; existing external blobs always win.
     */
    fun migrateFilesIfNeeded(internal: File, external: File): Int {
        val internalFiles = internal.listFiles()?.filter { it.isFile } ?: return 0
        if (internalFiles.isEmpty()) return 0
        return copyNewFiles(internalFiles, external)
    }

    /**
     * Copies [internal] tracks into [external]. Pure file IO (no Android) so it is unit-testable.
     * Never overwrites an existing external file, never deletes the internal copies. Two regimes:
     *  - External has no track files yet (first use): full migration, including the `archive/`
     *    subdir so pruned rides stay recoverable after the store switches to external.
     *  - External is already canonical: heal the split-brain left by a revoke→re-grant of all-files
     *    access — rides recorded while access was revoked landed on INTERNAL only, so copy across
     *    any internal file present in NEITHER external NOR external/archive. The archive check stops
     *    a ride the auto-clean already archived on external from being resurrected as active. (The
     *    healed file lands unindexed; TrackStore.prewarmAndReconcile re-indexes it at next startup.)
     * Returns the number of files copied.
     */
    fun migrateIfNeeded(internal: File, external: File): Int {
        val internalFiles = internal.listFiles()?.filter { it.isFile } ?: return 0
        if (internalFiles.isEmpty()) return 0
        val externalHasFiles = external.listFiles()?.any { it.isFile } == true
        if (externalHasFiles) {
            val externalArchive = File(external, TrackStore.ARCHIVE_SUBDIR)
            var healed = copyNewFiles(
                internalFiles.filter { !File(externalArchive, it.name).exists() },
                external,
            )
            // Rides archived on INTERNAL while access was revoked are stranded the same way — bring
            // them into external/archive (never as active; an external copy of any kind wins).
            File(internal, TrackStore.ARCHIVE_SUBDIR).listFiles()?.filter { it.isFile }?.let { archived ->
                val strays = archived.filter { !File(external, it.name).exists() }
                if (strays.isNotEmpty()) {
                    externalArchive.mkdirs()
                    healed += copyNewFiles(strays, externalArchive)
                }
            }
            if (healed > 0) Timber.i("healed $healed internal-only track file(s) into external")
            return healed
        }
        var copied = copyNewFiles(internalFiles, external)
        // Also migrate the archive/ subdir (pruned tracks) so they stay recoverable after the store
        // switches to external — the isFile filter above skips the subdir, so without this the archived
        // rides would be stranded on internal storage.
        File(internal, TrackStore.ARCHIVE_SUBDIR).listFiles()?.filter { it.isFile }?.let { archived ->
            if (archived.isNotEmpty()) {
                val externalArchive = File(external, TrackStore.ARCHIVE_SUBDIR)
                externalArchive.mkdirs()
                copied += copyNewFiles(archived, externalArchive)
            }
        }
        Timber.d("migrated $copied internal track files to external")
        return copied
    }

    /** Copies each of [files] into [dst] unless it already exists there (never overwrites). */
    private fun copyNewFiles(files: List<File>, dst: File): Int {
        var copied = 0
        for (f in files) {
            val dest = File(dst, f.name)
            if (!dest.exists()) {
                runCatching { f.copyTo(dest, overwrite = false) }
                    .onSuccess { copied++ }
                    .onFailure { Timber.w(it, "failed migrating ${f.name}") }
            }
        }
        return copied
    }
}
