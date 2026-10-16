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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Logo Lumi Box: kotak amber dengan huruf "L". */
@Composable
fun LumiLogo(size: Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Brush.linearGradient(listOf(Accent, AccentDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "L",
            color = Color.Black,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.62f).sp,
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
    Box(
        base
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (rank != null) {
            Top10Badge(Modifier.align(Alignment.TopStart))
        }
        if (item.isSeries) {
            Text(
                "SERIAL",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                color = Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
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
        Text("TOP", color = Color.Black, fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 8.sp)
        Text("10", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
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
) {
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
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
                model = entry.poster,
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
            IconButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Close, contentDescription = "Hapus", tint = Color.White)
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
