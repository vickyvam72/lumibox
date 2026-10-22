package com.moviebox.app.data

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Satu unduhan (film atau satu episode) beserta file subtitlenya. */
data class DownloadItem(
    val downloadId: Long,
    val subjectId: String,
    val title: String,
    val poster: String?,
    val isSeries: Boolean,
    val season: Int,
    val episode: Int,
    val filePath: String,
    val subtitles: List<Pair<String, String>>,
    val createdAt: Long,
    val imdbId: String? = null,
) {
    val key: String get() = "$subjectId:$season:$episode"
}

data class DownloadStatus(val state: Int, val progress: Float, val bytes: Long)

/**
 * Unduhan offline lewat DownloadManager Android (tetap jalan walau aplikasi ditutup).
 * Video disimpan di folder aplikasi (Android/data/com.moviebox.app/files/Movies/LumiBox).
 */
object Downloads {
    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    val items = mutableStateListOf<DownloadItem>()

    const val STATE_RUNNING = 0
    const val STATE_DONE = 1
    const val STATE_FAILED = 2

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("lumibox_downloads", Context.MODE_PRIVATE)
        items.clear()
        items.addAll(read())
    }

    fun find(subjectId: String, season: Int, episode: Int): DownloadItem? =
        items.firstOrNull { it.subjectId == subjectId && it.season == season && it.episode == episode }

    private fun folder(): File =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "LumiBox").apply { mkdirs() }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifBlank { "video" }

    /**
     * Mulai unduh. Mengembalikan pesan untuk ditampilkan ke pengguna.
     * [title] boleh judul addon/TMDB (id MovieBox dicari otomatis).
     */
    suspend fun start(title: Title, season: Int, episode: Int): String {
        val subjectId = Resolver.subjectId(title)
        find(subjectId, season, episode)?.let { return "Sudah ada di Unduhan." }
        val details = runCatching { Repo.details(subjectId) }.getOrNull()
        val playId = details?.originalSubjectId?.takeIf { it.isNotBlank() } ?: subjectId
        val streams = runCatching { Repo.streams(playId, season, episode) }
            .recoverCatching { Repo.streams(subjectId, season, episode) }
            .getOrElse { throw IOException(it.message ?: "Video tidak tersedia.") }

        // Hanya file video langsung (MP4) yang bisa diunduh; DASH/HLS hanya untuk streaming.
        val candidates = streams.filter {
            val path = it.url.substringBefore('?').lowercase()
            !path.endsWith(".mpd") && !path.contains("/dash/") && !path.endsWith(".m3u8")
        }
        val stream = candidates.filter { it.height in 1..1080 }.maxByOrNull { it.height }
            ?: candidates.maxByOrNull { it.height }
            ?: throw IOException("Judul ini hanya bisa diputar online (tidak ada file yang bisa diunduh).")

        val baseName = buildString {
            append(safeName(title.title.ifBlank { details?.title ?: "video" }))
            if (season > 0) append(" S%02dE%02d".format(season, episode))
            append("_").append(System.currentTimeMillis() % 100000)
        }

        // Subtitle: Indonesia + bahasa asli + Inggris (maks 3)
        val subs = mutableListOf<Pair<String, String>>()
        val captions = stream.resourceId?.let { rid ->
            runCatching { Repo.captions(playId, rid, season, episode) }.getOrDefault(emptyList())
        }.orEmpty()
        val original = Lang.code(details?.originalLanguage) ?: Lang.code(details?.country)
        val wanted = listOfNotNull("id", original, "en").distinct()
        val chosen = wanted.mapNotNull { code -> captions.firstOrNull { Lang.ofCaption(it) == code } }.distinct().take(3)
        withContext(Dispatchers.IO) {
            chosen.forEachIndexed { i, c ->
                runCatching {
                    val ext = c.url.substringBefore('?').substringAfterLast('.', "srt").take(4).lowercase()
                        .takeIf { it in listOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                    val file = File(folder(), "$baseName.${i}.$ext")
                    val conn = URL(c.url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    subs += c.name to file.absolutePath
                }
            }
        }

        val videoFile = File(folder(), "$baseName.mp4")
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(stream.url))
            .setTitle(title.title.ifBlank { details?.title ?: "Lumi Box" } + if (season > 0) " · S${season}E$episode" else "")
            .setDescription("Lumi Box")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(videoFile))
            .setAllowedOverMetered(true)
        stream.headers.forEach { (k, v) -> request.addRequestHeader(k, v) }
        val id = withContext(Dispatchers.IO) { dm.enqueue(request) }

        items.add(
            0,
            DownloadItem(
                downloadId = id,
                subjectId = subjectId,
                title = title.title.ifBlank { details?.title ?: "Video" },
                poster = title.poster ?: details?.poster,
                isSeries = title.isSeries,
                season = season,
                episode = episode,
                filePath = videoFile.absolutePath,
                subtitles = subs,
                createdAt = System.currentTimeMillis(),
                imdbId = title.imdbId,
            ),
        )
        persist()
        return "Mengunduh… lihat progres di Lumi Saya → Unduhan."
    }

    fun status(item: DownloadItem): DownloadStatus {
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(item.downloadId))?.use { c ->
            if (c.moveToFirst()) {
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val progress = if (total > 0) done.toFloat() / total else 0f
                return when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus(STATE_DONE, 1f, total)
                    DownloadManager.STATUS_FAILED -> DownloadStatus(STATE_FAILED, progress, done)
                    else -> DownloadStatus(STATE_RUNNING, progress, total)
                }
            }
        }
        // Tidak ada di DownloadManager lagi: cek file-nya saja.
        val f = File(item.filePath)
        return if (f.exists() && f.length() > 0) DownloadStatus(STATE_DONE, 1f, f.length()) else DownloadStatus(STATE_FAILED, 0f, 0)
    }

    fun delete(item: DownloadItem) {
        runCatching {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(item.downloadId)
        }
        runCatching { File(item.filePath).delete() }
        item.subtitles.forEach { runCatching { File(it.second).delete() } }
        items.removeAll { it.downloadId == item.downloadId }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { d ->
            arr.put(
                JSONObject()
                    .put("downloadId", d.downloadId)
                    .put("subjectId", d.subjectId)
                    .put("title", d.title)
                    .put("poster", d.poster ?: JSONObject.NULL)
                    .put("isSeries", d.isSeries)
                    .put("season", d.season)
                    .put("episode", d.episode)
                    .put("filePath", d.filePath)
                    .put("createdAt", d.createdAt)
                    .put("imdbId", d.imdbId ?: JSONObject.NULL)
                    .put(
                        "subtitles",
                        JSONArray().apply { d.subtitles.forEach { put(JSONArray().put(it.first).put(it.second)) } },
                    ),
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun read(): List<DownloadItem> = runCatching {
        JSONArray(prefs.getString("items", "[]")).objects().map { o ->
            DownloadItem(
                downloadId = o.optLong("downloadId"),
                subjectId = o.optString("subjectId"),
                title = o.optString("title"),
                poster = o.str("poster"),
                isSeries = o.optBoolean("isSeries"),
                season = o.optInt("season"),
                episode = o.optInt("episode"),
                filePath = o.optString("filePath"),
                subtitles = o.optJSONArray("subtitles").arrays().map { it.optString(0) to it.optString(1) },
                createdAt = o.optLong("createdAt"),
                imdbId = o.str("imdbId"),
            )
        }
    }.getOrDefault(emptyList())
}
