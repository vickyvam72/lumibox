package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Info tambahan dari TMDB. */
data class TmdbInfo(
    val title: String?,
    val logo: String?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val genres: List<String>,
    val score: String?,
    val ageRating: String?,
    val runtimeMin: Int?,
    val originalLanguage: String? = null,
    val originCountry: String? = null,
)

/**
 * TMDB (butuh kunci API gratis dari themoviedb.org, diisi di "Lumi Saya").
 * Poster & deskripsi: Indonesia → Inggris → bahasa asli.
 */
object Tmdb {
    private lateinit var prefs: SharedPreferences
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    var apiKey by mutableStateOf("")
        private set

    private val cache = ConcurrentHashMap<String, TmdbInfo>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = apiKey.isNotBlank()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_tmdb", Context.MODE_PRIVATE)
        apiKey = prefs.getString("key", "").orEmpty()
    }

    fun setKey(key: String) {
        apiKey = key.trim()
        prefs.edit().putString("key", apiKey).apply()
        cache.clear()
        misses.clear()
    }

    /** Cek kunci: true kalau TMDB menerima kunci ini. */
    suspend fun testKey(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { get("/configuration", emptyMap(), key.trim()) }.isSuccess
    }

    fun cached(t: Title): TmdbInfo? = cache[cacheKey(t)]

    suspend fun info(t: Title): TmdbInfo? {
        if (!enabled) return null
        val key = cacheKey(t)
        cache[key]?.let { return it }
        if (key in misses) return null
        val result = withContext(Dispatchers.IO) { runCatching { load(t) }.getOrNull() }
        if (result == null) misses.add(key) else cache[key] = result
        return result
    }

    private fun cacheKey(t: Title) = (t.imdbId ?: t.tmdbId?.let { "tmdb$it" } ?: "${t.title}|${t.year}") + "|" + (if (t.isSeries) "tv" else "mv")

    /**
     * Daftar rilis terbaru dari TMDB (cadangan kalau addon tidak bisa dibuka).
     * local = asal Indonesia; global = selain Indonesia, rilis 6 bulan terakhir yang cukup dikenal.
     */
    suspend fun latest(series: Boolean, local: Boolean): List<Title> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val today = java.time.LocalDate.now()
        val from = today.minusMonths(if (local) 18 else 8)
        val type = if (series) "tv" else "movie"
        val dateKey = if (series) "first_air_date" else "primary_release_date"
        val params = mutableMapOf(
            "language" to "en-US",
            "sort_by" to "popularity.desc",
            "$dateKey.gte" to from.toString(),
            "include_adult" to "false",
        )
        applyQuality(params, series, local)
        val out = mutableListOf<Title>()
        for (page in 1..3) {
            params["page"] = page.toString()
            out += parseResults(runCatching { get("/discover/$type", params) }.getOrNull(), series)
        }
        out.distinctBy { it.key }
    }

    /**
     * Hanya judul yang sudah bisa ditonton di rumah:
     * film = sudah rilis digital/fisik/TV (bukan yang masih di bioskop), minimal 60 menit;
     * lokal = produksi & bahasa asli Indonesia (drama dubbing / sulih suara tidak ikut).
     */
    private fun applyQuality(params: MutableMap<String, String>, series: Boolean, local: Boolean) {
        val today = java.time.LocalDate.now().toString()
        if (series) {
            params["first_air_date.lte"] = today
        } else {
            params["with_release_type"] = "4|5|6"
            params["release_date.lte"] = today
            params["with_runtime.gte"] = "60"
        }
        if (local) {
            params["with_origin_country"] = "ID"
            params["with_original_language"] = "id"
            params["vote_count.gte"] = "1"
        } else {
            params["without_origin_country"] = "ID"
            params["vote_count.gte"] = if (series) "20" else "50"
        }
    }


    /** Disembunyikan sesuai Setting (drama pendek / India). */
    fun isJunk(t: Title): Boolean = !ContentFilter.allows(t)

    private fun parseResults(json: JSONObject?, series: Boolean?): List<Title> =
        json?.optJSONArray("results").objects().mapNotNull { r ->
            val isSeries = series ?: (r.str("media_type") == "tv")
            val poster = r.str("poster_path") ?: return@mapNotNull null
            val lang = r.str("original_language")
            // lokal → judul asli Indonesia; selain itu judul dari permintaan (bahasa Inggris)
            val name = (if (lang == "id") r.str("original_title") ?: r.str("original_name") else null)
                ?: r.str("title") ?: r.str("name") ?: return@mapNotNull null
            val date = r.str("release_date") ?: r.str("first_air_date")
            val vote = r.optDouble("vote_average", 0.0)
            val genreIds = r.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.opt(it).toString() } }.orEmpty()
            Title(
                id = "",
                title = name,
                isSeries = isSeries,
                year = date?.take(4),
                poster = "$IMG/w500$poster",
                backdrop = r.str("backdrop_path")?.let { "$IMG/w1280$it" },
                rating = if (vote > 0) "%.1f".format(java.util.Locale.US, vote) else null,
                description = r.str("overview"),
                genres = Genres.normalize(genreIds),
                tmdbId = r.opt("id")?.toString(),
                lang = r.str("original_language"),
                country = r.optJSONArray("origin_country")?.optString(0)?.takeIf { it.isNotBlank() },
            )
        }

    /** Judul per genre (populer, cukup banyak penilaian) — supaya isi kategori sesuai genrenya. */
    suspend fun byGenre(label: String, series: Boolean, page: Int, year: Int? = null, local: Boolean? = null): List<Title> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val ids = Genres.tmdbFor(label, series) ?: return@withContext emptyList()
        val params = mutableMapOf(
            "language" to "en-US",
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "vote_count.gte" to if (series) "30" else "80",
            "with_genres" to ids,
            "page" to page.toString(),
        )
        if (year != null) params[if (series) "first_air_date_year" else "primary_release_year"] = year.toString()
        if (!series) {
            params["with_release_type"] = "4|5|6"
            params["release_date.lte"] = java.time.LocalDate.now().toString()
        }
        when (local) {
            true -> {
                params["with_origin_country"] = "ID"
                params["with_original_language"] = "id"
                params["vote_count.gte"] = "1"
            }
            false -> params["without_origin_country"] = "ID"
            null -> Unit
        }
        if (label == "Anime") params["with_original_language"] = "ja"
        if (label == "Animasi") params["without_original_language"] = "ja"
        parseResults(runCatching { get("/discover/${if (series) "tv" else "movie"}", params) }.getOrNull(), series)
    }

    /** Top 10: paling populer di antara yang rilis 12 bulan terakhir (lokal 24 bulan) dan sudah bisa ditonton. */
    suspend fun top(series: Boolean, local: Boolean): List<Title> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val today = java.time.LocalDate.now()
        val dateKey = if (series) "first_air_date" else "primary_release_date"
        val params = mutableMapOf(
            "language" to "en-US",
            "sort_by" to "popularity.desc",
            "$dateKey.gte" to today.minusMonths(if (local) 24 else 12).toString(),
            "include_adult" to "false",
        )
        applyQuality(params, series, local)
        val out = mutableListOf<Title>()
        for (page in 1..2) {
            params["page"] = page.toString()
            out += parseResults(runCatching { get("/discover/${if (series) "tv" else "movie"}", params) }.getOrNull(), series)
        }
        out.distinctBy { it.key }
    }

    /** Rilis terbaru: urut tanggal rilis (paling baru dulu), hanya yang sudah rilis digital. */
    suspend fun newest(series: Boolean, local: Boolean): List<Title> = withContext(Dispatchers.IO) {
        if (series) {
            val out = mutableListOf<Title>()
            for (page in 1..2) out += discover(series, local, null, page)
            return@withContext out.distinctBy { it.key }
        }
        val all = mutableListOf<Title>()
        for (page in 1..3) {
            val p = newestRaw(local, page)
            all += p
            if (p.size < 20) break
        }
        sortByDigital(all.distinctBy { it.key })
    }

    /** Satu halaman "rilis digital terbaru" (untuk Lainnya), urut tanggal rilis digital. */
    suspend fun newestPage(local: Boolean, page: Int): List<Title> = withContext(Dispatchers.IO) {
        sortByDigital(newestRaw(local, page))
    }

    /** Film yang rilis DIGITAL (streaming/DVD) dalam beberapa bulan terakhir. */
    private fun newestRaw(local: Boolean, page: Int): List<Title> {
        if (!enabled) return emptyList()
        val today = java.time.LocalDate.now()
        val params = mutableMapOf(
            "language" to "en-US",
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "page" to page.toString(),
        )
        applyQuality(params, false, local)
        params["with_release_type"] = "4|5"
        params["release_date.gte"] = today.minusDays(if (local) 365 else 120).toString()
        params["release_date.lte"] = today.toString()
        return parseResults(runCatching { get("/discover/movie", params) }.getOrNull(), false)
    }

    private val digitalDates = ConcurrentHashMap<String, String>()

    /** Tanggal rilis digital pertama (tipe 4 digital / 5 fisik) sebuah film. */
    private fun digitalDate(tmdbId: String): String? {
        digitalDates[tmdbId]?.let { return it.ifEmpty { null } }
        val today = java.time.LocalDate.now().toString()
        val res = runCatching { get("/movie/$tmdbId/release_dates", emptyMap()) }.getOrNull() ?: return null
        val date = res.optJSONArray("results").objects()
            .flatMap { it.optJSONArray("release_dates").objects() }
            .filter { it.optInt("type") == 4 || it.optInt("type") == 5 }
            .mapNotNull { it.str("release_date")?.take(10) }
            .filter { it <= today }
            .minOrNull()
        digitalDates[tmdbId] = date ?: ""
        return date
    }

    /** Urutkan dari rilis digital paling baru. */
    private suspend fun sortByDigital(list: List<Title>): List<Title> = kotlinx.coroutines.coroutineScope {
        val limit = kotlinx.coroutines.sync.Semaphore(6)
        val dated = list.map { t ->
            async {
                val d = t.tmdbId?.let { id -> limit.acquire(); try { digitalDate(id) } finally { limit.release() } }
                t to (d ?: "")
            }
        }.map { it.await() }
        dated.sortedByDescending { it.second }.map { it.first }
    }

    /** Daftar lengkap Lokal/Global (terbaru dulu), opsional per tahun — untuk halaman "Lainnya". */
    suspend fun discover(series: Boolean, local: Boolean, year: Int?, page: Int): List<Title> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val dateKey = if (series) "first_air_date" else "primary_release_date"
        val params = mutableMapOf(
            "language" to "en-US",
            "sort_by" to if (year != null) "popularity.desc" else "$dateKey.desc",
            "include_adult" to "false",
            "$dateKey.lte" to java.time.LocalDate.now().toString(),
            "page" to page.toString(),
        )
        if (year != null) params[if (series) "first_air_date_year" else "primary_release_year"] = year.toString()
        applyQuality(params, series, local)
        parseResults(runCatching { get("/discover/${if (series) "tv" else "movie"}", params) }.getOrNull(), series)
    }

    /** Rekomendasi TMDB dari satu judul yang disukai. */
    suspend fun recommendations(t: Title): List<Title> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val type = if (t.isSeries) "tv" else "movie"
        runCatching {
            val id = findId(t, type) ?: return@runCatching emptyList()
            parseResults(get("/$type/$id/recommendations", mapOf("language" to "en-US")), t.isSeries)
        }.getOrDefault(emptyList())
    }

    private fun load(t: Title): TmdbInfo? {
        val type = if (t.isSeries) "tv" else "movie"
        val id = findId(t, type) ?: return null

        val append = if (t.isSeries) "images,content_ratings,translations" else "images,release_dates,translations"
        val detail = get(
            "/$type/$id",
            mapOf(
                "language" to "id-ID",
                "append_to_response" to append,
                "include_image_language" to "id,en,null,${originalLangHint(t)}",
            ),
        )
        val originalLang = detail.str("original_language")

        // Poster: id → en → tanpa teks (null) → bahasa asli
        val posters = detail.optJSONObject("images")?.optJSONArray("posters").objects()
        val local = originalLang == "id"
        fun pickPoster(): String? {
            // lokal: poster Indonesia; selain itu: poster Inggris.
            // Pilih poster dengan VOTING terbaik: poster yang salah unggah (mis. poster film lain)
            // biasanya belum punya suara sama sekali, jadi tidak ikut terpilih.
            val order = if (local) listOf("id", null, originalLang, "en") else listOf("en", null, originalLang, "id")
            val candidates = posters.filter { it.str("iso_639_1") in order }
            val best = candidates.sortedWith(
                compareByDescending<JSONObject> { it.optInt("vote_count", 0) > 0 }
                    .thenBy { order.indexOf(it.str("iso_639_1")).coerceAtMost(1) } // utamakan bahasa pilihan / tanpa teks
                    .thenByDescending { it.optDouble("vote_average", 0.0) }
                    .thenByDescending { it.optInt("vote_count", 0) },
            ).firstOrNull()?.str("file_path")
            return best ?: detail.str("poster_path")
        }
        val backdrops = detail.optJSONObject("images")?.optJSONArray("backdrops").objects()
        val backdrop = (
            backdrops.firstOrNull { it.str("iso_639_1") == null }
                ?: backdrops.firstOrNull { it.str("iso_639_1") == "id" }
                ?: backdrops.firstOrNull { it.str("iso_639_1") == "en" }
            )?.str("file_path") ?: detail.str("backdrop_path")

        var overview = detail.str("overview")
        if (overview.isNullOrBlank()) {
            overview = runCatching { get("/$type/$id", mapOf("language" to "en-US")).str("overview") }.getOrNull()
        }

        // Judul: Indonesia → Inggris → asli
        val translations = detail.optJSONObject("translations")?.optJSONArray("translations").objects()
        fun translatedTitle(lang: String): String? = translations
            .firstOrNull { it.str("iso_639_1") == lang }
            ?.optJSONObject("data")
            ?.let { it.str("title") ?: it.str("name") }
            ?.takeIf { it.isNotBlank() }
        val originalTitle = detail.str("original_title") ?: detail.str("original_name")
        val idTitle = translatedTitle("id")
        // Judul: film/serial lokal → bahasa Indonesia; selain itu → bahasa Inggris
        val title = if (local) {
            originalTitle ?: idTitle ?: detail.str("title") ?: detail.str("name")
        } else {
            translatedTitle("en") ?: originalTitle?.takeIf { originalLang == "en" }
                ?: runCatching { get("/$type/$id", mapOf("language" to "en-US")).let { it.str("title") ?: it.str("name") } }.getOrNull()
                ?: originalTitle ?: detail.str("title") ?: detail.str("name")
        }

        // Logo: Indonesia; kalau judul Indonesia ada tapi logonya tidak, pakai teks; lalu Inggris → asli
        val logos = detail.optJSONObject("images")?.optJSONArray("logos").objects()
        fun logoFor(lang: String?) = logos.firstOrNull { it.str("iso_639_1") == lang }?.str("file_path")
        val logo = if (local) {
            logoFor("id") ?: logoFor(null)
        } else {
            logoFor("en") ?: logoFor(null) ?: logoFor(originalLang)
        }

        // Deskripsi Indonesia dari terjemahan kalau overview utama kosong
        if (overview.isNullOrBlank()) {
            overview = translations.firstOrNull { it.str("iso_639_1") == "id" }?.optJSONObject("data")?.str("overview")
        }

        val genres = Genres.normalize(detail.optJSONArray("genres").objects().mapNotNull { it.str("name") })
        val vote = detail.optDouble("vote_average", 0.0)
        val score = if (vote > 0) "%.1f".format(java.util.Locale.US, vote) else null

        val age = if (t.isSeries) {
            val list = detail.optJSONObject("content_ratings")?.optJSONArray("results").objects()
            (list.firstOrNull { it.str("iso_3166_1") == "ID" } ?: list.firstOrNull { it.str("iso_3166_1") == "US" })
                ?.str("rating")
        } else {
            val list = detail.optJSONObject("release_dates")?.optJSONArray("results").objects()
            fun cert(country: String) = list.firstOrNull { it.str("iso_3166_1") == country }
                ?.optJSONArray("release_dates").objects()
                .mapNotNull { it.str("certification") }
                .firstOrNull()
            cert("ID") ?: cert("US")
        }

        val runtime = if (t.isSeries) {
            detail.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        } else {
            detail.optInt("runtime", 0).takeIf { it > 0 }
        }

        return TmdbInfo(
            title = title,
            logo = logo?.let { "$IMG/w500$it" },
            poster = pickPoster()?.let { "$IMG/w500$it" },
            backdrop = backdrop?.let { "$IMG/w1280$it" },
            overview = overview,
            genres = genres,
            score = score,
            ageRating = age?.let(::ageLabel),
            runtimeMin = runtime,
            originalLanguage = originalLang,
            originCountry = detail.optJSONArray("origin_country")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: detail.optJSONArray("production_countries").objects().firstOrNull()?.str("iso_3166_1"),
        )
    }

    private fun originalLangHint(t: Title): String = if (t.tmdbId != null) "ko,ja,zh,th,es,fr,hi" else "ko,ja"

    private fun findId(t: Title, type: String): String? {
        t.tmdbId?.let { return it }
        val imdb = t.imdbId
        if (imdb != null && imdb.startsWith("tt")) {
            val found = get("/find/$imdb", mapOf("external_source" to "imdb_id"))
            val list = found.optJSONArray(if (type == "tv") "tv_results" else "movie_results").objects()
            list.firstOrNull()?.opt("id")?.toString()?.let { return it }
        }
        if (t.title.isBlank()) return null
        val params = mutableMapOf("query" to t.title)
        t.year?.let { params[if (type == "tv") "first_air_date_year" else "year"] = it }
        val results = get("/search/$type", params).optJSONArray("results").objects()
        return results.firstOrNull()?.opt("id")?.toString()
    }

    /** Judul Inggris/asli (untuk mencocokkan ke MovieBox kalau judul Indonesia tidak ketemu). */
    suspend fun englishTitle(t: Title): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val id = t.tmdbId ?: return@withContext null
        runCatching {
            val d = get("/${if (t.isSeries) "tv" else "movie"}/$id", mapOf("language" to "en-US"))
            d.str("title") ?: d.str("name") ?: d.str("original_title") ?: d.str("original_name")
        }.getOrNull()
    }

    /**
     * Semua nama lain sebuah judul (Inggris, asli, judul alternatif per negara) —
     * MovieBox sering memakai judul Inggris, mis. "Teluh Darah" = "Blood Curse".
     */
    suspend fun altTitles(t: Title): List<String> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val type = if (t.isSeries) "tv" else "movie"
        runCatching {
            val id = findId(t, type) ?: return@runCatching emptyList()
            val out = linkedSetOf<String>()
            val en = get("/$type/$id", mapOf("language" to "en-US"))
            listOfNotNull(en.str("title"), en.str("name"), en.str("original_title"), en.str("original_name"))
                .forEach { out += it }
            val alt = runCatching { get("/$type/$id/alternative_titles", emptyMap()) }.getOrNull()
            val list = (alt?.optJSONArray("titles") ?: alt?.optJSONArray("results")).objects()
            val order = listOf("US", "GB", "ID", "AU", "CA")
            list.sortedBy { o -> order.indexOf(o.str("iso_3166_1")).let { if (it < 0) 99 else it } }
                .mapNotNull { it.str("title") }
                .take(8)
                .forEach { out += it }
            out.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** Samakan label rating usia ke gaya Indonesia. */
    private fun ageLabel(raw: String): String = when (raw.uppercase()) {
        "G", "TV-G", "TV-Y", "TV-Y7", "SU" -> "SU"
        "PG", "TV-PG" -> "13+"
        "PG-13", "TV-14" -> "13+"
        "R", "TV-MA" -> "17+"
        "NC-17" -> "21+"
        else -> raw
    }

    private fun get(path: String, params: Map<String, String>, keyOverride: String? = null): JSONObject {
        val key = keyOverride ?: apiKey
        val bearer = key.length > 40 // token "API Read Access" (v4) vs kunci v3
        val query = buildString {
            params.forEach { (k, v) ->
                append(if (isEmpty()) "?" else "&")
                append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
            if (!bearer) append(if (isEmpty()) "?" else "&").append("api_key=").append(key)
        }
        val conn = URL("$API$path$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (bearer) conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (conn.responseCode !in 200..299) throw IOException("TMDB HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
