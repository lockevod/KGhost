package com.enderthor.kghost

import android.app.Application
import android.util.Log
import timber.log.Timber

class KGhostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val debugmode = true
        if (BuildConfig.DEBUG || debugmode) Timber.plant(Timber.DebugTree())
        else Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.WARN) Log.println(priority, tag ?: "KGhost", message)
            }
        })
    }
}
