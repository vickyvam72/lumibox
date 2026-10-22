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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HomeFilter { ALL, SERIES, MOVIES }

/** Satu baris di beranda: katalog addon (rilis terbaru) dengan cadangan TMDB. */
data class HomeRow(
    val key: String,
    val title: String,
    val isSeries: Boolean,
    val local: Boolean,
    val catalog: AddonCatalog? = null,
    val kind: RowKind = RowKind.MAIN,
)

/** MAIN = 4 kategori utama, TOP10 = 10 terpopuler, LATEST = rilis terbaru (urut tanggal). */
enum class RowKind { MAIN, TOP10, LATEST }

const val MIN_LIKES = 3

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

    /** Pakai TMDB saja kalau kuncinya ada (poster, judul, skor konsisten & cepat). */
    private val tmdbMode: Boolean get() = com.moviebox.app.data.Tmdb.enabled

    private var loadedMode: Boolean? = null

    /** Kunci TMDB baru diisi/dihapus → muat ulang beranda dengan sumber yang sesuai. */
    fun checkMode() {
        if (loadedMode != null && loadedMode != tmdbMode) refresh()
    }

    private fun loadCatalogs() {
        loadedMode = tmdbMode
        if (tmdbMode) {
            catalogs = emptyList()
            catalogsLoading = false
            allRows().forEach { r ->
                Library.loadTitles("v2_${r.key}").takeIf { it.isNotEmpty() }?.let { rows[r.key] = it }
                loadRow(r, force = rows.containsKey(r.key))
            }
            return
        }
        catalogsLoading = true
        // Tampilkan salinan tersimpan dulu (instan), lalu perbarui dari jaringan.
        val stored = Addon.storedCatalogs()
        if (stored.isNotEmpty()) {
            catalogs = stored
            catalogsLoading = false
            mainRows().forEach { r -> r.catalog?.let { c -> Addon.storedCatalog(c).takeIf { it.isNotEmpty() }?.let { rows[r.key] = it } } }
        }
        viewModelScope.launch {
            val fresh = runCatching { Addon.catalogs() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty() || catalogs.isEmpty()) catalogs = fresh
            catalogsLoading = false
            mainRows().forEach { loadRow(it, force = rows.containsKey(it.key)) }
        }
    }

    /** Baris tambahan di atas Film Lokal (khusus film, dari TMDB). */
    fun extraRows(): List<HomeRow> = if (!tmdbMode) emptyList() else listOf(
        HomeRow("top-film-lokal", "Top 10 Film Lokal", isSeries = false, local = true, kind = RowKind.TOP10),
        HomeRow("top-film-global", "Top 10 Film Global", isSeries = false, local = false, kind = RowKind.TOP10),
        HomeRow("new-film-lokal", "Film Lokal Rilis Terbaru", isSeries = false, local = true, kind = RowKind.LATEST),
        HomeRow("new-film-global", "Film Global Rilis Terbaru", isSeries = false, local = false, kind = RowKind.LATEST),
    )

    fun allRows(): List<HomeRow> = extraRows() + mainRows()

    /** 4 kategori beranda: Film Lokal, Serial Lokal, Film Global, Serial Global (rilis terbaru). */
    fun mainRows(): List<HomeRow> {
        fun find(series: Boolean, vararg words: String) = if (tmdbMode) null else catalogs
            .filter { it.isSeries == series && words.any { w -> it.name.contains(w, ignoreCase = true) } }
            // utamakan katalog "rilis/terbaru/baru"
            .sortedByDescending { c -> listOf("terbaru", "baru", "rilis", "latest", "new").count { c.name.contains(it, true) } }
            .firstOrNull()
        return listOf(
            HomeRow("main-film-lokal", "Film Lokal", isSeries = false, local = true, catalog = find(false, "lokal", "local", "indonesia")),
            HomeRow("main-serial-lokal", "Serial Lokal", isSeries = true, local = true, catalog = find(true, "lokal", "local", "indonesia")),
            HomeRow("main-film-global", "Film Global", isSeries = false, local = false, catalog = find(false, "global")),
            HomeRow("main-serial-global", "Serial Global", isSeries = true, local = false, catalog = find(true, "global")),
        )
    }

    val failedRows = mutableStateMapOf<String, Boolean>()

    // ---- Rekomendasi untukmu (muncul setelah menyukai beberapa judul) ----
    var recommended by mutableStateOf<List<Title>>(emptyList())
        private set
    private var recoSignature = ""

    /** Judul lokal (Indonesia)? null = belum tahu. */
    private fun isLocal(t: Title): Boolean? {
        t.lang?.let { return it.equals("id", true) }
        t.country?.let { c -> return c.equals("ID", true) || c.contains("indonesia", true) }
        com.moviebox.app.data.Tmdb.cached(t)?.let { info ->
            info.originalLanguage?.let { return it.equals("id", true) }
            info.originCountry?.let { return it.equals("ID", true) }
        }
        return null
    }

    fun loadRecommendations() {
        val liked = Library.likedTitles.toList()
        if (!Library.hasTaste()) {
            recommended = emptyList()
            recoSignature = ""
            return
        }
        val signature = Library.tasteSignature() + "#" + rows.size
        if (signature == recoSignature) return
        recoSignature = signature
        viewModelScope.launch {
            val tmdb = com.moviebox.app.data.Tmdb.enabled
            val recent = liked.take(10)
            // 1) Asal judul yang disukai: lokal / global (TMDB kalau belum diketahui)
            if (tmdb) recent.filter { isLocal(it) == null }.map { async { com.moviebox.app.data.Tmdb.info(it) } }.forEach { it.await() }
            val origins = recent.mapNotNull(::isLocal)
            val localShare = if (origins.isEmpty()) 0.5 else origins.count { it }.toDouble() / origins.size
            val wantLocal = localShare > 0.2
            val wantGlobal = localShare < 0.8
            val wantSeries = recent.count { it.isSeries }
            val wantMovies = recent.size - wantSeries

            // 2) Kandidat: rekomendasi TMDB dari judul yang disukai + genre favorit per asal + baris beranda
            val fav = Library.topGenres().take(3).ifEmpty { recent.flatMap { it.genres }.distinct().take(3) }
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Title>>>()
            if (tmdb) {
                recent.take(5).forEach { t -> jobs += async { com.moviebox.app.data.Tmdb.recommendations(t) } }
                for (g in fav) {
                    for (series in listOf(false, true)) {
                        if (series && wantSeries == 0 && recent.isNotEmpty()) continue
                        if (!series && wantMovies == 0 && recent.isNotEmpty()) continue
                        if (wantLocal) jobs += async { runCatching { com.moviebox.app.data.Tmdb.byGenre(g, series, 1, local = true) }.getOrDefault(emptyList()) }
                        if (wantGlobal) jobs += async { runCatching { com.moviebox.app.data.Tmdb.byGenre(g, series, 1, local = false) }.getOrDefault(emptyList()) }
                    }
                }
            }
            // Baris Lokal pasti judul Indonesia (cache lama belum menyimpan bahasa).
            val fromRows = mainRows().flatMap { r ->
                rows[r.key].orEmpty().map { if (r.local && it.lang == null && it.country == null) it.copy(country = "ID") else it }
            }
            val pool = (jobs.flatMap { it.await() } + fromRows)
            val likedKeys = liked.map { it.key }.toSet()
            val likedNames = liked.map { it.title.lowercase() }.toSet()
            val disliked = Library.dislikedGenres()
            val favSet = fav.toSet()
            val clean = com.moviebox.app.data.Resolver.notMissing(pool)
                .filter(com.moviebox.app.data.ContentFilter::allows)
                .filter { it.key !in likedKeys && it.title.lowercase() !in likedNames && Library.taste(it) == 0 }
                .filter { t -> t.genres.none { it in disliked } || t.genres.any { it in favSet } }
                .distinctBy { it.key }
                .distinctBy { it.title.lowercase() }
            // 3) Hanya yang relevan: genrenya mirip dengan yang disukai
            val relevant = clean.filter { t -> favSet.isEmpty() || t.genres.any { it in favSet } }
            fun byYear(list: List<Title>) = list.sortedWith(
                compareByDescending<Title> { it.year?.toIntOrNull() ?: 0 }
                    .thenByDescending { Library.affinity(it) },
            )
            val local = byYear(relevant.filter { isLocal(it) == true })
            val global = byYear(relevant.filter { isLocal(it) != true })
            // 4) Lokal/global sesuai perbandingan yang disukai, tetap urut tahun terbaru → terlama
            val picked = when {
                !wantGlobal -> local
                !wantLocal -> global
                else -> {
                    val nLocal = (30 * localShare).toInt().coerceIn(5, 25)
                    local.take(nLocal) + global.take(30 - nLocal.coerceAtMost(local.size))
                }
            }
            recommended = byYear(picked).take(30)
        }
    }

    fun loadRow(r: HomeRow, force: Boolean = false) {
        if ((!force && rows.containsKey(r.key)) || !loadingRows.add(r.key)) return
        failedRows.remove(r.key)
        viewModelScope.launch {
            var items = if (r.catalog != null) runCatching { Addon.catalog(r.catalog) }.getOrDefault(emptyList()) else emptyList()
            if (items.isEmpty()) {
                // Cadangan yang tetap relevan: TMDB rilis terbaru (butuh kunci TMDB).
                items = runCatching {
                    when (r.kind) {
                        RowKind.MAIN -> com.moviebox.app.data.Tmdb.latest(r.isSeries, r.local)
                        RowKind.TOP10 -> com.moviebox.app.data.Tmdb.top(r.isSeries, r.local)
                        RowKind.LATEST -> com.moviebox.app.data.Tmdb.newest(r.isSeries, r.local)
                    }
                }.getOrDefault(emptyList())
                // Langsung tampil; yang sudah pasti tidak ada di MovieBox dibuang,
                // sisanya dicek di latar dan dihapus dari baris kalau ternyata tidak ada.
                items = com.moviebox.app.data.Resolver.notMissing(items)
                if (items.isNotEmpty()) {
                    val toCheck = items.take(24)
                    launch {
                        com.moviebox.app.data.Resolver.verify(toCheck) { gone ->
                            rows[r.key]?.let { cur ->
                                val next = cur.filterNot { it.key == gone.key }
                                rows[r.key] = next
                                Library.saveTitles("v2_${r.key}", next)
                            }
                        }
                    }
                }
            }
            if (items.isNotEmpty()) {
                rows[r.key] = items
                if (tmdbMode) Library.saveTitles("v2_${r.key}", items)
            } else if (rows[r.key].isNullOrEmpty()) failedRows[r.key] = true
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
        refreshing = true
        page = 0
        seenIds.clear()
        sections = emptyList()
        endReached = false
        rows.clear()
        loadingRows.clear()
        failedRows.clear()
        if (tmdbMode || catalogs.isEmpty()) loadCatalogs() else mainRows().forEach { loadRow(it, force = true) }
        refreshing = false
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
        val lists = mainRows().filter(::keepRow).map { Library.personalize(rows[it.key].orEmpty()).take(3) }
        val out = mutableListOf<Title>()
        for (i in 0 until 3) lists.forEach { l -> l.getOrNull(i)?.let { out += it } }
        return out.distinctBy { it.key }.take(10)
    }

    fun allTitles(): List<Title> =
        (rows.values.flatten() + sections.flatMap { it.items }).distinctBy { it.key }

    // Harus paling akhir: semua properti (rows, failedRows, …) sudah dibuat sebelum dimuat.
    init {
        loadCatalogs()
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onGenre: (Genre) -> Unit,
    onRow: (HomeRow) -> Unit = {},
) {
    val heroItems = vm.heroItems()
    androidx.compose.runtime.LaunchedEffect(com.moviebox.app.data.Tmdb.enabled) { vm.checkMode() }
    val taste = Library.tasteSignature()
    val loadedRows = vm.rows.size
    androidx.compose.runtime.LaunchedEffect(taste, loadedRows) {
        vm.loadRecommendations()
    }
    val main = vm.mainRows().filter(vm::keepRow)
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
                    // Pager dibuat ulang kalau isinya berubah (mis. ganti chip Serial/Film).
                    androidx.compose.runtime.key(heroItems.joinToString("|") { it.key }) {
                        HeroCarousel(items = heroItems, onPlay = onPlay, onDetails = onDetails)
                    }
                }
            } else if (vm.catalogsLoading || main.any { vm.rows[it.key] == null && vm.failedRows[it.key] != true }) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(420.dp)) { CenterLoading() }
                }
            }

            if (Library.history.isNotEmpty() && vm.filter == HomeFilter.ALL) {
                item(key = "continue") { ContinueRow(onPlayEntry = onPlay, onDetails = onDetails) }
            }

            // Rekomendasi untukmu: di atas Film Lokal, setelah menyukai beberapa judul
            val reco = com.moviebox.app.data.ContentFilter.apply(vm.recommended).filter {
                when (vm.filter) {
                    HomeFilter.ALL -> true
                    HomeFilter.SERIES -> it.isSeries
                    HomeFilter.MOVIES -> !it.isSeries
                }
            }
            if (Library.hasTaste() && reco.isNotEmpty()) {
                item(key = "reco") { TitleRow("Rekomendasi untukmu", reco, onOpen, onDetails) }
            }

            // Hanya 4 kategori: Film Lokal, Serial Lokal, Film Global, Serial Global
            // Top 10 & rilis terbaru (film), lalu 4 kategori utama
            vm.extraRows().filter(vm::keepRow).forEach { r -> rowItem(r, vm, onOpen, onDetails, onRow) }
            main.forEach { r -> rowItem(r, vm, onOpen, onDetails, onRow) }
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
    onRow: (HomeRow) -> Unit,
) {
    item(key = r.key) {
        LaunchedEffect(r.key, vm.catalogsLoading) { if (!vm.catalogsLoading) vm.loadRow(r) }
        val items = vm.rows[r.key]
        when {
            items != null && items.isNotEmpty() -> when (r.kind) {
                // Top 10: urutan popularitas tetap, cukup 10 judul
                RowKind.TOP10 -> TitleRow(
                    r.title,
                    com.moviebox.app.data.ContentFilter.apply(items).filterNot(Library::isDisliked).take(10),
                    onOpen, onDetails, top10 = true,
                )
                // Rilis terbaru: urut tanggal rilis, bisa "Lainnya"
                RowKind.LATEST -> TitleRow(
                    r.title,
                    com.moviebox.app.data.ContentFilter.apply(items).filterNot(Library::isDisliked),
                    onOpen, onDetails, onMore = { onRow(r) },
                )
                RowKind.MAIN -> TitleRow(r.title, Library.personalize(items), onOpen, onDetails, onMore = { onRow(r) })
            }
            vm.failedRows[r.key] == true -> Column {
                SectionTitle(r.title)
                Text(
                    "Gagal memuat. Tarik ke bawah untuk mencoba lagi" +
                        if (!com.moviebox.app.data.Tmdb.enabled) ", atau isi Kunci TMDB di Lumi Saya." else ".",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable { vm.loadRow(r, force = true) },
                )
            }
            else -> RowPlaceholder(r.title)
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
        TogetherButton()
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
            key = { items.getOrNull(it)?.key ?: "p$it" },
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
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
            if (info.logo != null) {
                AsyncImage(
                    model = info.logo,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(80.dp),
                )
            } else {
                Text(
                    info.title,
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
