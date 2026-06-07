package com.enderthor.kvpartner.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permission seam for reaching SHARED external storage with a plain File. On API 30+ this needs
 * "All files access" (MANAGE_EXTERNAL_STORAGE); below that it falls back to the legacy runtime
 * WRITE_EXTERNAL_STORAGE permission. Requested lazily — only when the feature that needs shared
 * storage actually runs. Mirrors KSafe's BackupStorage permission helper.
 */
object StoragePermission {

    /** True if the app can read/write shared external storage (All-files-access on API 30+). */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Intent to send the user to the system "All files access" settings screen for THIS app.
     * Prefers the app-specific deep-link; if no Activity can handle it (some Karoo OS builds
     * don't expose the per-app screen) it falls back to the global All-files-access list.
     */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (appIntent.resolveActivity(context.packageManager) != null) return appIntent

            return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Below API 30 there is no All-files-access screen; point at this app's details page so
        // the user can grant the legacy WRITE_EXTERNAL_STORAGE permission.
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
