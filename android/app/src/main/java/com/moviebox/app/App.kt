package com.moviebox.app

import android.app.Application
import android.util.Log
import com.moviebox.app.data.Library
import com.moviebox.app.data.Native

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Arahkan folder config/cache core Rust ke folder privat aplikasi.
        val result = Native.initCore(filesDir.absolutePath, cacheDir.absolutePath)
        Log.i("LumiBox", "core init: $result")
        Library.init(this)
    }
}
