package com.moviebox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** API web MovieBox (h5) untuk daftar per genre/negara — sama seperti /movies & /tv-series di Moviebox-API. */
object MovieBoxWeb {
    private const val API = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
    const val TAB_MOVIES = 2
    const val TAB_SERIES = 5

    private val cache = ConcurrentHashMap<String, List<Title>>()

    suspend fun filter(
        tabId: Int,
        genre: String = "ALL",
        country: String = "ALL",
        page: Int = 1,
        perPage: Int = 24,
        year: String = "ALL",
    ): List<Title> {
        val key = "$tabId|$genre|$country|$page|$year"
        cache[key]?.let { return it }
        val body = JSONObject()
            .put("tabId", tabId)
            .put(
                "filter",
                JSONObject()
                    .put("sort", "RECOMMEND")
                    .put("genre", genre)
                    .put("country", country)
                    .put("year", year)
                    .put("language", "ALL"),
            )
            .put("page", page)
            .put("perPage", perPage)
        val items = withContext(Dispatchers.IO) {
            val json = post("$API/subject/filter", body)
            val data = json.optJSONObject("data") ?: JSONObject()
            val arr = data.optJSONArray("items") ?: data.optJSONArray("subjects") ?: data.optJSONArray("list")
            arr.objects().mapNotNull { o ->
                val s = o.optJSONObject("subject") ?: o
                val id = s.opt("subjectId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val title = s.str("title") ?: return@mapNotNull null
                Title(
                    id = id,
                    title = title,
                    isSeries = s.optInt("subjectType", if (tabId == TAB_SERIES) 2 else 1) == 2,
                    year = s.str("releaseDate")?.take(4),
                    poster = s.optJSONObject("cover")?.str("url"),
                    rating = s.str("imdbRatingValue"),
                    genres = Genres.normalize(listOfNotNull(s.str("genre"))),
                )
            }
        }.filterNot { isDubbed(it.title) }.distinctBy { it.id }
        cache[key] = items
        return items
    }

    private val dubTags = listOf("hindi", "tamil", "telugu", "bengali", "kannada", "malayalam", "dub")

    private fun isDubbed(title: String): Boolean {
        val tags = Regex("[\\[(]([^\\])]*)[\\])]").findAll(title).map { it.groupValues[1].lowercase() }
        return tags.any { tag -> dubTags.any { tag.contains(it) } }
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://moviebox.ph/")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("MovieBox HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
