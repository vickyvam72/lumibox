package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        // v2: pencocokan lebih ketat — hasil lama (yang bisa salah judul) dibuang.
        context.deleteSharedPreferences("lumibox_resolve")
        prefs = context.getSharedPreferences("lumibox_resolve2", Context.MODE_PRIVATE)
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
        // Judul alternatif TMDB disiapkan bersamaan dengan pencarian pertama (hemat waktu).
        val altsJob = bg.async { runCatching { Tmdb.altTitles(t) }.getOrDefault(emptyList()) }
        var match = findMatch(t.title, t)
        if (match == null && t.imdbId != null && t.imdbId.startsWith("tt")) {
            val original = Addon.originalName(if (t.isSeries) "series" else "movie", t.imdbId)
            if (original != null && names.add(original)) match = findMatch(original, t)
        }
        if (match == null) {
            // Judul Inggris / asli / alternatif dari TMDB (mis. "Teluh Darah" → "Blood Curse")
            val alts = altsJob.await().filter { names.add(it) }.take(5)
            // Semua nama alternatif dicari bersamaan; yang pertama (urutan prioritas) cocok dipakai.
            val found = kotlinx.coroutines.coroutineScope {
                alts.map { alt -> async { findMatch(alt, t) } }.map { it.await() }
            }
            match = found.firstOrNull { it != null }
        }
        if (match == null) throw IOException("\"${t.title}\" belum tersedia di MovieBox.")
        cacheKey(t)?.let { prefs.edit().putString(it, match).apply() }
        return match
    }

    private suspend fun findMatch(name: String, t: Title, exclude: Set<String> = emptySet()): String? =
        candidates(name, t, exclude).firstOrNull()

    /** Kandidat subject yang cocok, yang paling cocok dulu; serial kosong (0 episode) ditaruh paling akhir. */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Cari di dua jalur. Jalur lama biasanya cepat: kalau di sana sudah ada film yang judul
     * & tahunnya persis sama, tidak perlu menunggu jalur web (yang bisa sampai 10 detik).
     */
    private suspend fun searchFast(name: String, t: Title, full: Boolean = false): List<Title> {
        val fresh = bg.async { runCatching { Repo.searchNew(name) }.getOrDefault(emptyList()) }
        val legacy = runCatching { Repo.searchLegacy(name) }.getOrDefault(emptyList())
        if (!full) {
            val wanted = normalize(name)
            val sure = legacy.filter { it.isSeries == t.isSeries && score(wanted, t.year, it) >= 6 }
            if (sure.isNotEmpty()) {
                if (!t.isSeries) return legacy
                // Serial: pastikan entri yang cocok memang punya episode (ada entri kembar yang kosong)
                val withEpisodes = sure.take(2).any { c ->
                    runCatching { Repo.details(c.id) }.getOrNull()?.seasons?.any { it.episodes.isNotEmpty() } == true
                }
                if (withEpisodes) return legacy
            }
        }
        return Repo.rankResults(name, fresh.await() + legacy)
    }

    private suspend fun candidates(name: String, t: Title, exclude: Set<String>, full: Boolean = false): List<String> {
        val results = searchFast(name, t, full)
        val wanted = normalize(name)
        val ranked = results
            .filter { it.id !in exclude }
            // jenis (film/serial) kadang salah label di MovieBox → beda jenis tetap boleh, tapi nilainya dikurangi
            .map { it to score(wanted, t.year, it) - if (it.isSeries == t.isSeries) 0 else 1 }
            .filter { it.second >= 4 }
            .sortedByDescending { it.second }
            .map { it.first.id }
        if (!t.isSeries || ranked.size <= 1) return ranked
        // MovieBox kadang punya entri serial kembar yang kosong → utamakan yang punya episode.
        val withEpisodes = kotlinx.coroutines.coroutineScope {
            ranked.take(4).map { id ->
                async { id to (runCatching { Repo.details(id) }.getOrNull()?.seasons?.any { it.episodes.isNotEmpty() } == true) }
            }.map { it.await() }.filter { it.second }.map { it.first }
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
            out += candidates(n, t, exclude + out, full = true)
            if (out.size >= 4) break
        }
        return out.toList().take(4)
    }

    private val checkLimit = kotlinx.coroutines.sync.Semaphore(3)
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

    /** Sudah pernah dicek dan tidak ada di MovieBox (dalam 6 jam terakhir)? */
    fun knownMissing(t: Title): Boolean {
        if (cached(t) != null) return false
        val key = cacheKey(t) ?: return false
        return System.currentTimeMillis() - prefs.getLong("miss:$key", 0L) < MISS_TTL
    }

    /** Buang yang sudah pasti tidak ada (instan, tanpa jaringan). */
    fun notMissing(list: List<Title>): List<Title> = list.filterNot(::knownMissing)

    /**
     * Cek di latar: setiap judul yang ternyata tidak ada di MovieBox dilaporkan lewat [onMissing],
     * jadi daftar bisa langsung tampil lalu dirapikan sambil jalan.
     */
    suspend fun verify(list: List<Title>, onMissing: (Title) -> Unit) = kotlinx.coroutines.coroutineScope {
        list.filter { cached(it) == null }.forEach { t ->
            launch { if (!available(t)) onMissing(t) }
        }
    }

    /** Saring daftar: hanya judul yang ada di MovieBox, urutan tetap. */
    suspend fun filterAvailable(list: List<Title>): List<Title> = kotlinx.coroutines.coroutineScope {
        list.map { t -> async { t to available(t) } }.map { it.await() }.filter { it.second }.map { it.first }
    }

    // ---- ingat judul yang terbukti ada videonya (tombol Putar langsung muncul saat dibuka lagi)
    private const val SOURCE_TTL = 12 * 60 * 60 * 1000L

    fun sourceKnown(subjectId: String, s: Int, e: Int): Boolean =
        System.currentTimeMillis() - prefs.getLong("src:$subjectId:$s:$e", 0L) < SOURCE_TTL

    fun markSource(subjectId: String, s: Int, e: Int, ok: Boolean) {
        val k = "src:$subjectId:$s:$e"
        if (ok) prefs.edit().putLong(k, System.currentTimeMillis()).apply() else prefs.edit().remove(k).apply()
    }

    /** Simpan subject yang terbukti bisa diputar. */
    fun remember(t: Title, subjectId: String) {
        cacheKey(t)?.let { prefs.edit().putString(it, subjectId).apply() }
    }

    private fun score(wanted: String, year: String?, candidate: Title): Int {
        val title = normalize(candidate.title)
        // Ketat: harus sama persis, atau salah satu awalan yang lain dan panjangnya mirip
        // (mis. "Sore" vs "Sore: Istri dari Masa Depan"). Sekadar memuat satu kata tidak cukup.
        val shorter = minOf(title.length, wanted.length).toDouble()
        val longer = maxOf(title.length, wanted.length).coerceAtLeast(1).toDouble()
        val prefix = title.startsWith(wanted) || wanted.startsWith(title)
        val nextChar = if (title.length > wanted.length) title.getOrNull(wanted.length) else wanted.getOrNull(title.length)
        var s = when {
            title == wanted -> 4
            prefix && shorter >= 4 && (nextChar == ' ' || shorter / longer >= 0.6) -> 2
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
