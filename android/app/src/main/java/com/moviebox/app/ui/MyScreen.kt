package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moviebox.app.data.Library
import com.moviebox.app.data.Title
import com.moviebox.app.data.Tmdb
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Tab "Lumi Saya": tombol History · Unduhan · Setting, lalu Lanjutkan Menonton & Daftar Saya. */
@Composable
fun MyScreen(
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LumiLogo(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Lumi Saya",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MenuTile(PlayerIcons.History, "History", Modifier.weight(1f), onHistory)
                MenuTile(PlayerIcons.Download, "Unduhan", Modifier.weight(1f), onDownloads)
                MenuTile(PlayerIcons.Settings, "Setting", Modifier.weight(1f), onSettings)
            }
        }

        item {
            if (Library.history.isEmpty()) {
                EmptyCard("Lanjutkan Menonton", "Film dan serial yang kamu tonton akan muncul di sini.")
            } else {
                ContinueRow(onPlayEntry = onPlay, onDetails = onDetails)
            }
        }

        item {
            val list = Library.myList.toList()
            if (list.isEmpty()) {
                EmptyCard("Daftar Saya", "Ketuk \"+ Daftar Saya\" di sebuah film untuk menyimpannya di sini.")
            } else {
                Column {
                    SectionTitle("Daftar Saya")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list.distinctBy { it.key }, key = { it.key }) { item ->
                            Poster(item, onClick = { onOpen(item) }, onLongClick = { onDetails(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .background(Surface1, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.padding(top = 6.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(title: String, message: String) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(10.dp))
            .padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.padding(top = 6.dp))
        Text(message, color = TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pengaturan kunci TMDB (poster & deskripsi Indonesia → Inggris → asli, genre, skor, rating usia). */
@Composable
fun TmdbCard() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(Tmdb.apiKey) }
    var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var checking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(10.dp))
            .padding(18.dp),
    ) {
        Text("Kunci TMDB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.padding(top = 6.dp))
        Text(
            if (Tmdb.enabled) "Aktif. Poster & deskripsi memakai bahasa Indonesia bila tersedia."
            else "Isi kunci API gratis dari themoviedb.org (Pengaturan → API) untuk poster & deskripsi berbahasa Indonesia, genre, skor, dan rating usia.",
            color = TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(top = 10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Tempel kunci API / token di sini") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.padding(top = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(
                if (checking) "Mengecek…" else "Simpan",
                onClick = {
                    if (checking) return@PlayButton
                    val key = text.trim()
                    if (key.isEmpty()) {
                        Tmdb.setKey("")
                        status = "Kunci dihapus."
                        return@PlayButton
                    }
                    checking = true
                    scope.launch {
                        val ok = Tmdb.testKey(key)
                        checking = false
                        if (ok) {
                            Tmdb.setKey(key)
                            status = "Tersimpan ✓"
                        } else {
                            status = "Kunci ditolak TMDB. Periksa lagi."
                        }
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            status?.let { Text(it, color = TextDim) }
        }
    }
}
