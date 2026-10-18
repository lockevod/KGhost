package com.enderthor.kghost.fake

import android.os.Build
import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * Robolectric does not shadow `Environment.isExternalStorageManager()`, and the real method indexes an
 * empty external-dirs array, so KGhostApplication.onCreate (FileLogTree → StoragePermission) crashes
 * before any test code runs. Answer "no all-files access", the state a fresh install is in; every
 * other Environment call keeps Robolectric's own shadow.
 */
@Implements(Environment::class)
class ShadowNoAllFilesAccess : ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation(minSdk = Build.VERSION_CODES.R)
        fun isExternalStorageManager(): Boolean = false
    }
}
