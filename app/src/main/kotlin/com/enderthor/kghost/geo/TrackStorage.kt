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

    fun tracksDir(context: Context): File {
        val internal = File(context.filesDir, DIR_NAME)
        if (!StoragePermission.hasAllFilesAccess(context)) {
            internal.mkdirs()
            return internal
        }
        val external = File(Environment.getExternalStorageDirectory(), "KGhost/$DIR_NAME")
        return runCatching {
            external.mkdirs()
            migrateIfNeeded(internal, external)
            external
        }.getOrElse { e ->
            // Shared storage unexpectedly unwritable → keep working on internal.
            Timber.w(e, "external tracks dir unusable; falling back to internal")
            internal.mkdirs()
            internal
        }
    }

    /**
     * One-time copy of [internal] tracks into [external] when external has no track files yet.
     * Pure file IO (no Android) so it is unit-testable. Copies every regular file (skips dirs),
     * never overwrites an existing external file, and never deletes the internal copies.
     * Returns the number of files copied.
     */
    fun migrateIfNeeded(internal: File, external: File): Int {
        val internalFiles = internal.listFiles()?.filter { it.isFile } ?: return 0
        if (internalFiles.isEmpty()) return 0
        val externalHasFiles = external.listFiles()?.any { it.isFile } == true
        if (externalHasFiles) return 0 // already migrated / external is canonical
        var copied = 0
        for (f in internalFiles) {
            val dest = File(external, f.name)
            if (!dest.exists()) {
                runCatching { f.copyTo(dest, overwrite = false) }
                    .onSuccess { copied++ }
                    .onFailure { Timber.w(it, "failed migrating ${f.name}") }
            }
        }
        // Also migrate the archive/ subdir (pruned tracks) so they stay recoverable after the store
        // switches to external — the isFile filter above skips the subdir, so without this the archived
        // rides would be stranded on internal storage.
        File(internal, TrackStore.ARCHIVE_SUBDIR).listFiles()?.filter { it.isFile }?.let { archived ->
            if (archived.isNotEmpty()) {
                val externalArchive = File(external, TrackStore.ARCHIVE_SUBDIR)
                externalArchive.mkdirs()
                for (f in archived) {
                    val dest = File(externalArchive, f.name)
                    if (!dest.exists()) {
                        runCatching { f.copyTo(dest, overwrite = false) }
                            .onSuccess { copied++ }
                            .onFailure { Timber.w(it, "failed migrating archive/${f.name}") }
                    }
                }
            }
        }
        Timber.d("migrated $copied internal track files to external")
        return copied
    }
}
