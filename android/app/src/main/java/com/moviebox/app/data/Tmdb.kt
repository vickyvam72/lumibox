package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Info tambahan dari TMDB. */
data class TmdbInfo(
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val genres: List<String>,
    val score: String?,
    val ageRating: String?,
    val runtimeMin: Int?,
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

    private fun cacheKey(t: Title) = t.imdbId ?: "${t.title}|${t.year}|${t.isSeries}"

    private fun load(t: Title): TmdbInfo? {
        val type = if (t.isSeries) "tv" else "movie"
        val id = findId(t, type) ?: return null

        val append = if (t.isSeries) "images,content_ratings" else "images,release_dates"
        val detail = get(
            "/$type/$id",
            mapOf(
                "language" to "id-ID",
                "append_to_response" to append,
                "include_image_language" to "id,en,null",
            ),
        )
        val originalLang = detail.str("original_language")

        // Poster: id → en → tanpa teks (null) → bahasa asli
        val posters = detail.optJSONObject("images")?.optJSONArray("posters").objects()
        fun pickPoster(): String? {
            for (lang in listOf("id", "en", null, originalLang)) {
                val p = posters.firstOrNull { it.str("iso_639_1") == lang }?.str("file_path")
                if (p != null) return p
            }
            return detail.str("poster_path")
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
            poster = pickPoster()?.let { "$IMG/w500$it" },
            backdrop = backdrop?.let { "$IMG/w1280$it" },
            overview = overview,
            genres = genres,
            score = score,
            ageRating = age?.let(::ageLabel),
            runtimeMin = runtime,
        )
    }

    private fun findId(t: Title, type: String): String? {
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
