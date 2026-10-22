package com.moviebox.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moviebox.app.data.Downloads
import com.moviebox.app.data.Library
import com.moviebox.app.data.Title
import com.moviebox.app.data.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Mulai unduh (film: season/episode 0) dan beri tahu pengguna. */
fun startDownload(context: Context, scope: CoroutineScope, title: Title, season: Int, episode: Int) {
    Toast.makeText(context, "Menyiapkan unduhan…", Toast.LENGTH_SHORT).show()
    scope.launch {
        val message = try {
            Downloads.start(title, season, episode)
        } catch (e: Exception) {
            e.message ?: "Gagal mengunduh."
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

/** Menu titik tiga: Informasi · Download · Bukan untuk Saya · Saya Suka · (Hapus dari Baris). */
@Composable
fun TitleActionsSheet(
    title: Title,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onDownload: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val taste = Library.taste(title)
    val genres = (Tmdb.cached(title)?.genres ?: emptyList()).ifEmpty { title.genres }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Color(0xFF2B2B2B))
                    .clickable(enabled = true, onClick = {})
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
                    }
                }
                ActionRow(Icons.Default.Info, if (title.isSeries) "Episode & Informasi" else "Informasi") {
                    onDismiss()
                    onInfo()
                }
                ActionRow(PlayerIcons.Download, if (title.isSeries) "Download Episode" else "Download") {
                    onDismiss()
                    onDownload()
                }
                ActionRow(PlayerIcons.ThumbDown, "Bukan untuk Saya", selected = taste < 0) {
                    Library.rate(title, if (taste < 0) 0 else -1, genres)
                    onDismiss()
                }
                ActionRow(PlayerIcons.ThumbUp, "Saya Suka", selected = taste > 0) {
                    Library.rate(title, if (taste > 0) 0 else 1, genres)
                    onDismiss()
                }
                if (onRemove != null) {
                    ActionRow(Icons.Default.Close, "Hapus dari Baris") {
                        onDismiss()
                        onRemove()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Accent else Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(26.dp))
        Text(
            label + if (selected) "  ✓" else "",
            color = if (selected) Accent else Color.White.copy(alpha = 0.92f),
            fontSize = 18.sp,
        )
    }
}
