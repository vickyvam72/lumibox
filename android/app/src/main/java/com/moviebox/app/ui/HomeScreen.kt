@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.moviebox.app.data.Addon
import com.moviebox.app.data.AddonCatalog
import com.moviebox.app.data.Library
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Section
import com.moviebox.app.data.Title
import kotlinx.coroutines.launch

enum class HomeFilter { ALL, SERIES, MOVIES }

class HomeViewModel : ViewModel() {
    // ---- katalog addon (kategori) ----
    var catalogs by mutableStateOf<List<AddonCatalog>>(emptyList())
        private set
    var catalogsLoading by mutableStateOf(true)
        private set
    val rows = mutableStateMapOf<String, List<Title>>()
    private val loadingRows = mutableSetOf<String>()

    // ---- beranda MovieBox ----
    var sections by mutableStateOf<List<Section>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    var filter by mutableStateOf(HomeFilter.ALL)

    private var page = 0
    private val seenIds = mutableSetOf<String>()

    init {
        loadCatalogs()
        loadMore()
    }

    private fun loadCatalogs() {
        catalogsLoading = true
        viewModelScope.launch {
            catalogs = runCatching { Addon.catalogs() }.getOrDefault(emptyList())
            catalogsLoading = false
            catalogs.take(3).forEach { loadRow(it) }
        }
    }

    fun loadRow(c: AddonCatalog) {
        if (rows.containsKey(c.key) || !loadingRows.add(c.key)) return
        viewModelScope.launch {
            val items = runCatching { Addon.catalog(c) }.getOrDefault(emptyList())
            rows[c.key] = items
            loadingRows.remove(c.key)
        }
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val next = Repo.home(page + 1)
                val fresh = next.mapNotNull { s ->
                    val items = s.items.filter { seenIds.add(it.id) }
                    if (items.isEmpty()) null else s.copy(items = items)
                }
                if (fresh.isEmpty()) {
                    endReached = true
                } else {
                    page += 1
                    sections = sections + fresh
                }
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat beranda"
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun refresh() {
        if (loading) return
        refreshing = true
        page = 0
        seenIds.clear()
        sections = emptyList()
        endReached = false
        rows.clear()
        loadingRows.clear()
        catalogs.take(3).forEach { loadRow(it) }
        if (catalogs.isEmpty()) loadCatalogs()
        loadMore()
    }

    private fun keep(t: Title) = when (filter) {
        HomeFilter.ALL -> true
        HomeFilter.SERIES -> t.isSeries
        HomeFilter.MOVIES -> !t.isSeries
    }

    fun visibleCatalogs(): List<AddonCatalog> = catalogs.filter {
        when (filter) {
            HomeFilter.ALL -> true
            HomeFilter.SERIES -> it.isSeries
            HomeFilter.MOVIES -> !it.isSeries
        }
    }

    fun visibleSections(): List<Section> = sections.mapNotNull { s ->
        val items = s.items.filter(::keep)
        if (items.isEmpty()) null else s.copy(items = items)
    }

    fun hero(): Title? {
        visibleCatalogs().forEach { c ->
            rows[c.key]?.firstOrNull { it.backdrop != null || it.poster != null }?.let { return it }
        }
        val visible = visibleSections()
        return visible.firstOrNull { it.banner }?.items?.firstOrNull()
            ?: visible.firstOrNull()?.items?.firstOrNull()
    }

    /** Semua judul unik (untuk rekomendasi di halaman Cari). */
    fun allTitles(): List<Title> =
        (catalogs.flatMap { rows[it.key].orEmpty() } + sections.flatMap { it.items }).distinctBy { it.key }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onCategory: (AddonCatalog) -> Unit,
) {
    val hero = vm.hero()
    val catalogs = vm.visibleCatalogs()
    val sections = vm.visibleSections()
    var showCategories by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "top") {
                Column(Modifier.statusBarsPadding()) {
                    TopBar(onRefresh = { vm.refresh() })
                    FilterChips(
                        current = vm.filter,
                        hasCategories = vm.catalogs.isNotEmpty(),
                        onChange = { vm.filter = it },
                        onCategories = { showCategories = true },
                    )
                }
            }

