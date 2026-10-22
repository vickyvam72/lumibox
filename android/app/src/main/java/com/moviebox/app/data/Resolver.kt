package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
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

    private fun cacheKey(t: Title): String? = t.imdbId ?: t.tmdbId?.let { "tmdb:${if (t.isSeries) "tv" else "movie"}:$it" }

    fun cached(t: Title): String? {
        if (t.id.isNotBlank()) return t.id
        val key = cacheKey(t) ?: return null
        return prefs.getString(key, null)
    }

    suspend fun subjectId(t: Title): String {
        val known = cached(t)
        if (known != null) {
            if (!t.isSeries) return known
            // Serial: pastikan entri ini memang punya episode (ada entri kembar yang kosong).
            val d = runCatching { Repo.details(known) }.getOrNull()
            if (d == null || d.seasons.any { it.episodes.isNotEmpty() }) return known
            val name = t.title.ifBlank { d.title }
            val better = if (name.isNotBlank()) findMatch(name, t.copy(title = name), exclude = setOf(known)) else null
            if (better != null) {
                cacheKey(t)?.let { prefs.edit().putString(it, better).apply() }
                return better
            }
            return known
        }
        val names = linkedSetOf(t.title)
        var match = findMatch(t.title, t)
        if (match == null && t.imdbId != null && t.imdbId.startsWith("tt")) {
            val original = Addon.originalName(if (t.isSeries) "series" else "movie", t.imdbId)
            if (original != null && names.add(original)) match = findMatch(original, t)
        }
        if (match == null) {
            // Judul Inggris / asli / alternatif dari TMDB (mis. "Teluh Darah" → "Blood Curse")
            for (alt in Tmdb.altTitles(t)) {
                if (!names.add(alt)) continue
                match = findMatch(alt, t)
                if (match != null) break
                if (names.size > 6) break
            }
        }
        if (match == null) throw IOException("\"${t.title}\" belum tersedia di MovieBox.")
        cacheKey(t)?.let { prefs.edit().putString(it, match).apply() }
        return match
    }

    private suspend fun findMatch(name: String, t: Title, exclude: Set<String> = emptySet()): String? =
        candidates(name, t, exclude).firstOrNull()

    /** Kandidat subject yang cocok, yang paling cocok dulu; serial kosong (0 episode) ditaruh paling akhir. */
    private suspend fun candidates(name: String, t: Title, exclude: Set<String>): List<String> {
        val results = runCatching { Repo.search(name) }.getOrDefault(emptyList())
        val wanted = normalize(name)
        val ranked = results
            .filter { it.id !in exclude }
            // jenis (film/serial) kadang salah label di MovieBox → beda jenis tetap boleh, tapi nilainya dikurangi
            .map { it to score(wanted, t.year, it) - if (it.isSeries == t.isSeries) 0 else 1 }
            .filter { it.second >= 3 }
            .sortedByDescending { it.second }
            .map { it.first.id }
        if (!t.isSeries || ranked.size <= 1) return ranked
        // MovieBox kadang punya entri serial kembar yang kosong → utamakan yang punya episode.
        val withEpisodes = ranked.take(4).filter { id ->
            runCatching { Repo.details(id) }.getOrNull()?.seasons?.any { it.episodes.isNotEmpty() } == true
        }
        return (withEpisodes + ranked).distinct()
    }

    /** Subject lain dengan judul sama (dipakai kalau subject pertama tidak punya video). */
    suspend fun alternatives(t: Title, exclude: Set<String>): List<String> {
        val out = linkedSetOf<String>()
        val names = linkedSetOf<String>()
        if (t.title.isNotBlank()) names += t.title
        names += Tmdb.altTitles(t).take(3)
        for (n in names) {
            out += candidates(n, t, exclude + out)
            if (out.size >= 4) break
        }
        return out.toList().take(4)
    }

    private val checkLimit = kotlinx.coroutines.sync.Semaphore(6)
    private const val MISS_TTL = 6 * 60 * 60 * 1000L

    /** Sudah pasti ada di MovieBox (tersimpan dari pencarian sebelumnya)? */
    fun knownAvailable(t: Title): Boolean = cached(t) != null

    /** Cek judul memang ada di MovieBox (hasil disimpan; yang tidak ada dicek ulang 6 jam lagi). */
    suspend fun available(t: Title): Boolean {
        if (cached(t) != null) return true
        val key = cacheKey(t) ?: return true
        val missAt = prefs.getLong("miss:$key", 0L)
        if (System.currentTimeMillis() - missAt < MISS_TTL) return false
        val ok = checkLimit.withPermit { runCatching { subjectId(t) }.isSuccess }
        if (!ok) prefs.edit().putLong("miss:$key", System.currentTimeMillis()).apply()
        return ok
    }

    /** Saring daftar: hanya judul yang ada di MovieBox, urutan tetap. */
    suspend fun filterAvailable(list: List<Title>): List<Title> = kotlinx.coroutines.coroutineScope {
        list.map { t -> async { t to available(t) } }.map { it.await() }.filter { it.second }.map { it.first }
    }

    /** Simpan subject yang terbukti bisa diputar. */
    fun remember(t: Title, subjectId: String) {
        cacheKey(t)?.let { prefs.edit().putString(it, subjectId).apply() }
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
