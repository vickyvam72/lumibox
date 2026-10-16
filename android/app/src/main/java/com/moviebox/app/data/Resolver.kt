package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.text.Normalizer

/**
 * Mencocokkan judul dari addon (ID IMDb) ke subject MovieBox:
 * cari berdasarkan judul, lalu nilai kecocokan judul + tahun + jenis.
 * Hasil disimpan supaya pencarian berikutnya instan.
 */
object Resolver {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_resolve", Context.MODE_PRIVATE)
    }

    fun cached(t: Title): String? {
        if (t.id.isNotBlank()) return t.id
        val imdb = t.imdbId ?: return null
        return prefs.getString(imdb, null)
    }

    suspend fun subjectId(t: Title): String {
        cached(t)?.let { return it }
        val names = linkedSetOf(t.title)
        var match = findMatch(t.title, t)
        if (match == null && t.imdbId != null && t.imdbId.startsWith("tt")) {
            val original = Addon.originalName(if (t.isSeries) "series" else "movie", t.imdbId)
            if (original != null && names.add(original)) match = findMatch(original, t)
        }
        if (match == null) throw IOException("\"${t.title}\" belum tersedia di MovieBox.")
        t.imdbId?.let { prefs.edit().putString(it, match).apply() }
        return match
    }

    private suspend fun findMatch(name: String, t: Title): String? {
        val results = runCatching { Repo.search(name) }.getOrDefault(emptyList())
        val wanted = normalize(name)
        val best = results
            .filter { it.isSeries == t.isSeries }
            .map { it to score(wanted, t.year, it) }
            .filter { it.second >= 3 }
            .maxByOrNull { it.second }
        return best?.first?.id
    }

    private fun score(wanted: String, year: String?, candidate: Title): Int {
        val title = normalize(candidate.title)
        var s = when {
            title == wanted -> 4
            title.startsWith(wanted) || wanted.startsWith(title) -> 2
            title.contains(wanted) || wanted.contains(title) -> 1
            else -> 0
        }
        if (s == 0) return 0
        val y1 = year?.toIntOrNull()
        val y2 = candidate.year?.toIntOrNull()
        if (y1 != null && y2 != null) {
            s += when (kotlin.math.abs(y1 - y2)) {
                0 -> 2
                1 -> 1
                else -> -2
            }
        }
        return s
    }

    private fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removePrefix("the ")
            .replace(Regex("\\s+"), " ")
    }
}
