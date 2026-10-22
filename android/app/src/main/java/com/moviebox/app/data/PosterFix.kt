package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * Poster pengganti per judul. Dipakai kalau poster dari TMDB salah (data TMDB-nya keliru):
 * pengguna menekan "Poster salah?" di halaman info → poster MovieBox dipakai di semua tempat.
 */
object PosterFix {
    private lateinit var prefs: SharedPreferences
    private val map = mutableStateMapOf<String, String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_posterfix", Context.MODE_PRIVATE)
        prefs.all.forEach { (k, v) -> (v as? String)?.let { map[k] = it } }
    }

    fun get(t: Title): String? = map[t.key] ?: t.tmdbId?.let { map["tmdb:${if (t.isSeries) "tv" else "mv"}:$it"] }

    fun set(t: Title, url: String?) {
        val keys = listOfNotNull(t.key, t.tmdbId?.let { "tmdb:${if (t.isSeries) "tv" else "mv"}:$it" })
        val e = prefs.edit()
        keys.forEach { k ->
            if (url == null) { map.remove(k); e.remove(k) } else { map[k] = url; e.putString(k, url) }
        }
        e.apply()
    }
}
