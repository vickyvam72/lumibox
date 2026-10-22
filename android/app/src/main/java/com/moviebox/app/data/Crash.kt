package com.moviebox.app.data

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Menyimpan laporan crash terakhir supaya bisa disalin & dikirim saat aplikasi dibuka lagi. */
object Crash {
    private const val PREFS = "lumibox_crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull()
                val report = "Lumi Box $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                    "Thread: ${thread.name}\n\n" + sw.toString().take(12_000)
                // commit() supaya tersimpan sebelum proses berhenti
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", report).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last").apply()
    }
}
