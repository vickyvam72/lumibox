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

    // ------------------------------------------------------------ pembukaan aman

    /**
     * Dipanggil paling awal. Kalau dua pembukaan terakhir gagal (crash / crash native / macet)
     * sebelum aplikasi sempat stabil (sekali gagal sudah cukup), cache dibersihkan otomatis — riwayat, Daftar Saya,
     * suka/tidak suka, unduhan, dan kunci TMDB tetap aman.
     */
    fun safeStart(context: Context) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        recordExitReason(app)
        val failed = p.getInt("unstable_starts", 0)
        if (failed >= 1) {
            runCatching { clearCaches(app) }
            p.edit().putInt("unstable_starts", 0).putBoolean("recovered", true).commit()
        } else {
            p.edit().putInt("unstable_starts", failed + 1).commit()
        }
    }

    /** Aplikasi sudah berjalan normal beberapa detik → pembukaan dianggap berhasil. */
    fun markStable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("unstable_starts", 0).apply()
    }

    /** Sekali saja: apakah cache baru saja dibersihkan otomatis. */
    fun consumeRecovered(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val r = p.getBoolean("recovered", false)
        if (r) p.edit().remove("recovered").apply()
        return r
    }

    private fun clearCaches(app: Context) {
        for (name in listOf("lumibox_resolve2", "lumibox_translate", "lumibox_addon")) {
            app.deleteSharedPreferences(name)
        }
        val lib = app.getSharedPreferences("lumibox_library", Context.MODE_PRIVATE)
        val e = lib.edit()
        lib.all.keys.filter { it.startsWith("cache_") }.forEach { e.remove(it) }
        e.commit()
        app.cacheDir.deleteRecursively()
        java.io.File(app.filesDir, "data").deleteRecursively()
    }

    /** Alasan proses terakhir berhenti (termasuk crash native & macet/ANR) — Android 11+. */
    private fun recordExitReason(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull() ?: return
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (info.timestamp <= p.getLong("exit_seen", 0L)) return
            p.edit().putLong("exit_seen", info.timestamp).commit()
            val reason = when (info.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash native"
                android.app.ApplicationExitInfo.REASON_ANR -> "macet (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "memori habis"
                else -> return
            }
            // Laporan Java yang lebih lengkap sudah tersimpan untuk crash biasa.
            if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH && last(app) != null) return
            val trace = runCatching {
                info.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
            val report = "Lumi Box $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                "Berhenti karena: $reason\n${info.description.orEmpty()}\n\n" + trace.take(12_000)
            p.edit().putString("last", report).commit()
        }
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last").apply()
    }
}
