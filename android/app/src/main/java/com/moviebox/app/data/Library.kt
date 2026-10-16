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
    val myList = mutableStateListOf<Title>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_library", Context.MODE_PRIVATE)
        history.clear()
        history.addAll(readHistory())
        myList.clear()
        myList.addAll(readList())
    }

    fun entry(id: String): HistoryEntry? = history.firstOrNull { it.id == id }

    fun saveProgress(entry: HistoryEntry) {
        history.removeAll { it.id == entry.id }
        history.add(0, entry)
        while (history.size > 40) history.removeAt(history.lastIndex)
        persistHistory()
    }

    fun removeHistory(id: String) {
        if (history.removeAll { it.id == id }) persistHistory()
    }

    fun isInList(key: String): Boolean = myList.any { it.key == key }

    fun toggleList(item: Title) {
        if (!myList.removeAll { it.key == item.key }) myList.add(0, item)
        persistList()
    }

    // ---- penyimpanan ----

    private fun persistHistory() {
        val arr = JSONArray()
        history.forEach { h ->
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
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun readHistory(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
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
