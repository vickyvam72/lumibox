@file:OptIn(ExperimentalMaterial3Api::class)

package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import android.widget.Toast
import com.moviebox.app.PlayerActivity
import com.moviebox.app.data.BridgeException
import com.moviebox.app.data.Details
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Stream
import kotlinx.coroutines.launch

class DetailViewModel(initialId: String) : ViewModel() {
    var currentId by mutableStateOf(initialId)
        private set
    var details by mutableStateOf<Details?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedSeason by mutableIntStateOf(0)

    init {
        load(initialId)
    }

    fun load(id: String = currentId) {
        currentId = id
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val d = Repo.details(id)
                details = d
                if (d.seasons.none { it.number == selectedSeason }) {
                    selectedSeason = d.seasons.firstOrNull()?.number ?: 0
                }
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat detail"
            } finally {
                loading = false
            }
        }
    }
}

/** Status dialog pemilihan kualitas. */
private data class Picker(
    val season: Int,
    val episode: Int,
    val loading: Boolean = true,
    val streams: List<Stream> = emptyList(),
    val error: String? = null,
    val debug: String? = null,
)

@Composable
fun DetailScreen(subjectId: String, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "detail-$subjectId") { DetailViewModel(subjectId) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var preparing by remember { mutableStateOf(false) }

    fun launchPlayer(stream: Stream, season: Int, episode: Int) {
        val d = vm.details ?: return
        picker = null
        preparing = true
        scope.launch {
            val captions = try {
                Repo.captions(vm.currentId, stream.resourceId, season, episode)
            } catch (_: Exception) {
                emptyList()
            }
            preparing = false
            val label = if (season > 0) "${d.title} • S$season E$episode" else d.title
            PlayerActivity.start(
                context = context,
                title = label,
                resumeKey = "${vm.currentId}:$season:$episode",
                stream = stream,
                captions = captions,
            )
        }
    }

    fun requestPlay(season: Int, episode: Int) {
        picker = Picker(season, episode)
        scope.launch {
            try {
                val streams = Repo.streams(vm.currentId, season, episode)
                    .distinctBy { it.label }
                when {
                    streams.isEmpty() -> picker = picker?.copy(loading = false, error = "Tidak ada sumber video.")
                    streams.size == 1 -> launchPlayer(streams.first(), season, episode)
                    else -> picker = picker?.copy(loading = false, streams = streams)
                }
            } catch (e: Exception) {
                picker = picker?.copy(
                    loading = false,
                    error = e.message ?: "Gagal mengambil sumber video",
                    debug = (e as? BridgeException)?.debug,
                )
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val d = vm.details
        when {
            d == null && vm.loading -> CenterLoading()
            d == null -> ErrorBox(
                vm.error ?: "Gagal memuat",
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> DetailContent(
                d = d,
                vm = vm,
                onPlay = { s, e -> requestPlay(s, e) },
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
        }

        if (preparing || (vm.loading && vm.details != null)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }

    picker?.let { p ->
        QualityDialog(
            picker = p,
            onPick = { launchPlayer(it, p.season, p.episode) },
            onDismiss = { picker = null },
        )
    }
}

@Composable
private fun DetailContent(d: Details, vm: DetailViewModel, onPlay: (Int, Int) -> Unit) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset.calculateBottomPadding() + 24.dp),
    ) {
        item { Header(d) }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val canQuickPlay = !d.isSeries || d.seasons.isEmpty()
                if (canQuickPlay) {
                    Button(
                        onClick = { if (d.isSeries) onPlay(1, 1) else onPlay(0, 0) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Putar", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (d.dubs.size > 1) {
                    Text("Bahasa audio", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        if (d.dubs.size > 1) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(d.dubs, key = { it.subjectId }) { dub ->
                        FilterChip(
                            selected = dub.subjectId == vm.currentId,
                            onClick = { if (dub.subjectId != vm.currentId) vm.load(dub.subjectId) },
                            label = { Text(dub.label) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                d.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                }
                d.stars?.let {
                    Text(
                        "Pemeran: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (d.isSeries && d.seasons.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Episode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (d.seasons.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(d.seasons, key = { it.number }) { s ->
                            FilterChip(
                                selected = s.number == vm.selectedSeason,
                                onClick = { vm.selectedSeason = s.number },
                                label = { Text("Season ${s.number}") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            val season = d.seasons.firstOrNull { it.number == vm.selectedSeason } ?: d.seasons.first()
            val rows = season.episodes.chunked(5)
            items(rows.size, key = { "s${season.number}-r$it" }) { index ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val row = rows[index]
                    row.forEach { ep ->
                        OutlinedButton(
                            onClick = { onPlay(season.number, ep) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) {
                            Text("$ep")
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Header(d: Details) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp),
    ) {
        AsyncImage(
            model = d.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(262.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.6f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp)
                .offset(y = (-8).dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AsyncImage(
                model = d.poster,
                contentDescription = d.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(110.dp)
                    .height(165.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    d.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(
                    d.year,
                    d.rating?.let { "★ $it" },
                    d.duration,
                    if (d.isSeries) "Series" else "Film",
                ).joinToString(" • ")
                Text(meta, style = MaterialTheme.typography.labelMedium, color = Accent)
                if (d.genres.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        d.genres.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityDialog(picker: Picker, onPick: (Stream) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (picker.season > 0) "S${picker.season} E${picker.episode} — Pilih kualitas" else "Pilih kualitas")
        },
        text = {
            when {
                picker.loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(32.dp)) }

                picker.error != null -> ErrorDetails(picker.error, picker.debug)

                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    picker.streams.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(s) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(10.dp))
                            Text(s.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            formatSize(s.sizeBytes)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}

@Composable
private fun ErrorDetails(error: String, debug: String?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showDebug by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(error)
        if (debug != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDebug = !showDebug }) {
                    Text(if (showDebug) "Sembunyikan info" else "Info teknis")
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(debug))
                    Toast.makeText(context, "Info teknis disalin", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Salin")
                }
            }
            if (showDebug) {
                Spacer(Modifier.height(8.dp))
                Text(
                    debug,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }
        }
    }
}
