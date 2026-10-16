package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.moviebox.app.data.Details
import com.moviebox.app.data.Library
import com.moviebox.app.data.Repo
import kotlinx.coroutines.launch

class DetailViewModel(private val subjectId: String) : ViewModel() {
    var details by mutableStateOf<Details?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedSeason by mutableIntStateOf(0)

    init {
        load()
    }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val d = Repo.details(subjectId)
                details = d
                val watched = Library.entry(subjectId)
                selectedSeason = when {
                    watched != null && d.seasons.any { it.number == watched.season } -> watched.season
                    else -> d.seasons.minByOrNull { it.number }?.number ?: 0
                }
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat detail"
            } finally {
                loading = false
            }
        }
    }
}

/**
 * Halaman detail. [onPlay] dipanggil dengan (detail, season, episode);
 * season/episode 0 berarti film.
 */
@Composable
fun DetailScreen(
    subjectId: String,
    onBack: () -> Unit,
    onPlay: (Details, Int, Int) -> Unit,
) {
    val vm: DetailViewModel = viewModel(key = "detail-$subjectId") { DetailViewModel(subjectId) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        val d = vm.details
        when {
            d == null && vm.loading -> CenterLoading()
            d == null -> ErrorBox(
                vm.error ?: "Gagal memuat",
                onRetry = { vm.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> DetailContent(d, vm, onPlay)
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
        }
    }
}

@Composable
private fun DetailContent(d: Details, vm: DetailViewModel, onPlay: (Details, Int, Int) -> Unit) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val watched = Library.entry(d.id)
    val inList = Library.isInList(d.id)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f),
            ) {
                AsyncImage(
                    model = d.poster,
                    contentDescription = d.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                0.35f to Color.Transparent,
                                0.75f to Color.Black.copy(alpha = 0.55f),
                                1f to Background,
                            ),
                        ),
                )
                Text(
                    d.title,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                val meta = listOfNotNull(
                    d.year,
                    d.rating?.let { "★ $it" },
                    d.duration,
                    if (d.isSeries) "${d.seasons.size.coerceAtLeast(1)} Season" else "Film",
                ).joinToString("   ")
                Text(meta, color = TextDim, fontSize = 14.sp)
                if (d.genres.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(d.genres.joinToString(" • "), color = TextDim, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))

                val (playSeason, playEpisode) = when {
                    !d.isSeries -> 0 to 0
                    watched != null && watched.season > 0 -> watched.season to watched.episode
                    else -> d.firstEpisode()
                }
                val playText = when {
                    d.isSeries && watched != null -> "Lanjutkan S$playSeason:E$playEpisode"
                    d.isSeries -> "Putar S$playSeason:E$playEpisode"
                    watched != null -> "Lanjutkan"
                    else -> "Putar"
                }
                PlayButton(playText, onClick = { onPlay(d, playSeason, playEpisode) }, modifier = Modifier.fillMaxWidth())
                if (watched != null) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(watched.progress)
                }
                Spacer(Modifier.height(8.dp))
                GrayButton(
                    text = if (inList) "Ada di Daftar Saya" else "Daftar Saya",
                    icon = if (inList) Icons.Default.Check else Icons.Default.Add,
                    onClick = { Library.toggleList(d.asTitle()) },
                    modifier = Modifier.fillMaxWidth(),
                )

                d.description?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                d.stars?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pemeran: $it",
                        color = TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (d.isSeries && d.seasons.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Surface2),
                    )
                    SectionTitle("Episode", Modifier.padding(top = 12.dp))
                    if (d.seasons.size > 1) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(d.seasons.sortedBy { it.number }, key = { it.number }) { s ->
                                val selected = s.number == vm.selectedSeason
                                Text(
                                    "Season ${s.number}",
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) Color.White else Color.Transparent)
                                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                                        .clickable { vm.selectedSeason = s.number }
                                        .padding(horizontal = 16.dp, vertical = 7.dp),
                                    color = if (selected) Color.Black else Color.White,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            val season = d.seasons.firstOrNull { it.number == vm.selectedSeason } ?: d.seasons.first()
            items(season.episodes.sorted(), key = { "s${season.number}e$it" }) { ep ->
                val isCurrent = watched != null && watched.season == season.number && watched.episode == ep
                EpisodeRow(
                    poster = d.poster,
                    number = ep,
                    isCurrent = isCurrent,
                    progress = if (isCurrent) watched?.progress ?: 0f else 0f,
                    onClick = { onPlay(d, season.number, ep) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(poster: String?, number: Int, isCurrent: Boolean, progress: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(132.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Surface2),
            ) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (isCurrent) ProgressLine(progress)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "Episode $number",
            color = if (isCurrent) Accent else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
