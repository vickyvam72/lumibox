package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Addon EasyRatings milik pengguna (katalog + poster/backdrop/logo/rating). */
const val ADDON_MANIFEST =
    "https://easyratingsdb.com/proxy/Tk-52322f03f7b72fb5cf7b5bc565d000569a722cc5dc03a96c/eyJ1cmwiOiJodHRwczovL2FwcHMuc29sdXNlcnYuZXMvc3RyZW1pb19jYXRhbG9nX3BsdXMvYy4yNmQwMGI2NzI2MDdmYzZhZGUxMTdlZDhkODBmZTliNWQyNzA2OTAxL21hbmlmZXN0Lmpzb24iLCJlcmRiQmFzZSI6Imh0dHBzOi8vZWFzeXJhdGluZ3NkYi5jb20iLCJwb3N0ZXJFbmFibGVkIjp0cnVlLCJiYWNrZHJvcEVuYWJsZWQiOnRydWUsImxvZ29FbmFibGVkIjp0cnVlLCJ0aHVtYm5haWxFbmFibGVkIjp0cnVlLCJ0cmFuc2xhdGVNZXRhIjp0cnVlLCJoaWRkZW5DYXRhbG9ncyI6WyJtb3ZpZTpzZWFyY2hfbW92aWVfY3YyNF84YjlmNzk3OGZiOnNlYXJjaDpza2lwIiwic2VyaWVzOnNlYXJjaF9zZXJpZXNfY3YyNF84YjlmNzk3OGZiOnNlYXJjaDpza2lwIiwic2VyaWVzOmxhc3QtdmlkZW9zOmxhc3R2aWRlb3NpZHMiLCJzZXJpZXM6Y2FsZW5kYXItdmlkZW9zOmNhbGVuZGFydmlkZW9zaWRzIl0sInNlcmllc01ldGFkYXRhUHJvdmlkZXIiOiJpbWRiIn0/manifest.json"

private const val CINEMETA = "https://v3-cinemeta.strem.io"

object Addon {
    private lateinit var prefs: SharedPreferences
    @Volatile
    private var base = ADDON_MANIFEST.removeSuffix("/manifest.json")

    /**
     * URL addon dengan poster/backdrop TANPA badge rating EasyRatings
     * (posterEnabled/backdropEnabled = false), terjemahan tetap aktif.
     */
    private val cleanManifest: String by lazy {
        runCatching {
            val marker = "/proxy/"
            val start = ADDON_MANIFEST.indexOf(marker) + marker.length
            val afterToken = ADDON_MANIFEST.indexOf('/', start) + 1
            val end = ADDON_MANIFEST.indexOf("/manifest.json")
            val encoded = ADDON_MANIFEST.substring(afterToken, end)
            val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            val config = JSONObject(String(android.util.Base64.decode(encoded, flags)))
            config.put("posterEnabled", false)
            config.put("backdropEnabled", false)
            config.put("logoEnabled", false)
            config.put("thumbnailEnabled", false)
            val reEncoded = android.util.Base64.encodeToString(config.toString().toByteArray(), flags)
            ADDON_MANIFEST.substring(0, afterToken) + reEncoded + "/manifest.json"
        }.getOrDefault(ADDON_MANIFEST)
    }

