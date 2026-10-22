package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moviebox.app.data.DownloadItem
import com.moviebox.app.data.Downloads
import com.moviebox.app.data.HistoryEntry
import com.moviebox.app.data.Library
import com.moviebox.app.data.Title
import kotlinx.coroutines.delay

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun GroupHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(Accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = TextDim, fontSize = 14.sp)
    }
}

@Composable
private fun TypeTag(isSeries: Boolean) {
    Text(
        if (isSeries) "SERIAL" else "FILM",
        modifier = Modifier
            .background(if (isSeries) AccentDeep else Color(0xFF3A3A3A), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ThumbRow(
    poster: String?,
    imdbId: String?,
    title: String,
    isSeries: Boolean,
    subtitle: String,
    progress: Float?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val tmdb = rememberTmdb(Title(id = "", title = title, isSeries = isSeries, year = null, poster = poster, imdbId = imdbId))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2),
        ) {
            AsyncImage(
                model = tmdb?.poster ?: cleanPoster(poster, imdbId),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TypeTag(isSeries)
            Spacer(Modifier.height(4.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextDim, fontSize = 13.sp)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(progress)
            }
        }
        trailing()
    }
}

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 60_000
    return when {
        d < 1 -> "baru saja"
        d < 60 -> "$d menit lalu"
        d < 60 * 24 -> "${d / 60} jam lalu"
        else -> "${d / (60 * 24)} hari lalu"
    }
}

/** Riwayat tonton, dipisah Film dan Serial. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onPlay: (Title) -> Unit) {
    val all = Library.watched.toList()
    val films = all.filter { !it.isSeries }
    val series = all.filter { it.isSeries }
    Column(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        PageHeader("History", onBack) {
            if (all.isNotEmpty()) {
                Text(
                    "Hapus semua",
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { Library.clearWatched() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (all.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Belum ada yang ditonton.", color = TextDim)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (films.isNotEmpty()) {
                item(key = "h-film") { GroupHeader("Film", films.size) }
                items(films, key = { "f-" + it.id }) { e -> HistoryRow(e, onPlay) }
            }
            if (series.isNotEmpty()) {
                item(key = "h-serial") { GroupHeader("Serial", series.size) }
                items(series, key = { "s-" + it.id }) { e -> HistoryRow(e, onPlay) }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onPlay: (Title) -> Unit) {
    ThumbRow(
        poster = e.poster,
        imdbId = e.imdbId,
        title = e.title,
        isSeries = e.isSeries,
        subtitle = (if (e.isSeries && e.season > 0) "S${e.season}:E${e.episode} · " else "") + ago(e.updatedAt),
        progress = e.progress.takeIf { it > 0f },
        onClick = { onPlay(e.asTitle()) },
    ) {
        IconButton(onClick = { Library.removeWatched(e.id) }) {
            Icon(PlayerIcons.Delete, contentDescription = "Hapus", tint = TextDim)
        }
    }
}

/** Daftar unduhan, dipisah Film dan Serial, dengan progres. */
@Composable
fun DownloadsScreen(onBack: () -> Unit, onPlayOffline: (DownloadItem) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val all = Downloads.items.toList()
    val films = all.filter { !it.isSeries }
    val series = all.filter { it.isSeries }.sortedWith(compareBy({ it.title }, { it.season }, { it.episode }))
    Column(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        PageHeader("Unduhan", onBack)
        if (all.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Belum ada unduhan. Buka sebuah film lalu ketuk \"Download\".",
                    color = TextDim,
                )
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (films.isNotEmpty()) {
                item(key = "d-film") { GroupHeader("Film", films.size) }
                items(films, key = { "df-" + it.downloadId }) { d -> DownloadRow(d, tick, onPlayOffline) }
            }
            if (series.isNotEmpty()) {
                item(key = "d-serial") { GroupHeader("Serial", series.size) }
                items(series, key = { "ds-" + it.downloadId }) { d -> DownloadRow(d, tick, onPlayOffline) }
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, tick: Int, onPlayOffline: (DownloadItem) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick
    val status = Downloads.status(d)
    val sizeMb = status.bytes / 1_048_576
    val subtitle = when (status.state) {
        Downloads.STATE_DONE -> "Selesai · ${sizeMb} MB" + if (d.subtitles.isNotEmpty()) " · ${d.subtitles.size} subtitle" else ""
        Downloads.STATE_FAILED -> "Gagal mengunduh"
        else -> "Mengunduh ${(status.progress * 100).toInt()}%" + if (sizeMb > 0) " dari ${sizeMb} MB" else ""
    }
    val label = d.title + if (d.isSeries) " · S${d.season}:E${d.episode}" else ""
    ThumbRow(
        poster = d.poster,
        imdbId = d.imdbId,
        title = label,
        isSeries = d.isSeries,
        subtitle = subtitle,
        progress = if (status.state == Downloads.STATE_RUNNING) status.progress else null,
        onClick = { if (status.state == Downloads.STATE_DONE) onPlayOffline(d) },
    ) {
        Row {
            if (status.state == Downloads.STATE_DONE) {
                IconButton(onClick = { onPlayOffline(d) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Putar", tint = Color.White)
                }
            }
            IconButton(onClick = { Downloads.delete(d) }) {
                Icon(PlayerIcons.Delete, contentDescription = "Hapus", tint = TextDim)
            }
        }
    }
}

/** Setting: kunci API. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    SettingsBody(onBack)
}

@Composable
private fun ContentFilterCard() {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Surface2, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Text("Konten", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Kalau dimatikan, judul ini tidak muncul di mana pun: beranda, kategori, rekomendasi, dan pencarian.",
            color = TextDim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        FilterSwitch(
            title = "Drama pendek (DramaBox dkk.)",
            subtitle = "Drama pendek / dubbing seperti DramaBox, ReelShort, ShortMax. Videonya kadang tidak sesuai judul.",
            checked = com.moviebox.app.data.ContentFilter.showShortDrama,
            onChange = com.moviebox.app.data.ContentFilter::setShortDrama,
        )
        FilterSwitch(
            title = "Film & serial India / Bollywood",
            subtitle = "Termasuk film berbahasa Hindi, Tamil, Telugu, Malayalam, dan drama Pakistan (Urdu).",
            checked = com.moviebox.app.data.ContentFilter.showIndian,
            onChange = com.moviebox.app.data.ContentFilter::setIndian,
        )
    }
}

@Composable
private fun FilterSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = TextDim, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

@Composable
private fun SettingsBody(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        PageHeader("Setting", onBack)
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { TmdbCard() }
            item { ContentFilterCard() }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Tentang", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Lumi Box · info film dari TMDB, video dari MovieBox.",
                        color = TextDim,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

