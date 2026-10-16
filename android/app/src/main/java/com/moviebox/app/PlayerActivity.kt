package com.moviebox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.moviebox.app.data.Caption
import com.moviebox.app.data.Stream

/**
 * Pemutar bawaan (ExoPlayer). Dipakai karena stream MovieBox butuh header
 * Referer / User-Agent / Cookie yang tidak bisa dikirim ke player luar dengan andal.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var resumeKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        resumeKey = intent.getStringExtra(EXTRA_RESUME_KEY) ?: url.substringBefore('?')

        val headerKeys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS) ?: emptyArray()
        val headerValues = intent.getStringArrayExtra(EXTRA_HEADER_VALUES) ?: emptyArray()
        val headers = headerKeys.zip(headerValues).toMap()
        val subNames = intent.getStringArrayExtra(EXTRA_SUB_NAMES) ?: emptyArray()
        val subUrls = intent.getStringArrayExtra(EXTRA_SUB_URLS) ?: emptyArray()
        val maxHeight = intent.getIntExtra(EXTRA_MAX_HEIGHT, 0)

        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        if (userAgent != null) httpFactory.setUserAgent(userAgent)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredTextLanguages("id", "in", "en")
                .apply { if (maxHeight > 0) setMaxVideoSize(Int.MAX_VALUE, maxHeight) },
        )

        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        player = exo

        val subtitles = subNames.zip(subUrls).map { (name, subUrl) ->
            val lang = languageCode(name)
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(subtitleMime(subUrl))
                .setLabel(name)
                .setLanguage(lang)
                .setSelectionFlags(if (lang == "id") C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitles)
        val path = url.substringBefore('?').lowercase()
        when {
            path.endsWith(".mpd") || path.contains("/dash/") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".m3u8") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exo.setMediaItem(itemBuilder.build())
        val resumeAt = prefs().getLong(resumeKey, 0L)
        if (resumeAt > 10_000L) exo.seekTo(resumeAt)
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Gagal memutar: ${error.errorCodeName}. Coba kualitas lain.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefs().edit().remove(resumeKey).apply()
                }
            }
        })
        exo.prepare()

        val view = PlayerView(this).apply {
            this.player = exo
            keepScreenOn = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        setContentView(view)

        intent.getStringExtra(EXTRA_TITLE)?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            savePosition(p)
            p.pause()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun savePosition(p: Player) {
        if (!::resumeKey.isInitialized) return
        val duration = p.duration
        val position = p.currentPosition
        val nearEnd = duration > 0 && duration - position < 60_000L
        val editor = prefs().edit()
        if (nearEnd || position < 10_000L) {
            editor.remove(resumeKey)
        } else {
            editor.putLong(resumeKey, position)
        }
        editor.apply()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun prefs() = getSharedPreferences("resume_positions", Context.MODE_PRIVATE)

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_RESUME_KEY = "resume_key"
        private const val EXTRA_HEADER_KEYS = "header_keys"
        private const val EXTRA_HEADER_VALUES = "header_values"
        private const val EXTRA_SUB_NAMES = "sub_names"
        private const val EXTRA_SUB_URLS = "sub_urls"
        private const val EXTRA_MAX_HEIGHT = "max_height"

        fun start(
            context: Context,
            title: String,
            resumeKey: String,
            stream: Stream,
            captions: List<Caption>,
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, stream.url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_RESUME_KEY, resumeKey)
                putExtra(EXTRA_HEADER_KEYS, stream.headers.map { it.first }.toTypedArray())
                putExtra(EXTRA_HEADER_VALUES, stream.headers.map { it.second }.toTypedArray())
                putExtra(EXTRA_SUB_NAMES, captions.map { it.name }.toTypedArray())
                putExtra(EXTRA_SUB_URLS, captions.map { it.url }.toTypedArray())
                putExtra(EXTRA_MAX_HEIGHT, stream.height)
            }
            context.startActivity(intent)
        }

        private fun subtitleMime(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }

        private fun languageCode(name: String): String? {
            val n = name.lowercase()
            return when {
                "indo" in n || "bahasa" in n -> "id"
                "english" in n || n == "en" -> "en"
                "malay" in n || "melayu" in n -> "ms"
                "arab" in n -> "ar"
                "hindi" in n -> "hi"
                "chinese" in n || "中文" in n -> "zh"
                "japan" in n -> "ja"
                "korea" in n -> "ko"
                "spanish" in n || "español" in n -> "es"
                "french" in n -> "fr"
                "portugu" in n -> "pt"
                "thai" in n -> "th"
                "vietnam" in n -> "vi"
                else -> null
            }
        }
    }
}
