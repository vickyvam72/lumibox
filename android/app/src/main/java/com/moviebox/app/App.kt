package com.moviebox.app

import android.app.Application
import android.util.Log
import com.moviebox.app.data.Addon
import com.moviebox.app.data.Library
import com.moviebox.app.data.Native
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Resolver
import com.moviebox.app.data.Tmdb

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.moviebox.app.data.Crash.install(this)
        com.moviebox.app.data.Crash.safeStart(this)
        // Arahkan folder config/cache core Rust ke folder privat aplikasi.
        val result = Native.initCore(filesDir.absolutePath, cacheDir.absolutePath)
        Log.i("LumiBox", "core init: $result")
        Library.init(this)
        Addon.init(this)
        Resolver.init(this)
        Tmdb.init(this)
        com.moviebox.app.data.ContentFilter.init(this)
        com.moviebox.app.data.PosterFix.init(this)
        com.moviebox.app.data.Translator.init(this)
        com.moviebox.app.data.Downloads.init(this)
        // Siapkan token/sesi MovieBox di latar supaya putar pertama lebih cepat.
        Repo.warmup()
    }
}
