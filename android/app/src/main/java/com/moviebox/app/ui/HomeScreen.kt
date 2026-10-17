@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.moviebox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.moviebox.app.data.Genre
import com.moviebox.app.data.Genres
import com.moviebox.app.data.Library
import com.moviebox.app.data.MovieBoxWeb
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Section
import com.moviebox.app.data.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HomeFilter { ALL, SERIES, MOVIES }

/** Satu baris di beranda: dari katalog addon atau dari filter MovieBox. */
data class HomeRow(
    val key: String,
    val title: String,
    val isSeries: Boolean,
    val catalog: AddonCatalog? = null,
    val webTab: Int = 0,
    val webGenre: String = "ALL",
    val webCountry: String = "ALL",
)

class HomeViewModel : ViewModel() {
    var catalogs by mutableStateOf<List<AddonCatalog>>(emptyList())
        private set
    var catalogsLoading by mutableStateOf(true)
        private set
    val rows = mutableStateMapOf<String, List<Title>>()
    private val loadingRows = mutableSetOf<String>()

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
            mainRows().forEach { loadRow(it) }
        }
    }

    /** 4 kategori utama: Film Lokal, Serial Lokal, Film Global, Serial Global. */
    fun mainRows(): List<HomeRow> {
        fun find(series: Boolean, word: String) =
            catalogs.firstOrNull { it.isSeries == series && it.name.contains(word, ignoreCase = true) }
        val lokalFilm = find(false, "lokal") ?: find(false, "local")
        val lokalSerial = find(true, "lokal") ?: find(true, "local")
        val globalFilm = find(false, "global")
        val globalSerial = find(true, "global")
        return listOf(
            lokalFilm?.let { HomeRow("main-film-lokal", "Film Lokal", false, catalog = it) }
                ?: HomeRow("main-film-lokal", "Film Lokal", false, webTab = MovieBoxWeb.TAB_MOVIES, webCountry = "Indonesia"),
            lokalSerial?.let { HomeRow("main-serial-lokal", "Serial Lokal", true, catalog = it) }
                ?: HomeRow("main-serial-lokal", "Serial Lokal", true, webTab = MovieBoxWeb.TAB_SERIES, webCountry = "Indonesia"),
            globalFilm?.let { HomeRow("main-film-global", "Film Global", false, catalog = it) }
                ?: HomeRow("main-film-global", "Film Global", false, webTab = MovieBoxWeb.TAB_MOVIES),
            globalSerial?.let { HomeRow("main-serial-global", "Serial Global", true, catalog = it) }
                ?: HomeRow("main-serial-global", "Serial Global", true, webTab = MovieBoxWeb.TAB_SERIES),
        )
    }

    /** Baris tambahan: platform populer dari addon + beberapa genre favorit. */
    fun extraRows(): List<HomeRow> {
        val used = mainRows().mapNotNull { it.catalog?.key }.toSet()
        val fromAddon = catalogs.filter { it.key !in used }.map {
            HomeRow("cat-${it.key}", prettyName(it.name), it.isSeries, catalog = it)
        }
        val genreRows = listOf(
            HomeRow("g-horor-lokal", "Horor Indonesia", false, webTab = MovieBoxWeb.TAB_MOVIES, webGenre = "Horror", webCountry = "Indonesia"),
            HomeRow("g-komedi", "Komedi Paling Seru", false, webTab = MovieBoxWeb.TAB_MOVIES, webGenre = "Comedy"),
            HomeRow("g-anime", "Anime", true, webTab = MovieBoxWeb.TAB_SERIES, webGenre = "Anime"),
            HomeRow("g-romantis", "Kisah Romantis", false, webTab = MovieBoxWeb.TAB_MOVIES, webGenre = "Romance"),
        )
        // Selang-seling supaya beranda tidak monoton.
        val out = mutableListOf<HomeRow>()
        val a = fromAddon.iterator()
        val g = genreRows.iterator()
        while (a.hasNext() || g.hasNext()) {
            if (a.hasNext()) out += a.next()
            if (a.hasNext()) out += a.next()
            if (g.hasNext()) out += g.next()
        }
        return out
    }

    private fun prettyName(name: String): String {
        val parts = name.split("·").map { it.trim() }
        return if (parts.size == 3 && parts[2].equals("Populer", true)) {
            "${parts[1]} Populer di ${parts[0]}"
        } else {
            name
        }
    }

    fun loadRow(r: HomeRow) {
        if (rows.containsKey(r.key) || !loadingRows.add(r.key)) return
        viewModelScope.launch {
            val items = runCatching {
                if (r.catalog != null) {
                    Addon.catalog(r.catalog)
                } else {
                    MovieBoxWeb.filter(r.webTab, genre = r.webGenre, country = r.webCountry)
                }
            }.getOrDefault(emptyList())
            rows[r.key] = items
            loadingRows.remove(r.key)
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
        if (catalogs.isEmpty()) loadCatalogs() else mainRows().forEach { loadRow(it) }
        loadMore()
    }

    fun keepRow(r: HomeRow) = when (filter) {
        HomeFilter.ALL -> true
        HomeFilter.SERIES -> r.isSeries
        HomeFilter.MOVIES -> !r.isSeries
    }

    private fun keep(t: Title) = when (filter) {
        HomeFilter.ALL -> true
        HomeFilter.SERIES -> t.isSeries
        HomeFilter.MOVIES -> !t.isSeries
    }

    fun visibleSections(): List<Section> = sections.mapNotNull { s ->
        val items = s.items.filter(::keep)
        if (items.isEmpty()) null else s.copy(items = items)
    }

    /** Isi carousel: selang-seling dari 4 kategori utama. */
    fun heroItems(): List<Title> {
        val lists = mainRows().filter(::keepRow).map { rows[it.key].orEmpty().take(3) }
        val out = mutableListOf<Title>()
        for (i in 0 until 3) lists.forEach { l -> l.getOrNull(i)?.let { out += it } }
        if (out.isEmpty()) out += visibleSections().flatMap { it.items }.take(8)
        return out.distinctBy { it.key }.take(10)
    }

    fun allTitles(): List<Title> =
        (rows.values.flatten() + sections.flatMap { it.items }).distinctBy { it.key }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onGenre: (Genre) -> Unit,
) {
    val heroItems = vm.heroItems()
    val main = vm.mainRows().filter(vm::keepRow)
    val extra = vm.extraRows().filter(vm::keepRow)
    val sections = vm.visibleSections()
    var showGenres by remember { mutableStateOf(false) }

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
                        onChange = { vm.filter = it },
                        onCategories = { showGenres = true },
                    )
                }
            }

            if (heroItems.isNotEmpty()) {
                item(key = "hero") {
                    HeroCarousel(items = heroItems, onPlay = onPlay, onDetails = onDetails)
                }
            } else if (vm.catalogsLoading || vm.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(420.dp)) { CenterLoading() }
                }
            } else if (vm.error != null && sections.isEmpty()) {
                item(key = "error") { ErrorBox(vm.error ?: "", onRetry = { vm.refresh() }) }
            }

            if (Library.history.isNotEmpty() && vm.filter == HomeFilter.ALL) {
                item(key = "continue") { ContinueRow(onPlayEntry = onPlay, onDetails = onDetails) }
            }

            // 4 kategori utama
            main.forEach { r -> rowItem(r, vm, onOpen, onDetails, top10 = false) }

            // Top 10 dari MovieBox
            sections.firstOrNull { !it.banner }?.let { top ->
                item(key = "top10") {
                    TitleRow("Top 10 Hari Ini", top.items.take(10), onOpen, onDetails, top10 = true)
                }
            }

            // Baris tambahan (platform populer + genre)
            extra.forEach { r -> rowItem(r, vm, onOpen, onDetails, top10 = false) }

            // Sisa beranda MovieBox
            sections.drop(1).forEachIndexed { index, section ->
                item(key = "s-$index-${section.items.first().key}") {
                    TitleRow(section.title, section.items, onOpen, onDetails)
                }
            }

            item(key = "footer") {
                if (!vm.endReached && vm.error == null) {
                    LaunchedEffect(vm.sections.size) { vm.loadMore() }
                    SmallLoading()
                }
            }
        }
    }

    if (showGenres) {
        GenreDialog(
            onPick = {
                showGenres = false
                onGenre(it)
            },
            onDismiss = { showGenres = false },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rowItem(
    r: HomeRow,
    vm: HomeViewModel,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    top10: Boolean,
) {
    item(key = r.key) {
        LaunchedEffect(r.key) { vm.loadRow(r) }
        val items = vm.rows[r.key]
        when {
            items == null -> RowPlaceholder(r.title)
            items.isNotEmpty() -> TitleRow(r.title, items, onOpen, onDetails, top10 = top10)
        }
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

@Composable
private fun Pill(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent.copy(alpha = 0.28f) else Color.Transparent)
            .border(1.dp, if (selected) Accent else Color.White.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Menu "Kategori" berisi genre. */
@Composable
private fun GenreDialog(onPick: (Genre) -> Unit, onDismiss: () -> Unit) {
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
                items(Genres.all, key = { it.label }) { g ->
                    Text(
                        g.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(g) }
                            .padding(vertical = 13.dp),
                        color = TextDim,
                        fontSize = 19.sp,
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

/** Poster utama yang berganti tiap 2 detik dan bisa digeser. */
@Composable
private fun HeroCarousel(items: List<Title>, onPlay: (Title) -> Unit, onDetails: (Title) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(items.size, dragged) {
        if (!dragged && items.size > 1) {
            while (true) {
                delay(2_000)
                pager.animateScrollToPage((pager.currentPage + 1) % items.size)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            key = { items[it].key },
        ) { page ->
            val item = items[page]
            HeroCard(item = item, onPlay = { onPlay(item) }, onDetails = { onDetails(item) })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Accent else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(item: Title, onPlay: () -> Unit, onDetails: () -> Unit) {
    val inList = Library.isInList(item.key)
    val info = mergeInfo(item, rememberTmdb(item), null, null)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onDetails),
    ) {
        AsyncImage(
            model = info.poster ?: info.backdrop,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))),
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
            if (info.genres.isNotEmpty()) {
                Text(info.genres.take(3).joinToString("  •  "), color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
            InfoBadges(info, item.isSeries)
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
