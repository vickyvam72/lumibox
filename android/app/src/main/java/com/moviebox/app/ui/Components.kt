@file:OptIn(ExperimentalFoundationApi::class)

package com.moviebox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moviebox.app.data.HistoryEntry
import com.moviebox.app.data.Title

/** Logo Lumi Box: kotak gradasi biru muda dengan huruf "L" dan kilau. */
@Composable
fun LumiLogo(size: Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.linearGradient(listOf(AccentLight, Color(0xFF4A9CF5), AccentDeep))),
        contentAlignment = Alignment.Center,
    ) {
        // Ikon depan launcher (108dp, area terlihat 72dp) → skala 1.5x lalu dipotong lingkaran.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.moviebox.app.R.drawable.ic_launcher_foreground),
            contentDescription = "Lumi Box",
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

/** Poster 2:3 seperti di aplikasi streaming. Tap = putar/buka, tahan = detail. */
@Composable
fun Poster(
    item: Title,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 112.dp,
    rank: Int? = null,
) {
    val base = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (menu) {
        TitleActionsSheet(
            title = item,
            onDismiss = { menu = false },
            onInfo = onLongClick,
            onDownload = {
                if (item.isSeries) onLongClick() else startDownload(context, scope, item, 0, 0)
            },
        )
    }
    Box(
        base
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        // Judul sebagai cadangan kalau gambar gagal dimuat.
        Text(
            item.title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = TextDim,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        val tmdb = rememberTmdb(item)
        AsyncImage(
            model = tmdb?.poster ?: cleanPoster(item.poster, item.imdbId),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val info = mergeInfo(item, tmdb, null, null)
        val score = (tmdb?.score ?: item.rating)?.toDoubleOrNull()?.takeIf { it > 0 }
        // Atas: judul kecil (kiri) + skor (kanan)
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(start = if (rank != null) 30.dp else 5.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                info.title.ifBlank { item.title },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 1.dp, end = 3.dp),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f),
                ),
            )
            if (score != null) {
                Text(
                    "★ " + "%.1f".format(java.util.Locale.US, score),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = AccentLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (rank != null) {
            Top10Badge(Modifier.align(Alignment.TopStart))
        }
        // Bawah: genre · rating usia · tahun · durasi (seperti di halaman info)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.92f))))
                .padding(start = 5.dp, end = 5.dp, top = 18.dp, bottom = 4.dp),
        ) {
            val genres = info.genres.take(2).joinToString(" • ")
            if (genres.isNotBlank()) {
                Text(
                    genres,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 11.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tag jenis: FILM (putih) / SERIAL (biru)
                Text(
                    if (item.isSeries) "SERIAL" else "FILM",
                    modifier = Modifier
                        .background(if (item.isSeries) Accent else Color.White, RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp),
                    color = Color(0xFF00172E),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.4.sp,
                    lineHeight = 10.sp,
                )
                Spacer(Modifier.width(3.dp))
                info.ageRating?.let { age ->
                    Text(
                        age,
                        modifier = Modifier
                            .border(0.8.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 2.dp),
                        color = Color.White,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                val rest = buildList {
                    info.year?.let { add(it) }
                    if (!item.isSeries) info.runtimeMin?.let { m -> add(if (m >= 60) "${m / 60}j ${m % 60}m" else "${m}m") }
                }.joinToString(" • ")
                Text(
                    rest,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 10.sp,
                )
            }
        }
    }
}

@Composable
fun Top10Badge(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Accent, RoundedCornerShape(bottomEnd = 4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TOP", color = Color(0xFF00172E), fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 8.sp)
        Text("10", color = Color(0xFF00172E), fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
    }
}

/** Baris berjudul berisi poster yang bisa digeser. */
@Composable
fun TitleRow(
    title: String,
    items: List<Title>,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    top10: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    Column {
        RowHeader(title, onMore)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items.distinctBy { it.key }, key = { _, it -> it.key }) { index, item ->
                Poster(
                    item = item,
                    onClick = { onOpen(item) },
                    onLongClick = { onDetails(item) },
                    rank = if (top10 && index < 10) index + 1 else null,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Kartu "Lanjutkan Menonton": poster + tombol play + progress + info/hapus. */
@Composable
fun ContinueCard(
    entry: HistoryEntry,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val tmdb = rememberTmdb(entry.asTitle())
    if (menu) {
        TitleActionsSheet(
            title = entry.asTitle(),
            onDismiss = { menu = false },
            onInfo = onInfo,
            onDownload = { startDownload(context, scope, entry.asTitle(), entry.season, entry.episode) },
            onRemove = onRemove,
        )
    }
    Column(
        Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface1),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Surface2)
                .clickable(onClick = onPlay),
        ) {
            AsyncImage(
                model = tmdb?.poster ?: cleanPoster(entry.poster, entry.imdbId),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Putar", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            if (entry.isSeries) {
                Text(
                    "S${entry.season}:E${entry.episode}",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        ProgressLine(entry.progress)
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onInfo, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White)
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 10.dp)
                    .background(Color(0xFF444444)),
            )
            IconButton(onClick = { menu = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Lainnya", tint = Color.White)
            }
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color(0xFF4D4D4D)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(Accent),
        )
    }
}

/** Tombol putih besar "▶ Putar" ala aplikasi streaming. */
@Composable
fun PlayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
}

/** Tombol abu-abu sekunder (mis. "+ Daftar Saya"). */
@Composable
fun GrayButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun CenterLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent)
    }
}

@Composable
fun SmallLoading(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Accent)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, color = TextDim)
        PlayButton("Coba lagi", onClick = onRetry)
    }
}

/** Poster tanpa badge rating: kalau poster lama dari EasyRatings, ganti dengan poster bersih (metahub). */
fun cleanPoster(poster: String?, imdbId: String?): String? {
    if (poster != null && poster.contains("easyratingsdb", ignoreCase = true) && imdbId != null && imdbId.startsWith("tt")) {
        return "https://images.metahub.space/poster/medium/$imdbId/img"
    }
    return poster
}
