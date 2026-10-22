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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    suggestions: List<Title>,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitTick by remember { mutableIntStateOf(0) }
    var searchDebug by remember { mutableStateOf<String?>(null) }
    // Hasil yang ternyata (menurut TMDB) film India / drama pendek → disembunyikan sesuai Setting.
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checking by remember { mutableStateOf(false) }
    val filterAll = !com.moviebox.app.data.ContentFilter.showIndian || !com.moviebox.app.data.ContentFilter.showShortDrama
    LaunchedEffect(results, com.moviebox.app.data.ContentFilter.showIndian, com.moviebox.app.data.ContentFilter.showShortDrama) {
        if (com.moviebox.app.data.ContentFilter.showIndian && com.moviebox.app.data.ContentFilter.showShortDrama) return@LaunchedEffect
        val limit = kotlinx.coroutines.sync.Semaphore(6)
        checking = true
        kotlinx.coroutines.coroutineScope {
            results.forEach { t ->
                launch {
                    limit.acquire()
                    try {
                        if (com.moviebox.app.data.ContentFilter.allows(t)) {
                            // Detail MovieBox: genre (mis. "Modern Drama"), negara, jenis
                            if (t.id.isNotBlank()) runCatching { Repo.details(t.id) }
                            if (com.moviebox.app.data.ContentFilter.allows(t) && com.moviebox.app.data.Tmdb.enabled) {
                                com.moviebox.app.data.Tmdb.info(t)
                            }
                        }
                        if (!com.moviebox.app.data.ContentFilter.allows(t)) hidden = hidden + t.key
                        checked = checked + t.key
                    } finally {
                        limit.release()
                    }
                }
            }
        }
        checking = false
    }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query, submitTick) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(450)
        loading = true
        error = null
        results = emptyList()
        // Dua sumber jalan bersamaan; hasil yang datang duluan langsung tampil.
        var newList = emptyList<Title>()
        var oldList = emptyList<Title>()
        var failures = 0
        var lastError: String? = null
        kotlinx.coroutines.coroutineScope {
            launch {
                runCatching { Repo.searchNew(q) }
                    .onSuccess { newList = it; results = Repo.rankResults(q, newList + oldList) }
                    .onFailure { failures++; lastError = it.message }
            }
            launch {
                runCatching { Repo.searchLegacy(q) }
                    .onSuccess { oldList = it; results = Repo.rankResults(q, newList + oldList) }
                    .onFailure { failures++; lastError = it.message }
            }
        }
        loading = false
        searchDebug = if (newList.isEmpty()) Repo.lastSearchDebug else null
        if (results.isEmpty()) error = if (failures >= 2) (lastError ?: "Pencarian gagal") else "Tidak ada hasil untuk \"$q\""
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        // Kolom pencarian abu-abu
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextDim)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Cari film, serial, anime…", color = TextDim, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        submitTick++
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Hapus", tint = TextDim)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                query.trim().length < 2 -> SuggestionList(suggestions, onOpen, onDetails)
                loading && results.isEmpty() -> CenterLoading()
                error != null && results.isEmpty() -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(error ?: "", color = TextDim)
                    searchDebug?.let { SearchDebug(it) }
                }

                else -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Film & Serial")
                        if (loading || checking) {
                            androidx.compose.material3.CircularProgressIndicator(
                                Modifier.size(16.dp),
                                color = Accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            com.moviebox.app.data.ContentFilter.apply(results)
                                // judul tampil setelah dicek (genre/negara), supaya drama pendek & film India tidak sempat muncul
                                .filter { it.key !in hidden && (!filterAll || it.key in checked) }
                                .distinctBy { it.key },
                            key = { it.key },
                        ) { item ->
                            Poster(
                                item = item,
                                onClick = { onOpen(item) },
                                onLongClick = { onDetails(item) },
                                width = null,
                            )
                        }
                        searchDebug?.let { dbg ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SearchDebug(dbg) }
                        }
                    }
                }
            }
        }
    }
}

/** Catatan kecil kalau pencarian web tidak memberi hasil (membantu mencari penyebab). */
@Composable
private fun SearchDebug(text: String) {
    Text(
        "Info pencarian web:\n$text",
        color = TextDim.copy(alpha = 0.7f),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/** Daftar "Rekomendasi" saat kolom pencarian masih kosong (gaya baris + tombol play). */
@Composable
private fun SuggestionList(titles: List<Title>, onOpen: (Title) -> Unit, onDetails: (Title) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("Rekomendasi untukmu") }
        items(com.moviebox.app.data.ContentFilter.apply(titles).distinctBy { it.key }.take(30), key = { it.key }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onDetails(item) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(130.dp)
                        .height(74.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Surface2),
                ) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onOpen(item) }) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Putar", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}
