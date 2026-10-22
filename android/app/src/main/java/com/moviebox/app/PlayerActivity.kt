package com.moviebox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moviebox.app.data.Addon
import com.moviebox.app.data.AddonMeta
import com.moviebox.app.data.BridgeException
import com.moviebox.app.data.Caption
import com.moviebox.app.data.Details
import com.moviebox.app.data.HistoryEntry
import com.moviebox.app.data.Lang
import com.moviebox.app.data.Library
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Resolver
import com.moviebox.app.data.Stream
import com.moviebox.app.data.Title
import com.moviebox.app.ui.Accent
import com.moviebox.app.ui.LumiTheme
import com.moviebox.app.ui.PlayerIcons
import com.moviebox.app.ui.SeasonChips
import com.moviebox.app.ui.TextDim
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Panel { NONE, SPEED, EPISODES, AUDIO_SUBS }

/**
 * Pemutar Lumi Box dengan kontrol ala aplikasi streaming:
 * kunci layar, kecerahan, mundur/maju 10 detik, kecepatan, daftar episode,
 * pilihan audio & subtitle, dan episode berikutnya.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var title: Title

    private var subjectId = ""
    private var playId = ""
    private var details by mutableStateOf<Details?>(null)
    private var meta by mutableStateOf<AddonMeta?>(null)
    private var season by mutableIntStateOf(0)
    private var episode by mutableIntStateOf(0)
    private var streams: List<Stream> = emptyList()
    private var streamIndex = 0
    private var captions by mutableStateOf<List<Caption>>(emptyList())
    private var captionLabels: Map<Caption, String> = emptyMap()
    private var selectedCaption by mutableStateOf<Caption?>(null)
    private var readyOnce = false
    private var loadJob: Job? = null
    private var lastErrorMessage: String? = null
    private var lastDebug: String? = null

    // ---- state tampilan ----
    private var loadingMessage by mutableStateOf<String?>("Menyiapkan video…")
    private var errorMessage by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var tracksVersion by mutableIntStateOf(0)
    private var speed by mutableFloatStateOf(1f)
    private var cues by mutableStateOf<List<com.moviebox.app.data.Cue>>(emptyList())
    private var subtitleOffsetMs by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        title = Title(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            isSeries = intent.getBooleanExtra(EXTRA_SERIES, false),
            year = intent.getStringExtra(EXTRA_YEAR),
            poster = intent.getStringExtra(EXTRA_POSTER),
            imdbId = intent.getStringExtra(EXTRA_IMDB),
            tmdbId = intent.getStringExtra(EXTRA_TMDB),
        )
        if (title.id.isBlank() && title.imdbId.isNullOrBlank() && title.tmdbId.isNullOrBlank() && title.title.isBlank()) {
            finish()
            return
        }
        subjectId = title.id
        details = if (subjectId.isNotBlank()) Repo.cachedDetails(subjectId) else null

        // Buffer awal kecil → video mulai lebih cepat.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 50_000, 700, 1_500)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player.addListener(listener)
        com.moviebox.app.data.Together.player = togetherHook

        setContent {
            LumiTheme {
                PlayerScreen()
                com.moviebox.app.ui.TogetherDialog()
            }
        }

        loadEpisode(
            intent.getIntExtra(EXTRA_SEASON, 0),
            intent.getIntExtra(EXTRA_EPISODE, 0),
            resume = true,
        )
    }

    // ================================================================== alur utama

    private fun loadEpisode(newSeason: Int, newEpisode: Int, resume: Boolean, forcePlayId: String? = null, startAtMs: Long? = null) {
        loadJob?.cancel()
        readyOnce = false
        errorMessage = null
        mismatchBlocked = false
        loadingMessage = "Menyiapkan video…"
        loadJob = lifecycleScope.launch {
            // 0) File unduhan: putar offline.
            val local = intent.getStringExtra(EXTRA_LOCAL)
            if (local != null) {
                season = newSeason
                episode = newEpisode
                streams = listOf(
                    Stream(label = "Offline", quality = null, height = 0, sizeBytes = null, url = android.net.Uri.fromFile(java.io.File(local)).toString(), headers = emptyList(), resourceId = null),
                )
                streamIndex = 0
                val names = intent.getStringArrayExtra(EXTRA_LOCAL_SUB_NAMES) ?: emptyArray()
                val paths = intent.getStringArrayExtra(EXTRA_LOCAL_SUB_PATHS) ?: emptyArray()
                applyCaptions(names.zip(paths).map { (n, p) -> Caption(n, android.net.Uri.fromFile(java.io.File(p)).toString()) })
                loadOffset()
                mismatchChecked = true
                playCurrent(if (resume) resumePosition() else 0L)
                selectCaption(selectedCaption?.takeIf { it in captions } ?: autoCaption() ?: captions.firstOrNull())
                return@launch
            }

            // 1) Cocokkan judul addon → MovieBox (sekali, lalu tersimpan).
            if (subjectId.isBlank()) {
                loadingMessage = "Mencari judul…"
                subjectId = try {
                    Resolver.subjectId(title)
                } catch (e: Exception) {
                    showError(e.message ?: "Judul tidak ditemukan")
                    return@launch
                }
                details = Repo.cachedDetails(subjectId)
                loadingMessage = "Menyiapkan video…"
            }

            loadOffset()

            // Meta addon (judul & gambar episode) — tidak menahan pemutaran.
            val imdb = title.imdbId
            if (meta == null && !imdb.isNullOrBlank()) {
                launch {
                    meta = runCatching { Addon.meta(if (title.isSeries) "series" else "movie", imdb) }.getOrNull()
                    recheckDuration()
                }
            }
            if (com.moviebox.app.data.Tmdb.enabled && !title.isSeries) {
                launch {
                    runCatching {
                        com.moviebox.app.data.Tmdb.info(title.copy(title = title.title.ifBlank { details?.title ?: "" }))
                    }
                    recheckDuration()
                }
            }

            val detailsJob = async { details ?: runCatching { Repo.details(subjectId) }.getOrNull() }

            // Serial tanpa episode tertentu → butuh detail dulu untuk tahu episode pertama.
            if (title.isSeries && (newSeason <= 0 || newEpisode <= 0)) {
                val d = detailsJob.await()
                details = d
                val (s, e) = d?.firstEpisode() ?: (1 to 1)
                season = s
                episode = e
            } else {
                season = newSeason
                episode = newEpisode
            }

            // 2) Ambil stream paralel dengan detail. Tebakan pertama: versi asli kalau sudah diketahui.
            val guess = forcePlayId
                ?: playId.takeIf { it.isNotBlank() }
                ?: details?.originalSubjectId?.takeIf { it.isNotBlank() }
                ?: subjectId
            val streamsJob = async { fetchStreams(guess) }
            val d = detailsJob.await()
            if (d != null) details = d
            val original = forcePlayId ?: d?.originalSubjectId?.takeIf { it.isNotBlank() } ?: subjectId

            var found = streamsJob.await()
            var usedId = guess
            if (original != guess) {
                val better = fetchStreams(original)
                if (!better.isNullOrEmpty()) {
                    found = better
                    usedId = original
                }
            }
            if (found.isNullOrEmpty() && usedId != subjectId) {
                found = fetchStreams(subjectId)
                usedId = subjectId
            }
            if (details?.isShortDrama == true) {
                showError("Ini drama pendek MovieBox. Link videonya sering berisi film lain, jadi tidak diputar.")
                return@launch
            }
            if (found.isNullOrEmpty()) {
                // Entri MovieBox ini kosong → coba entri lain dengan judul yang sama.
                loadingMessage = "Mencari sumber lain…"
                val named = title.copy(title = title.title.ifBlank { details?.title ?: "" })
                val alts = runCatching { Resolver.alternatives(named, setOf(subjectId, usedId)) }.getOrDefault(emptyList())
                val ownDuration = details?.durationSeconds ?: 0L
                for (alt in alts) {
                    val altDetails = runCatching { Repo.details(alt) }.getOrNull()
                    // Film lain berjudul mirip tapi durasinya jauh berbeda → bukan film yang sama.
                    val altDuration = altDetails?.durationSeconds ?: 0L
                    if (!title.isSeries && ownDuration > 0 && altDuration > 0 &&
                        kotlin.math.abs(ownDuration - altDuration) > maxOf(600L, ownDuration / 4)
                    ) continue
                    if (altDetails != null && altDetails.isSeries != title.isSeries) continue
                    if (title.isSeries && altDetails != null &&
                        altDetails.seasons.none { s -> s.number == season && episode in s.episodes }
                    ) {
                        val (s, e) = altDetails.firstEpisode()
                        if (altDetails.seasons.none { it.episodes.isNotEmpty() }) continue
                        season = s
                        episode = e
                    }
                    val f = fetchStreams(alt)
                    if (!f.isNullOrEmpty()) {
                        found = f
                        usedId = alt
                        subjectId = alt
                        if (altDetails != null) details = altDetails
                        Resolver.remember(title, alt)
                        break
                    }
                }
                if (found.isNullOrEmpty()) {
                    lastDebug = (lastDebug ?: "") + "\n--- entri lain dengan judul sama: " +
                        (if (alts.isEmpty()) "tidak ditemukan" else alts.joinToString())
                }
            }
            if (found.isNullOrEmpty()) {
                showError(lastErrorMessage ?: "Video tidak tersedia untuk judul ini.")
                return@launch
            }
            playId = usedId
            streams = rank(found)
            streamIndex = 0

            // 3) Video langsung jalan; subtitle dimuat paralel (dirender sendiri, tidak perlu memuat ulang video).
            loadingMessage = "Memutar…"
            mismatchChecked = false
            applyAudioPreference()
            val startAt = startAtMs ?: if (resume) resumePosition() else 0L
            playCurrent(startAt)

            val list = loadCaptions(usedId)
            applyCaptions(list)
            val keep = selectedCaption?.let { cur -> list.firstOrNull { it.url == cur.url || it.name == cur.name } }
            selectCaption(keep ?: autoCaption())
        }
    }

    // ---- cek durasi: cegah film yang isinya tidak sesuai judul ----

    private var mismatchChecked = false
    private var mismatchBlocked by mutableStateOf(false)
    private val mismatchedStreams = mutableSetOf<String>()

    /** Durasi film yang diharapkan (detik), hanya untuk film (bukan episode). */
    private fun expectedSeconds(): Long? {
        if (title.isSeries || season > 0) return null
        val tmdbMin = com.moviebox.app.data.Tmdb.cached(title.copy(title = title.title.ifBlank { details?.title ?: "" }))?.runtimeMin
        val metaMin = meta?.runtimeMin
        return (tmdbMin ?: metaMin)?.let { it * 60L } ?: details?.durationSeconds
    }

    private fun isSuspicious(actualSec: Long?): Boolean {
        val expected = expectedSeconds() ?: return false
        val actual = actualSec ?: return false
        if (actual <= 0) return false
        return kotlin.math.abs(actual - expected) > maxOf(480L, (expected * 0.12).toLong())
    }

    /** Dipanggil saat data durasi asli baru datang (TMDB/addon). */
    private fun recheckDuration() {
        if (readyOnce && streams.getOrNull(streamIndex)?.url !in mismatchedStreams) {
            mismatchChecked = false
            checkDurationOnReady()
        }
    }

    private fun checkDurationOnReady() {
        if (mismatchChecked) return
        mismatchChecked = true
        val stream = streams.getOrNull(streamIndex) ?: return
        val actual = player.duration.takeIf { it > 0 }?.div(1000) ?: return
        if (!isSuspicious(actual)) return
        mismatchedStreams += stream.url
        val nextGood = streams.indices.firstOrNull { i ->
            i != streamIndex && streams[i].url !in mismatchedStreams && !isSuspicious(streams[i].durationSec)
        }
        if (nextGood != null) {
            Toast.makeText(this, "Video tidak cocok dengan judul, mencoba sumber lain…", Toast.LENGTH_SHORT).show()
            streamIndex = nextGood
            mismatchChecked = false
            readyOnce = false
            playCurrent(0L)
        } else {
            // Semua sumber durasinya jauh beda dari judul ini → hampir pasti film lain. Hentikan.
            player.pause()
            val exp = expectedSeconds() ?: 0L
            lastDebug = (lastDebug ?: "") + "\n--- durasi: judul ${exp / 60} menit, video ${actual / 60} menit"
            mismatchBlocked = true
            showError(
                "Video dari MovieBox sepertinya bukan judul ini (durasi ${actual / 60} menit, " +
                    "seharusnya sekitar ${exp / 60} menit).",
            )
        }
    }

    private suspend fun fetchStreams(id: String): List<Stream>? = try {
        Repo.streams(id, season, episode)
    } catch (e: Exception) {
        lastErrorMessage = e.message
        lastDebug = (e as? BridgeException)?.debug
        null
    }

    private suspend fun loadCaptions(id: String): List<Caption> {
        val ids = streams.mapNotNull { it.resourceId }.distinct().take(3)
        for (rid in ids) {
            val list = runCatching { Repo.captions(id, rid, season, episode) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun applyCaptions(list: List<Caption>) {
        captions = list
        val counts = HashMap<String, Int>()
        captionLabels = list.associateWith { c ->
            val n = (counts[c.name] ?: 0) + 1
            counts[c.name] = n
            if (n == 1) c.name else "${c.name} ($n)"
        }
    }

    /** Urutan otomatis: resolusi terbaik ≤1080p dulu, lalu Auto/DASH, lalu >1080p. */
    private fun rank(list: List<Stream>): List<Stream> = list
        .distinctBy { it.url }
        .sortedWith(
            compareBy<Stream> { if (isSuspicious(it.durationSec)) 1 else 0 }.thenBy {
                when {
                    it.height in 1..1080 -> 0
                    it.height == 0 -> 1
                    else -> 2
                }
            }.thenByDescending { if (it.height <= 1080) it.height else -it.height },
        )

    private fun originalLanguageCode(): String? =
        Lang.code(details?.originalLanguage) ?: Lang.code(details?.country)

    /** Subtitle otomatis: Indonesia → bahasa asli film (→ Inggris kalau bahasa asli tak diketahui). */
    private fun autoCaption(): Caption? {
        val original = originalLanguageCode()
        return captions.firstOrNull { Lang.ofCaption(it) == "id" }
            ?: original?.let { code -> captions.firstOrNull { Lang.ofCaption(it) == code } }
            ?: if (original == null) captions.firstOrNull { Lang.ofCaption(it) == "en" } else null
    }

    /** Audio bahasa asli; subtitle bawaan stream dimatikan (pakai renderer subtitle sendiri). */
    private fun applyAudioPreference() {
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        originalLanguageCode()?.let { params.setPreferredAudioLanguages(it) }
        player.trackSelectionParameters = params.build()
    }

    private fun playCurrent(startAtMs: Long, keepState: Boolean = false) {
        val stream = streams.getOrNull(streamIndex) ?: run {
            showError("Semua sumber video gagal diputar.")
            return
        }
        if (!keepState) {
            loadingMessage = if (streamIndex == 0) "Memutar…" else "Mencoba sumber lain (${streamIndex + 1}/${streams.size})…"
        }

        val headers = stream.headers.toMap()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        if (userAgent != null) http.setUserAgent(userAgent)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        val item = MediaItem.Builder()
            .setUri(stream.url)
        val path = stream.url.substringBefore('?').lowercase()
        when {
            path.endsWith(".mpd") || path.contains("/dash/") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val wasPlaying = player.playWhenReady
        player.setMediaSource(factory.createMediaSource(item.build()), startAtMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = if (keepState) wasPlaying else true
        player.setPlaybackSpeed(speed)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> if (!readyOnce) {
                    readyOnce = true
                    loadingMessage = null
                    // langsung masuk Riwayat & Lanjutkan Menonton
                    saveProgress()
                    checkDurationOnReady()
                }

                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracksVersion++
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
        val next = if (title.isSeries) details?.nextEpisode(season, episode) else null
        if (next != null) {
            playEpisode(next.first, next.second)
        } else {
            Library.removeHistory(subjectId)
            finish()
        }
    }

    private fun playEpisode(s: Int, e: Int) {
        saveProgress()
        saveEntry(s, e, 0L, 0L)
        loadEpisode(s, e, resume = false)
    }

    // ================================================================== pilihan audio & subtitle

    private var cueJob: Job? = null

    private fun selectCaption(c: Caption?) {
        selectedCaption = c
        cues = emptyList()
        cueJob?.cancel()
        if (c == null) return
        cueJob = lifecycleScope.launch {
            val parsed = runCatching { com.moviebox.app.data.Subtitles.load(c.url) }.getOrDefault(emptyList())
            if (selectedCaption == c) {
                cues = parsed
                if (parsed.isEmpty()) {
                    Toast.makeText(this@PlayerActivity, "Subtitle gagal dimuat", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun offsetKey() = "offset_$subjectId"

    private fun loadOffset() {
        subtitleOffsetMs = getSharedPreferences("lumibox_subs", Context.MODE_PRIVATE).getLong(offsetKey(), 0L)
    }

    private fun changeOffset(deltaMs: Long?) {
        subtitleOffsetMs = if (deltaMs == null) 0L else (subtitleOffsetMs + deltaMs).coerceIn(-600_000L, 600_000L)
        getSharedPreferences("lumibox_subs", Context.MODE_PRIVATE).edit().putLong(offsetKey(), subtitleOffsetMs).apply()
    }

    private data class AudioOption(val label: String, val selected: Boolean, val onPick: () -> Unit)

    private fun audioOptions(): List<AudioOption> {
        val d = details
        if (d != null && d.dubs.size > 1) {
            return d.dubs.map { dub ->
                AudioOption(dub.label, dub.subjectId == playId) {
                    if (dub.subjectId != playId) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        playId = dub.subjectId
                        loadEpisode(season, episode, resume = false, forcePlayId = dub.subjectId, startAtMs = pos)
                    }
                }
            }
        }
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val options = groups.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                val label = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage } ?: "Audio ${i + 1}"
                AudioOption(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        return options.ifEmpty { listOf(AudioOption("Asli", true) {}) }
    }

    // ================================================================== riwayat

    private fun resumePosition(): Long {
        val h = Library.entry(subjectId) ?: return 0L
        if (h.season != season || h.episode != episode) return 0L
        val nearEnd = h.durationMs > 0 && h.durationMs - h.positionMs < 60_000L
        return if (h.positionMs > 10_000L && !nearEnd) h.positionMs else 0L
    }

    private fun saveProgress() {
        if (!::player.isInitialized || !readyOnce || subjectId.isBlank()) return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        saveEntry(season, episode, player.currentPosition.coerceAtLeast(0L), duration)
    }

    private fun saveEntry(s: Int, e: Int, position: Long, duration: Long) {
        if (subjectId.isBlank()) return
        Library.saveProgress(
            HistoryEntry(
                id = subjectId,
                title = title.title.ifBlank { details?.title ?: "" },
                poster = com.moviebox.app.ui.cleanPoster(title.poster, title.imdbId) ?: details?.poster,
                isSeries = title.isSeries,
                season = s,
                episode = e,
                positionMs = position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
                imdbId = title.imdbId,
            ),
        )
    }

    // ================================================================== lifecycle

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
    }

    override fun onDestroy() {
        if (com.moviebox.app.data.Together.player === togetherHook) com.moviebox.app.data.Together.player = null
        loadJob?.cancel()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ================================================================== Nonton Bareng

    private val togetherHook = object : com.moviebox.app.data.TogetherPlayer {
        override val exo: ExoPlayer get() = player
        override val loading: Boolean get() = loadingMessage != null || errorMessage != null
        override fun content(): com.moviebox.app.data.TogetherContent? {
            if (subjectId.isBlank()) return null
            return com.moviebox.app.data.TogetherContent(
                subjectId = subjectId,
                title = title.title.ifBlank { details?.title ?: "" },
                poster = title.poster ?: details?.poster,
                isSeries = title.isSeries,
                season = season,
                episode = episode,
            )
        }
        override fun switchTo(c: com.moviebox.app.data.TogetherContent) {
            if (c.subjectId == subjectId && intent.getStringExtra(EXTRA_LOCAL) == null) {
                saveProgress()
                loadEpisode(c.season, c.episode, resume = false)
            } else {
                saveProgress()
                com.moviebox.app.data.Together.opener?.invoke(c)
                finish()
            }
        }
    }

    private fun showError(message: String) {
        loadingMessage = null
        errorMessage = message
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w in 0f..1f) return w
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.02f, 1f)
    }

    private fun setBrightness(v: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = v.coerceIn(0.02f, 1f)
        window.attributes = attrs
    }

    private fun headline(): String {
        if (!title.isSeries || season <= 0) return title.title.ifBlank { details?.title ?: "" }
        val epTitle = meta?.episode(season, episode)?.title
        return if (epTitle != null) "S$season: E$episode \"$epTitle\"" else "${title.title.ifBlank { details?.title ?: "" }}  •  S$season: E$episode"
    }

    // ================================================================== UI

    @Composable
    private fun PlayerScreen() {
        var controlsVisible by remember { mutableStateOf(true) }
        var locked by remember { mutableStateOf(false) }
        var panel by remember { mutableStateOf(Panel.NONE) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf<Float?>(null) }
        var brightness by remember { mutableFloatStateOf(currentBrightness()) }
        var touchTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.takeIf { it > 0 } ?: 0L
                delay(500)
            }
        }
        // Sembunyikan kontrol otomatis setelah 4 detik.
        LaunchedEffect(controlsVisible, isPlaying, panel, touchTick, seeking) {
            if (controlsVisible && isPlaying && panel == Panel.NONE && seeking == null) {
                delay(4_000)
                controlsVisible = false
            }
        }
        // Simpan progres berkala.
        LaunchedEffect(Unit) {
            while (true) {
                delay(10_000)
                saveProgress()
            }
        }
        BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = this@PlayerActivity.player
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            SubtitleOverlay(raised = controlsVisible && !locked)

            // Lapisan sentuh: tap = tampil/sembunyi kontrol, ketuk dua kali kiri/kanan = ±10 detik.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = {
                                controlsVisible = !controlsVisible
                                touchTick++
                            },
                            onDoubleTap = { offset ->
                                if (!locked) {
                                    val delta = if (offset.x < size.width / 2) -10_000L else 10_000L
                                    player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
                                    controlsVisible = true
                                    touchTick++
                                }
                            },
                        )
                    },
            ) {
                if (controlsVisible && locked) {
                    LockedOverlay(onUnlock = {
                        locked = false
                        touchTick++
                    })
                } else if (controlsVisible && loadingMessage == null && errorMessage == null) {
                    Controls(
                        position = position,
                        duration = duration,
                        seeking = seeking,
                        onSeeking = {
                            seeking = it
                            touchTick++
                        },
                        onSeekDone = {
                            seeking?.let { f -> if (duration > 0) player.seekTo((f * duration).toLong()) }
                            seeking = null
                        },
                        brightness = brightness,
                        onBrightness = {
                            brightness = it
                            setBrightness(it)
                            touchTick++
                        },
                        onLock = {
                            locked = true
                            touchTick++
                        },
                        onPanel = { panel = it },
                        onTouch = { touchTick++ },
                    )
                }
            }

            if (buffering && loadingMessage == null && errorMessage == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Accent)
            }
            loadingMessage?.let { LoadingOverlay(it) }
            errorMessage?.let { ErrorOverlay(it) }

            when (panel) {
                Panel.SPEED -> SpeedPanel(onClose = { panel = Panel.NONE })
                Panel.AUDIO_SUBS -> AudioSubsPanel(onClose = { panel = Panel.NONE })
                Panel.EPISODES -> EpisodesPanel(onClose = { panel = Panel.NONE })
                Panel.NONE -> Unit
            }
        }
    }

    @Composable
    private fun Controls(
        position: Long,
        duration: Long,
        seeking: Float?,
        onSeeking: (Float) -> Unit,
        onSeekDone: () -> Unit,
        brightness: Float,
        onBrightness: (Float) -> Unit,
        onLock: () -> Unit,
        onPanel: (Panel) -> Unit,
        onTouch: () -> Unit,
    ) {
        val next = if (title.isSeries) details?.nextEpisode(season, episode) else null
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            // Atas: kembali · judul · kunci
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Text(
                    headline(),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                com.moviebox.app.ui.TogetherButton(28.dp)
                IconButton(onClick = onLock) {
                    Icon(PlayerIcons.LockOpen, contentDescription = "Kunci layar", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Kiri: kecerahan
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(PlayerIcons.Brightness, contentDescription = "Kecerahan", tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(10.dp))
                VerticalBar(value = brightness, onChange = onBrightness)
            }

            // Tengah: mundur 10 · putar/jeda · maju 10
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(forward = false) {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    onTouch()
                }
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        onTouch()
                    },
                    modifier = Modifier.size(84.dp),
                ) {
                    Icon(
                        if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = if (isPlaying) "Jeda" else "Putar",
                        tint = Color.White,
                        modifier = Modifier.size(72.dp),
                    )
                }
                SkipButton(forward = true) {
                    player.seekTo(player.currentPosition + 10_000L)
                    onTouch()
                }
            }

            // Bawah: seekbar + tombol aksi
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val progress = if (duration > 0) position.toFloat() / duration else 0f
                    Slider(
                        value = (seeking ?: progress).coerceIn(0f, 1f),
                        onValueChange = onSeeking,
                        onValueChangeFinished = onSeekDone,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Spacer(Modifier.width(16.dp))
                    val shown = seeking?.let { (it * duration).toLong() } ?: position
                    Text(formatTime((duration - shown).coerceAtLeast(0L)), color = Color.White, fontSize = 15.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionButton(PlayerIcons.Speed, "Kecepatan (${formatSpeed(speed)})") { onPanel(Panel.SPEED) }
                    if (title.isSeries && (details?.seasons?.isNotEmpty() == true)) {
                        ActionButton(PlayerIcons.Episodes, "Episode") { onPanel(Panel.EPISODES) }
                    }
                    ActionButton(PlayerIcons.Subtitles, "Audio & Subtitle") { onPanel(Panel.AUDIO_SUBS) }
                    if (next != null) {
                        ActionButton(PlayerIcons.SkipNext, "Eps. Berikutnya") { playEpisode(next.first, next.second) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PlayerIcons.Replay,
                contentDescription = if (forward) "Maju 10 detik" else "Mundur 10 detik",
                tint = Color.White,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer { scaleX = if (forward) -1f else 1f },
            )
            Text("10", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun VerticalBar(value: Float, onChange: (Float) -> Unit) {
        val current by androidx.compose.runtime.rememberUpdatedState(value)
        Box(
            Modifier
                .width(40.dp)
                .height(150.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, drag ->
                        change.consume()
                        onChange((current - drag / size.height).coerceIn(0.02f, 1f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(value.coerceIn(0f, 1f))
                        .background(Color.White),
                )
            }
        }
    }

    @Composable
    private fun LockedOverlay(onUnlock: () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onUnlock)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PlayerIcons.Lock, contentDescription = "Buka kunci", tint = Color.Black, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Layar terkunci", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Ketuk untuk membuka kunci", color = TextDim, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun LoadingOverlay(message: String) {
        Box(Modifier.fillMaxSize()) {
            IconButton(onClick = { finish() }, modifier = Modifier.padding(20.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(52.dp), color = Accent)
                Spacer(Modifier.height(14.dp))
                Text(headline(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(message, color = TextDim, fontSize = 14.sp)
            }
        }
    }

    @Composable
    private fun ErrorOverlay(message: String) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(message, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mismatchBlocked) {
                        PanelButton("Tetap putar") {
                            mismatchBlocked = false
                            errorMessage = null
                            player.play()
                        }
                    } else {
                        PanelButton("Coba lagi") { loadEpisode(season, episode, resume = true) }
                    }
                    if (!lastDebug.isNullOrBlank()) {
                        PanelButton("Salin info teknis") {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Lumi Box", lastDebug.orEmpty()))
                            Toast.makeText(this@PlayerActivity, "Info teknis disalin", Toast.LENGTH_SHORT).show()
                        }
                    }
                    PanelButton("Tutup") { finish() }
                }
            }
        }
    }

    @Composable
    private fun PanelButton(text: String, onClick: () -> Unit) {
        Text(
            text,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }

    @Composable
    private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f)) { content() }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }

    @Composable
    private fun SpeedPanel(onClose: () -> Unit) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        PanelFrame("Kecepatan Pemutaran", onClose) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                speeds.forEach { v ->
                    val selected = v == speed
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                speed = v
                                player.setPlaybackSpeed(v)
                                onClose()
                            }
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(if (selected) 22.dp else 14.dp)
                                .clip(CircleShape)
                                .background(if (selected) Accent else Color.White.copy(alpha = 0.6f)),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            formatSpeed(v) + if (v == 1f) " (Normal)" else "",
                            color = if (selected) Color.White else TextDim,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AudioSubsPanel(onClose: () -> Unit) {
        @Suppress("UNUSED_VARIABLE")
        val version = tracksVersion // baca ulang saat track berubah
        val audio = audioOptions()
        PanelFrame("Audio & Subtitle", onClose) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Audio", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(audio) { a ->
                            OptionRow(a.label, a.selected) {
                                a.onPick()
                                onClose()
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Subtitle", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        item {
                            OptionRow("Mati", selectedCaption == null) {
                                selectCaption(null)
                                onClose()
                            }
                        }
                        items(captions) { c ->
                            OptionRow(captionLabels[c] ?: c.name, c == selectedCaption) {
                                selectCaption(c)
                                onClose()
                            }
                        }
                        if (captions.isEmpty()) {
                            item {
                                Text("Subtitle tidak tersedia", color = TextDim, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
                Column(Modifier.weight(1.2f)) {
                    Text("Sinkron subtitle", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    SyncControls()
                    Spacer(Modifier.height(18.dp))
                    Text("Sumber video", color = TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn {
                        items(streams.indices.toList()) { i ->
                            val st = streams[i]
                            val odd = st.url in mismatchedStreams || isSuspicious(st.durationSec)
                            val label = "Sumber ${i + 1} · ${st.label}" + if (odd) "  (durasi beda)" else ""
                            OptionRow(label, i == streamIndex) {
                                if (i != streamIndex) {
                                    val pos = player.currentPosition.coerceAtLeast(0L)
                                    streamIndex = i
                                    readyOnce = false
                                    mismatchChecked = true
                                    playCurrent(pos)
                                }
                                onClose()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Geser subtitle per 0,1 detik (+ = subtitle muncul lebih lambat). */
    @Composable
    private fun SyncControls() {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SyncButton("−0.5") { changeOffset(-500L) }
                SyncButton("−0.1") { changeOffset(-100L) }
                Text(
                    "%+.1f dtk".format(java.util.Locale.US, subtitleOffsetMs / 1000.0),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(78.dp),
                )
                SyncButton("+0.1") { changeOffset(100L) }
                SyncButton("+0.5") { changeOffset(500L) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        subtitleOffsetMs > 0 -> "Subtitle diperlambat"
                        subtitleOffsetMs < 0 -> "Subtitle dipercepat"
                        else -> "Tidak digeser"
                    },
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (subtitleOffsetMs != 0L) {
                    Text(
                        "Reset",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { changeOffset(null) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun SyncButton(label: String, onClick: () -> Unit) {
        Text(
            label,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }

    /** Subtitle yang dirender sendiri (supaya bisa disinkronkan). */
    @Composable
    private fun SubtitleOverlay(raised: Boolean) {
        var text by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(cues, subtitleOffsetMs) {
            while (true) {
                text = com.moviebox.app.data.Subtitles.active(cues, player.currentPosition, subtitleOffsetMs)
                delay(100)
            }
        }
        val current = text ?: return
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                current,
                modifier = Modifier
                    .padding(bottom = if (raised) 120.dp else 28.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = if (selected) Color.White else TextDim,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 16.sp,
            )
        }
    }

    @Composable
    private fun EpisodesPanel(onClose: () -> Unit) {
        val d = details ?: return
        var shownSeason by remember { mutableIntStateOf(if (season > 0) season else d.seasons.firstOrNull()?.number ?: 1) }
        PanelFrame("Episode", onClose) {
            Column {
                if (d.seasons.size > 1) {
                    SeasonChips(d, shownSeason) { shownSeason = it }
                    Spacer(Modifier.height(10.dp))
                }
                val s = d.seasons.firstOrNull { it.number == shownSeason } ?: d.seasons.first()
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(s.episodes.sorted()) { ep ->
                        val current = s.number == season && ep == episode
                        val name = meta?.episode(s.number, ep)?.title
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (current) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onClose()
                                    if (!current) playEpisode(s.number, ep)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$ep",
                                color = if (current) Accent else TextDim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.width(48.dp),
                            )
                            Text(
                                name ?: "Episode $ep",
                                color = Color.White,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_SERIES = "series"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_TMDB = "tmdb"
        private const val EXTRA_LOCAL = "local"
        private const val EXTRA_LOCAL_SUB_NAMES = "local_sub_names"
        private const val EXTRA_LOCAL_SUB_PATHS = "local_sub_paths"

        /** Putar file hasil unduhan (offline). */
        fun startOffline(context: Context, d: com.moviebox.app.data.DownloadItem) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, d.subjectId)
                    .putExtra(EXTRA_TITLE, d.title)
                    .putExtra(EXTRA_POSTER, d.poster)
                    .putExtra(EXTRA_SERIES, d.isSeries)
                    .putExtra(EXTRA_IMDB, d.imdbId)
                    .putExtra(EXTRA_SEASON, d.season)
                    .putExtra(EXTRA_EPISODE, d.episode)
                    .putExtra(EXTRA_LOCAL, d.filePath)
                    .putExtra(EXTRA_LOCAL_SUB_NAMES, d.subtitles.map { it.first }.toTypedArray())
                    .putExtra(EXTRA_LOCAL_SUB_PATHS, d.subtitles.map { it.second }.toTypedArray()),
            )
        }
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"

        /** season/episode 0 = film; -1 = serial, pilih episode pertama otomatis. */
        fun start(context: Context, title: Title, season: Int, episode: Int) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, title.id)
                    .putExtra(EXTRA_TITLE, title.title)
                    .putExtra(EXTRA_POSTER, title.poster)
                    .putExtra(EXTRA_SERIES, title.isSeries)
                    .putExtra(EXTRA_YEAR, title.year)
                    .putExtra(EXTRA_IMDB, title.imdbId)
                    .putExtra(EXTRA_TMDB, title.tmdbId)
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

        private fun formatTime(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        private fun formatSpeed(v: Float): String =
            if (v % 1f == 0f) "${v.toInt()}x" else "${v}x"
    }
}
