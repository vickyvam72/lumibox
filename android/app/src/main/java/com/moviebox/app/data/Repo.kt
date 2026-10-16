package com.moviebox.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Tab beranda MovieBox yang dipakai versi TUI untuk "Browse". */
const val HOME_TAB_ID = "2"

/** Error dari core Rust, opsional membawa info teknis untuk diagnosa. */
class BridgeException(message: String, val debug: String?) : IOException(message)

object Repo {

    /** Scope latar untuk prefetch (tidak terikat layar). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ambil stream lebih awal supaya saat tombol Putar ditekan hasilnya sudah ada di cache. */
    fun prefetch(id: String, season: Int, episode: Int) {
        if (id.isBlank()) return
        background.launch { runCatching { Native.streams(id, season, episode) } }
    }

    fun warmup() {
        background.launch { runCatching { Native.warmup() } }
    }

    private fun unwrap(raw: String): Any? {
        val obj = JSONObject(raw)
        if (!obj.optBoolean("ok", false)) {
            throw BridgeException(obj.str("error") ?: "Terjadi kesalahan", obj.str("debug"))
        }
        return if (obj.isNull("data")) null else obj.get("data")
    }

    private fun unwrapArray(raw: String): JSONArray = unwrap(raw) as? JSONArray ?: JSONArray()

    suspend fun home(page: Int): List<Section> = withContext(Dispatchers.IO) {
        unwrapArray(Native.homepage(HOME_TAB_ID, page)).objects().map { s ->
            Section(
                title = s.str("title") ?: "Rekomendasi",
                banner = s.optBoolean("banner", false),
                items = s.optJSONArray("items").objects().map(::parseTitle)
                    .filter { it.id.isNotBlank() }
                    .distinctBy { it.id },
            )
        }
    }

    suspend fun search(query: String, page: Int = 1): List<Title> = withContext(Dispatchers.IO) {
        unwrapArray(Native.search(query, page)).objects().map(::parseTitle)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    }

    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, Details>()

    /** Detail yang sudah pernah dimuat (dipakai pemutar supaya tidak menunggu lagi). */
    fun cachedDetails(id: String): Details? = detailsCache[id]

    suspend fun details(id: String): Details {
        detailsCache[id]?.let { return it }
        val d = fetchDetails(id)
        detailsCache[id] = d
        return d
    }

    private suspend fun fetchDetails(id: String): Details = withContext(Dispatchers.IO) {
        val d = unwrap(Native.details(id)) as? JSONObject ?: throw IOException("Detail kosong")
        Details(
            id = d.optJSONObject("id")?.str("value") ?: id,
            title = d.str("title") ?: "Tanpa judul",
            isSeries = d.str("media_type") == "series" ||
                (d.optJSONArray("seasons")?.length() ?: 0) > 0,
            year = d.str("year"),
            description = d.str("description"),
            rating = d.str("imdb_rating"),
            duration = d.str("duration"),
            stars = d.str("stars"),
            genres = d.optJSONArray("genres").strings(),
            poster = d.str("poster_url"),
            seasons = d.optJSONArray("seasons").objects().map { s ->
                SeasonInfo(
                    number = s.optInt("number", 1),
                    episodes = s.optJSONArray("episodes").objects().map { it.optInt("number", 1) },
                )
            }.filter { it.episodes.isNotEmpty() },
            dubs = d.optJSONArray("dubs").objects().mapNotNull { dub ->
                val sid = dub.str("subject_id") ?: return@mapNotNull null
                Dub(sid, dub.str("label") ?: dub.str("language") ?: "Audio", dub.str("language"))
            }.distinctBy { it.subjectId },
            originalSubjectId = d.str("original_subject_id"),
            originalLanguage = d.str("original_language"),
            country = d.str("country"),
        )
    }

    suspend fun streams(id: String, season: Int, episode: Int): List<Stream> =
        withContext(Dispatchers.IO) {
            unwrapArray(Native.streams(id, season, episode)).objects().mapNotNull { s ->
                val url = s.str("url") ?: return@mapNotNull null
                val headers = s.optJSONArray("headers").arrays().mapNotNull { pair ->
                    if (pair.length() >= 2) pair.optString(0) to pair.optString(1) else null
                }
                Stream(
                    label = s.str("label") ?: s.str("quality") ?: "Stream",
                    quality = s.str("quality"),
                    height = s.optInt("height", 0),
                    sizeBytes = if (s.isNull("sizeBytes")) null else s.optLong("sizeBytes"),
                    url = url,
                    headers = headers,
                    resourceId = s.str("resourceId"),
                )
            }
        }

    suspend fun captions(id: String, resourceId: String?, season: Int, episode: Int): List<Caption> =
        withContext(Dispatchers.IO) {
            if (resourceId.isNullOrBlank()) return@withContext emptyList()
            unwrapArray(Native.captions(id, resourceId, season, episode)).objects().mapNotNull { c ->
                val url = c.str("url") ?: return@mapNotNull null
                Caption(c.str("name") ?: "Subtitle", url, c.str("code"))
            }
        }

    private fun parseTitle(o: JSONObject) = Title(
        id = o.optJSONObject("id")?.str("value") ?: o.str("id") ?: "",
        title = o.str("title") ?: "Tanpa judul",
        isSeries = o.str("media_type") == "series",
        year = o.str("year"),
        poster = o.str("poster_url"),
    )
}

// ---- helper kecil untuk org.json ----

/** String atau null (org.json mengembalikan "null" untuk JSON null, jadi dicek dulu). */
internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONArray?.arrays(): List<JSONArray> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONArray(it) }
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        if (isNull(i)) null else optString(i).takeIf { it.isNotBlank() }
    }
}