            val nothingYet = sections.isEmpty() && catalogs.none { vm.rows[it.key]?.isNotEmpty() == true }
            if (nothingYet && (vm.loading || vm.catalogsLoading)) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(420.dp)) { CenterLoading() }
                }
            } else if (nothingYet && vm.error != null) {
                item(key = "error") {
                    ErrorBox(vm.error ?: "", onRetry = { vm.refresh() })
                }
            }

            if (hero != null) {
                item(key = "hero-${hero.key}") {
                    HeroCard(item = hero, onPlay = { onPlay(hero) }, onDetails = { onDetails(hero) })
                }
            }

            if (Library.history.isNotEmpty() && vm.filter == HomeFilter.ALL) {
                item(key = "continue") {
                    ContinueRow(onPlayEntry = onPlay, onDetails = onDetails)
                }
            }

            // Kategori dari addon EasyRatings
            catalogs.forEachIndexed { index, c ->
                item(key = "cat-${c.key}") {
                    LaunchedEffect(c.key) { vm.loadRow(c) }
                    val items = vm.rows[c.key]
                    when {
                        items == null -> RowPlaceholder(c.name)
                        items.isNotEmpty() -> TitleRow(
                            title = c.name,
                            items = items,
                            onOpen = onOpen,
                            onDetails = onDetails,
                            top10 = index == 0,
                        )
                    }
                }
            }

            // Baris dari beranda MovieBox (termasuk film Indonesia)
            sections.forEachIndexed { index, section ->
                item(key = "s-$index-${section.items.first().key}") {
                    TitleRow(
                        title = section.title,
                        items = section.items,
                        onOpen = onOpen,
                        onDetails = onDetails,
                        top10 = catalogs.isEmpty() && index == sections.indexOfFirst { !it.banner },
                    )
                }
            }

            item(key = "footer") {
                when {
                    vm.error != null && sections.isNotEmpty() -> ErrorBox(vm.error ?: "", onRetry = { vm.loadMore() })
                    !vm.endReached -> {
                        LaunchedEffect(vm.sections.size) { vm.loadMore() }
                        SmallLoading()
                    }
                }
            }
        }
    }

    if (showCategories) {
        CategoryDialog(
            catalogs = vm.catalogs,
            onPick = {
                showCategories = false
                onCategory(it)
            },
            onDismiss = { showCategories = false },
        )
    }
}

@Composable
private fun RowPlaceholder(title: String) {
    Column {
        SectionTitle(title)
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) {
                Box(
                    Modifier
                        .width(112.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surface1),
                )
            }
        }
    }
}

@Composable
private fun TopBar(onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LumiLogo(36.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Beranda",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Muat ulang", tint = Color.White)
        }
    }
}

@Composable
private fun FilterChips(
    current: HomeFilter,
    hasCategories: Boolean,
    onChange: (HomeFilter) -> Unit,
    onCategories: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (current != HomeFilter.ALL) {
            item {
                Pill(selected = false, onClick = { onChange(HomeFilter.ALL) }) {
                    Icon(Icons.Default.Close, contentDescription = "Semua", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            Pill(selected = current == HomeFilter.SERIES, onClick = {
                onChange(if (current == HomeFilter.SERIES) HomeFilter.ALL else HomeFilter.SERIES)
            }) { Text("Serial", color = Color.White, fontSize = 15.sp) }
        }
        item {
            Pill(selected = current == HomeFilter.MOVIES, onClick = {
                onChange(if (current == HomeFilter.MOVIES) HomeFilter.ALL else HomeFilter.MOVIES)
            }) { Text("Film", color = Color.White, fontSize = 15.sp) }
        }
        if (hasCategories) {
            item {
                Pill(selected = false, onClick = onCategories) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kategori", color = Color.White, fontSize = 15.sp)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Daftar kategori layar penuh (seperti menu "Kategori" di aplikasi streaming). */
@Composable
private fun CategoryDialog(catalogs: List<AddonCatalog>, onPick: (AddonCatalog) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 80.dp, bottom = 140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(catalogs, key = { it.key }) { c ->
                    Text(
                        c.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(vertical = 14.dp),
                        color = TextDim,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.Black, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun HeroCard(item: Title, onPlay: () -> Unit, onDetails: () -> Unit) {
    val inList = Library.isInList(item.key)
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onDetails),
    ) {
        AsyncImage(
            model = item.poster ?: item.backdrop,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (item.logo != null) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(80.dp),
                )
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            val meta = buildList {
                add(if (item.isSeries) "Serial" else "Film")
                addAll(item.genres.take(3))
                item.rating?.let { add("★ $it") }
            }.joinToString("  •  ")
            Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayButton("Putar", onClick = onPlay, modifier = Modifier.weight(1f))
                GrayButton(
                    text = "Daftar Saya",
                    icon = if (inList) Icons.Default.Check else Icons.Default.Add,
                    onClick = { Library.toggleList(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun ContinueRow(onPlayEntry: (Title) -> Unit, onDetails: (Title) -> Unit) {
    Column {
        SectionTitle("Lanjutkan Menonton")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Library.history.toList(), key = { it.id }) { entry ->
                ContinueCard(
                    entry = entry,
                    onPlay = { onPlayEntry(entry.asTitle()) },
                    onInfo = { onDetails(entry.asTitle()) },
                    onRemove = { Library.removeHistory(entry.id) },
                )
            }
        }
    }
}
