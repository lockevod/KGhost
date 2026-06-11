package com.enderthor.kghost

import android.app.Application
import android.util.Log
import timber.log.Timber

class KGhostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // File logging (off until the rider enables it in settings) — lets a ride be studied later
        // without adb/logcat. Planted always; gated by FileLogTree.enabled, so it costs nothing when off.
        // This is now the way to get diagnostics from a RELEASE build, so debugmode can stay false: a
        // debug build still logs to logcat (BuildConfig.DEBUG), and a release build is quiet (WARN+ only)
        // until the rider flips the settings toggle, which captures everything to the file.
        FileLogTree.start(this)
        Timber.plant(FileLogTree)
        val debugmode = false
        if (BuildConfig.DEBUG || debugmode) Timber.plant(Timber.DebugTree())
        else Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.WARN) Log.println(priority, tag ?: "KGhost", message)
            }
        })
    }
}
