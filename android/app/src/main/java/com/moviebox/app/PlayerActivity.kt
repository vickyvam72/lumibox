package com.moviebox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
import com.moviebox.app.data.BridgeException
import com.moviebox.app.data.Caption
import com.moviebox.app.data.Details
import com.moviebox.app.data.HistoryEntry
import com.moviebox.app.data.Lang
import com.moviebox.app.data.Library
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Stream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pemutar Lumi Box.
 * - Mencari stream sendiri, otomatis memilih kualitas terbaik dan pindah ke sumber lain kalau gagal.
 * - Audio: selalu versi asli (bukan dubbing).
 * - Subtitle: Bahasa Indonesia; kalau tidak ada, subtitle bahasa asli film.
 * - Serial: lanjut otomatis ke episode berikutnya.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var playerView: PlayerView
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var copyButton: Button
    private lateinit var titleText: TextView
    private lateinit var nextButton: Button
    private lateinit var topBar: LinearLayout

    private lateinit var subjectId: String
    private var displayTitle = ""
    private var poster: String? = null
    private var isSeries = false

    private var details: Details? = null
    private var season = 0
    private var episode = 0
    private var streams: List<Stream> = emptyList()
    private var captions: List<Caption> = emptyList()
    private var streamIndex = 0
    private var readyOnce = false
    private var loadJob: Job? = null
    private var lastDebug: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        subjectId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (subjectId.isBlank()) {
            finish()
            return
        }
        displayTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        poster = intent.getStringExtra(EXTRA_POSTER)
        isSeries = intent.getBooleanExtra(EXTRA_SERIES, false)
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)

        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        player.addListener(listener)

        buildLayout()
        loadEpisode(season, episode, resume = true)
    }

    // ------------------------------------------------------------------ alur utama

    private fun loadEpisode(newSeason: Int, newEpisode: Int, resume: Boolean) {
        loadJob?.cancel()
        readyOnce = false
        showLoading("Menyiapkan video…")
        loadJob = lifecycleScope.launch {
            val d = details ?: runCatching { Repo.details(subjectId) }.getOrNull()
            details = d
            if (d != null) {
                displayTitle = d.title
                poster = d.poster ?: poster
                isSeries = d.isSeries
            }

            // Serial tanpa episode tertentu → episode pertama.
            if (isSeries && (newSeason <= 0 || newEpisode <= 0)) {
                val (s, e) = d?.firstEpisode() ?: (1 to 1)
                season = s
                episode = e
            } else {
                season = newSeason
                episode = newEpisode
            }
            updateTitle()

            // Selalu pakai subject versi audio asli.
            val playId = d?.originalSubjectId?.takeIf { it.isNotBlank() } ?: subjectId
            val found = fetchStreams(playId) ?: if (playId != subjectId) fetchStreams(subjectId) else null
            if (found.isNullOrEmpty()) {
                showError(lastErrorMessage ?: "Video tidak tersedia untuk judul ini.")
                return@launch
            }
            streams = rank(found)
            streamIndex = 0

            showLoading("Memuat subtitle…")
            captions = loadCaptions(playId)
            applyLanguagePreferences(d)

            val startAt = if (resume) resumePosition() else 0L
            playCurrent(startAt)
        }
    }

    private var lastErrorMessage: String? = null

    private suspend fun fetchStreams(id: String): List<Stream>? = try {
        Repo.streams(id, season, episode)
    } catch (e: Exception) {
        lastErrorMessage = e.message
        lastDebug = (e as? BridgeException)?.debug
        null
    }

    private suspend fun loadCaptions(playId: String): List<Caption> {
        val ids = streams.mapNotNull { it.resourceId }.distinct().take(3)
        for (rid in ids) {
            val list = runCatching { Repo.captions(playId, rid, season, episode) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** Urutan otomatis: resolusi terbaik ≤1080p dulu, lalu Auto/DASH, lalu >1080p. */
    private fun rank(list: List<Stream>): List<Stream> = list
        .distinctBy { it.url }
        .sortedWith(
            compareBy<Stream> {
                when {
                    it.height in 1..1080 -> 0
                    it.height == 0 -> 1
                    else -> 2
                }
            }.thenByDescending { if (it.height <= 1080) it.height else -it.height },
        )

    private fun originalLanguageCode(d: Details?): String? =
        Lang.code(d?.originalLanguage) ?: Lang.code(d?.country)

    /** Subtitle: Indonesia → bahasa asli. Audio: bahasa asli. */
    private fun chosenCaption(d: Details?): Caption? {
        val original = originalLanguageCode(d)
        return captions.firstOrNull { Lang.ofCaption(it) == "id" }
            ?: original?.let { code -> captions.firstOrNull { Lang.ofCaption(it) == code } }
            ?: if (original == null) captions.firstOrNull { Lang.ofCaption(it) == "en" } else null
    }

    private fun applyLanguagePreferences(d: Details?) {
        val original = originalLanguageCode(d)
        val chosen = chosenCaption(d)
        val textLangs = listOfNotNull(chosen?.let { Lang.ofCaption(it) })
        val builder = trackSelector.buildUponParameters()
            .setPreferredTextLanguages(*textLangs.toTypedArray())
            .setSelectUndeterminedTextLanguage(false)
        if (original != null) builder.setPreferredAudioLanguages(original)
        trackSelector.setParameters(builder)
    }

    private fun playCurrent(startAtMs: Long) {
        val stream = streams.getOrNull(streamIndex) ?: run {
            showError("Semua sumber video gagal diputar.")
            return
        }
        showLoading(if (streamIndex == 0) "Memutar…" else "Mencoba sumber lain (${streamIndex + 1}/${streams.size})…")

        val headers = stream.headers.toMap()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        if (userAgent != null) http.setUserAgent(userAgent)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        val chosen = chosenCaption(details)
        val subtitleConfigs = captions.map { c ->
            val code = Lang.ofCaption(c)
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                .setMimeType(subtitleMime(c.url))
                .setLabel(c.name)
                .setLanguage(code)
                .setSelectionFlags(if (c == chosen) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setSubtitleConfigurations(subtitleConfigs)
        val path = stream.url.substringBefore('?').lowercase()
        when {
            path.endsWith(".mpd") || path.contains("/dash/") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        player.setMediaSource(factory.createMediaSource(item.build()))
        if (startAtMs > 0) player.seekTo(startAtMs)
        player.prepare()
        player.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> if (!readyOnce) {
                    readyOnce = true
                    hideOverlays()
                    handler.removeCallbacks(saver)
                    handler.postDelayed(saver, 10_000L)
                }

                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val position = player.currentPosition.coerceAtLeast(0L)
            if (streamIndex + 1 < streams.size) {
                streamIndex += 1
                readyOnce = false
                playCurrent(position)
            } else {
                lastDebug = "Error pemutar: ${error.errorCodeName}\n" +
                    streams.joinToString("\n") { "- ${it.label}" }
                showError("Video tidak bisa diputar (${error.errorCodeName}).")
            }
        }
    }

    private fun onEnded() {
        val next = if (isSeries) details?.nextEpisode(season, episode) else null
        if (next != null) {
            saveEntry(next.first, next.second, 0L, 0L)
            loadEpisode(next.first, next.second, resume = false)
        } else {
            Library.removeHistory(subjectId)
            finish()
        }
    }

    // ------------------------------------------------------------------ riwayat

    private fun resumePosition(): Long {
        val h = Library.entry(subjectId) ?: return 0L
        if (h.season != season || h.episode != episode) return 0L
        val nearEnd = h.durationMs > 0 && h.durationMs - h.positionMs < 60_000L
        return if (h.positionMs > 10_000L && !nearEnd) h.positionMs else 0L
    }

    private fun saveProgress() {
        if (!::player.isInitialized || !readyOnce) return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        saveEntry(season, episode, player.currentPosition.coerceAtLeast(0L), duration)
    }

    private fun saveEntry(s: Int, e: Int, position: Long, duration: Long) {
        Library.saveProgress(
            HistoryEntry(
                id = subjectId,
                title = displayTitle,
                poster = poster,
                isSeries = isSeries,
                season = s,
                episode = e,
                positionMs = position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
        handler.removeCallbacks(saver)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && readyOnce) handler.postDelayed(saver, 10_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        loadJob?.cancel()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tampilan

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (::topBar.isInitialized &&
                        loadingBox.visibility != View.VISIBLE &&
                        errorBox.visibility != View.VISIBLE
                    ) {
                        topBar.visibility = visibility
                    }
                },
            )
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Bar atas: kembali + judul + "Episode berikutnya"
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(110, 0, 0, 0))
        }
        val back = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { finish() }
        }
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        nextButton = Button(this).apply {
            text = "Episode berikutnya ▶"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                val next = details?.nextEpisode(season, episode) ?: return@setOnClickListener
                saveProgress()
                saveEntry(next.first, next.second, 0L, 0L)
                loadEpisode(next.first, next.second, resume = false)
            }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(nextButton, LinearLayout.LayoutParams(WRAP, dp(40)))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // Loading di tengah
        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 176, 32))
        }
        loadingText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
            gravity = Gravity.CENTER
        }
        loadingBox.addView(spinner, LinearLayout.LayoutParams(dp(48), dp(48)))
        loadingBox.addView(loadingText, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(loadingBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // Error di tengah
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val retry = Button(this).apply {
            text = "Coba lagi"
            isAllCaps = false
            setOnClickListener { loadEpisode(season, episode, resume = true) }
        }
        copyButton = Button(this).apply {
            text = "Salin info teknis"
            isAllCaps = false
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lumi Box", lastDebug.orEmpty()))
                Toast.makeText(this@PlayerActivity, "Info teknis disalin", Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(this).apply {
            text = "Tutup"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        buttons.addView(retry)
        buttons.addView(copyButton)
        buttons.addView(close)
        errorBox.addView(errorText, LinearLayout.LayoutParams(WRAP, WRAP))
        errorBox.addView(buttons, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(errorBox, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))

        setContentView(root)
        updateTitle()
    }

    private fun updateTitle() {
        if (!::titleText.isInitialized) return
        titleText.text = if (isSeries && season > 0) "$displayTitle  •  S$season:E$episode" else displayTitle
        val hasNext = isSeries && details?.nextEpisode(season, episode) != null
        nextButton.visibility = if (hasNext) View.VISIBLE else View.GONE
    }

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingBox.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        topBar.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        handler.removeCallbacks(saver)
        errorText.text = message
        copyButton.visibility = if (lastDebug.isNullOrBlank()) View.GONE else View.VISIBLE
        loadingBox.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
    }

    private fun hideOverlays() {
        loadingBox.visibility = View.GONE
        errorBox.visibility = View.GONE
        topBar.visibility = if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_SERIES = "series"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"

        /** season/episode 0 = film; -1 = serial, pilih episode pertama otomatis. */
        fun start(
            context: Context,
            id: String,
            title: String,
            poster: String?,
            isSeries: Boolean,
            season: Int,
            episode: Int,
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_POSTER, poster)
                    .putExtra(EXTRA_SERIES, isSeries)
                    .putExtra(EXTRA_SEASON, season)
                    .putExtra(EXTRA_EPISODE, episode),
            )
        }

        private fun subtitleMime(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
