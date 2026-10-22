package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data lokal pengguna: "Lanjutkan Menonton" dan "Daftar Saya".
 * Disimpan di SharedPreferences, dan berupa state Compose sehingga UI ikut ter-update.
 * Panggil hanya dari main thread.
 */
object Library {
    private lateinit var prefs: SharedPreferences

    val history = mutableStateListOf<HistoryEntry>()

    /** Riwayat tonton permanen (tidak ikut terhapus dari "Lanjutkan Menonton"). */
    val watched = mutableStateListOf<HistoryEntry>()

    /** Suka / tidak suka untuk menyusun urutan (algoritma) di beranda. */
    val likes = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    private val likedGenres = mutableMapOf<String, Int>()
    val myList = mutableStateListOf<Title>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_library", Context.MODE_PRIVATE)
        history.clear()
        history.addAll(readHistory())
        myList.clear()
        myList.addAll(readList())
        watched.clear()
        watched.addAll(readHistory("watched"))
        if (watched.isEmpty() && history.isNotEmpty()) watched.addAll(history)
        likes.clear()
        runCatching {
            val o = JSONObject(prefs.getString("likes", "{}") ?: "{}")
            o.keys().forEach { likes[it] = o.optInt(it) }
            val g = JSONObject(prefs.getString("liked_genres", "{}") ?: "{}")
            g.keys().forEach { likedGenres[it] = g.optInt(it) }
        }
    }

    fun entry(id: String): HistoryEntry? = history.firstOrNull { it.id == id }

    fun saveProgress(entry: HistoryEntry) {
        history.removeAll { it.id == entry.id }
        history.add(0, entry)
        while (history.size > 40) history.removeAt(history.lastIndex)
        persistHistory()
        addWatched(entry)
    }

    /** Catat ke riwayat tonton (langsung saat video mulai diputar). */
    fun addWatched(entry: HistoryEntry) {
        watched.removeAll { it.id == entry.id }
        watched.add(0, entry)
        while (watched.size > 300) watched.removeAt(watched.lastIndex)
        persistHistory("watched", watched)
    }

    fun removeWatched(id: String) {
        if (watched.removeAll { it.id == id }) persistHistory("watched", watched)
    }

    fun clearWatched() {
        watched.clear()
        persistHistory("watched", watched)
    }

    // ---- suka / tidak suka ----

    private fun tasteKeys(t: Title): List<String> = listOfNotNull(
        t.id.takeIf { it.isNotBlank() },
        t.imdbId,
        t.tmdbId?.let { "tmdb$it" },
        t.title.lowercase().trim().takeIf { it.isNotBlank() }?.let { "t:$it" },
    )

    /** 1 = suka, -1 = tidak suka, 0 = belum dinilai. */
    fun taste(t: Title): Int = tasteKeys(t).firstNotNullOfOrNull { likes[it] } ?: 0

    fun rate(t: Title, value: Int, genres: List<String>) {
        val keys = tasteKeys(t)
        val old = taste(t)
        keys.forEach { if (value == 0) likes.remove(it) else likes[it] = value }
        // genre yang disukai/tidak disukai ikut diingat
        genres.forEach { g ->
            val delta = value - old
            likedGenres[g] = (likedGenres[g] ?: 0) + delta
        }
        prefs.edit()
            .putString("likes", JSONObject(likes.toMap() as Map<*, *>).toString())
            .putString("liked_genres", JSONObject(likedGenres as Map<*, *>).toString())
            .apply()
    }

    fun isDisliked(t: Title): Boolean = taste(t) < 0

    /** Skor ketertarikan dari genre yang pernah disukai/tidak disukai. */
    fun affinity(t: Title): Int = t.genres.sumOf { likedGenres[it] ?: 0 } + taste(t) * 5

    /** Urutkan ulang daftar sesuai selera (stabil: urutan "terbaru" tetap untuk yang setara). */
    fun personalize(items: List<Title>): List<Title> =
        items.filterNot(::isDisliked).sortedByDescending(::affinity)

    fun removeHistory(id: String) {
        if (history.removeAll { it.id == id }) persistHistory()
    }

    fun isInList(key: String): Boolean = myList.any { it.key == key }

    fun toggleList(item: Title) {
        if (!myList.removeAll { it.key == item.key }) myList.add(0, item)
        persistList()
    }

    // ---- penyimpanan ----

    private fun persistHistory(key: String = "history", list: List<HistoryEntry> = history) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("title", h.title)
                    .put("poster", h.poster ?: JSONObject.NULL)
                    .put("isSeries", h.isSeries)
                    .put("season", h.season)
                    .put("episode", h.episode)
                    .put("position", h.positionMs)
                    .put("duration", h.durationMs)
                    .put("updatedAt", h.updatedAt)
                    .put("imdbId", h.imdbId ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readHistory(key: String = "history"): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        arr.objects().map { o ->
            HistoryEntry(
                id = o.getString("id"),
                title = o.optString("title"),
                poster = o.str("poster"),
                isSeries = o.optBoolean("isSeries"),
                season = o.optInt("season"),
                episode = o.optInt("episode"),
                positionMs = o.optLong("position"),
                durationMs = o.optLong("duration"),
                updatedAt = o.optLong("updatedAt"),
                imdbId = o.str("imdbId"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistList() {
        val arr = JSONArray()
        myList.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("isSeries", t.isSeries)
                    .put("year", t.year ?: JSONObject.NULL)
                    .put("poster", t.poster ?: JSONObject.NULL)
                    .put("imdbId", t.imdbId ?: JSONObject.NULL)
                    .put("backdrop", t.backdrop ?: JSONObject.NULL)
                    .put("logo", t.logo ?: JSONObject.NULL)
                    .put("rating", t.rating ?: JSONObject.NULL)
                    .put("description", t.description ?: JSONObject.NULL)
                    .put("genres", JSONArray(t.genres)),
            )
        }
        prefs.edit().putString("my_list", arr.toString()).apply()
    }

    private fun readList(): List<Title> = runCatching {
        val arr = JSONArray(prefs.getString("my_list", "[]"))
        arr.objects().map { o ->
            Title(
                id = o.getString("id"),
                title = o.optString("title"),
                isSeries = o.optBoolean("isSeries"),
                year = o.str("year"),
                poster = o.str("poster"),
                imdbId = o.str("imdbId"),
                backdrop = o.str("backdrop"),
                logo = o.str("logo"),
                rating = o.str("rating"),
                description = o.str("description"),
                genres = o.optJSONArray("genres").strings(),
            )
        }
    }.getOrDefault(emptyList())
}