    @Volatile
    private var catalogsCache: List<AddonCatalog>? = null
    private val catalogItems = ConcurrentHashMap<String, List<Title>>()
    private val metas = ConcurrentHashMap<String, AddonMeta>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_addon", Context.MODE_PRIVATE)
    }

    /** Katalog yang bisa dijadikan baris beranda (tanpa yang wajib "search" dsb). */
    suspend fun catalogs(): List<AddonCatalog> {
        catalogsCache?.let { return it }
        val manifest = try {
            withContext(Dispatchers.IO) {
                // Coba versi tanpa badge rating dulu; kalau ditolak, pakai URL asli.
                val clean = runCatching { getJson(cleanManifest) }.getOrNull()
                if (clean != null) {
                    base = cleanManifest.removeSuffix("/manifest.json")
                    clean
                } else {
                    base = ADDON_MANIFEST.removeSuffix("/manifest.json")
                    getJson(ADDON_MANIFEST)
                }
            }.also {
                prefs.edit().putString("manifest", it.toString()).putString("base", base).apply()
            }
        } catch (e: Exception) {
            prefs.getString("base", null)?.let { base = it }
            // Pakai salinan terakhir kalau sedang offline / server lambat.
            prefs.getString("manifest", null)?.let { JSONObject(it) } ?: throw e
        }
        val list = parseCatalogs(manifest)
        catalogsCache = list
        return list
    }

    /** Katalog dari salinan tersimpan (instan, tanpa jaringan). */
    fun storedCatalogs(): List<AddonCatalog> = runCatching {
        prefs.getString("base", null)?.let { base = it }
        prefs.getString("manifest", null)?.let { parseCatalogs(JSONObject(it)) }
    }.getOrNull().orEmpty()

    /** Isi katalog terakhir yang tersimpan (instan, tanpa jaringan). */
    fun storedCatalog(c: AddonCatalog): List<Title> = runCatching {
        prefs.getString("cat_${c.key}@0", null)?.let { JSONArray(it) }
            ?.objects()?.mapNotNull { parseMeta(it, c.type) }?.distinctBy { it.key }
    }.getOrNull().orEmpty()

    private fun parseCatalogs(manifest: JSONObject): List<AddonCatalog> {
        val hidden = listOf("search", "last-videos", "calendar-videos", "lastvideos", "calendar")
        return manifest.optJSONArray("catalogs").objects().mapNotNull { c ->
            val type = c.str("type") ?: return@mapNotNull null
            val id = c.str("id") ?: return@mapNotNull null
            if (type != "movie" && type != "series") return@mapNotNull null
            val required = c.optJSONArray("extra").objects().any { it.optBoolean("isRequired", false) } ||
                c.optJSONArray("extraRequired").strings().isNotEmpty()
            if (required || hidden.any { id.lowercase().contains(it) }) return@mapNotNull null
            AddonCatalog(type, id, c.str("name") ?: id)
        }.distinctBy { it.key }
    }

    fun catalogByKey(key: String): AddonCatalog? = catalogsCache?.firstOrNull { it.key == key }

    suspend fun catalog(c: AddonCatalog, skip: Int = 0): List<Title> {
        val cacheKey = "${c.key}@$skip"
        catalogItems[cacheKey]?.let { return it }
        val url = if (skip > 0) {
            "$base/catalog/${c.type}/${Uri.encode(c.id)}/skip=$skip.json"
        } else {
            "$base/catalog/${c.type}/${Uri.encode(c.id)}.json"
        }
        val stored = "cat_${cacheKey}"
        val metas = try {
            withContext(Dispatchers.IO) { getJson(url).optJSONArray("metas") ?: JSONArray() }.also {
                if (it.length() > 0) prefs.edit().putString(stored, it.toString()).apply()
            }
        } catch (e: Exception) {
            // Server lambat/gagal: pakai daftar terakhir yang tersimpan (bukan daftar acak).
            prefs.getString(stored, null)?.let { JSONArray(it) } ?: throw e
        }
        val items = metas.objects().mapNotNull { parseMeta(it, c.type) }.distinctBy { it.key }
        if (items.isNotEmpty()) catalogItems[cacheKey] = items
        return items
    }

    /** Meta lengkap (deskripsi, backdrop, judul episode). Addon dulu, lalu Cinemeta. */
    suspend fun meta(type: String, imdbId: String): AddonMeta? {
        val key = "$type/$imdbId"
        metas[key]?.let { return it }
        val meta = withContext(Dispatchers.IO) {
            val fromAddon = runCatching { getJson("$base/meta/$type/$imdbId.json").optJSONObject("meta") }.getOrNull()
            val obj = fromAddon ?: runCatching { getJson("$CINEMETA/meta/$type/$imdbId.json").optJSONObject("meta") }.getOrNull()
            obj?.let(::parseFullMeta)
        } ?: return null
        metas[key] = meta
        return meta
    }

    /** Judul asli (bahasa Inggris) dari Cinemeta, untuk pencocokan kalau judul addon diterjemahkan. */
    suspend fun originalName(type: String, imdbId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            getJson("$CINEMETA/meta/$type/$imdbId.json").optJSONObject("meta")?.str("name")
        }.getOrNull()
    }

    // ---------------------------------------------------------------- parsing

    private fun parseMeta(o: JSONObject, fallbackType: String): Title? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: o.str("title") ?: return null
        val type = o.str("type") ?: fallbackType
        val genres = Genres.normalize(o.optJSONArray("genres").strings().ifEmpty { o.optJSONArray("genre").strings() })
        return Title(
            id = "",
            title = name,
            isSeries = type == "series",
            year = year(o.str("releaseInfo") ?: o.str("year") ?: o.str("released")),
            poster = o.str("poster"),
            imdbId = id,
            backdrop = o.str("background"),
            logo = o.str("logo"),
            rating = o.str("imdbRating"),
            description = o.str("description"),
            genres = genres,
        )
    }

    private fun parseFullMeta(o: JSONObject): AddonMeta {
        val episodes = o.optJSONArray("videos").objects().mapNotNull { v ->
            val season = v.optInt("season", -1)
            val episode = if (v.has("episode")) v.optInt("episode", -1) else v.optInt("number", -1)
            if (season < 0 || episode < 0) return@mapNotNull null
            EpisodeMeta(season, episode, v.str("name") ?: v.str("title"), v.str("thumbnail"))
        }
        return AddonMeta(
            name = o.str("name"),
            description = o.str("description"),
            background = o.str("background"),
            logo = o.str("logo"),
            episodes = episodes,
            runtimeMin = o.str("runtime")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
            genres = Genres.normalize(o.optJSONArray("genres").strings().ifEmpty { o.optJSONArray("genre").strings() }),
            rating = o.str("imdbRating"),
        )
    }

    private fun year(raw: String?): String? =
        raw?.let { Regex("(19|20)\\d{2}").find(it)?.value }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "LumiBox/1.0 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Addon HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

@Suppress("unused")
private fun JSONArray.isEmptyArray() = length() == 0
