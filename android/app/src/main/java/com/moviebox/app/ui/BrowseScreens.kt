package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.app.data.Addon
import com.moviebox.app.data.Library
import com.moviebox.app.data.Title
import com.moviebox.app.data.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pilihan tahun: Semua, tahun ini, … sampai 1990. */
val filterYears: List<Int> = (java.time.LocalDate.now().year downTo 1990).toList()

/** Baris chip tahun untuk menyaring daftar. */
@Composable
fun YearFilter(selected: Int?, onPick: (Int?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") { YearChip("Semua", selected == null) { onPick(null) } }
        items(filterYears, key = { it }) { y -> YearChip(y.toString(), selected == y) { onPick(y) } }
    }
}

@Composable
private fun YearChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Accent.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (selected) Accent else Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Header "Judul baris" + "Lainnya ›". */
@Composable
fun RowHeader(title: String, onMore: (() -> Unit)?) {
    Row(
        Modifier.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Text(
                "Lainnya ›",
                color = AccentLight,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onMore)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Isi lengkap satu baris beranda (Film Lokal, dst.) dengan filter tahun. */
class CategoryViewModel(private val row: HomeRow) : ViewModel() {
    var items by mutableStateOf<List<Title>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var year by mutableStateOf<Int?>(null)
        private set

    private var page = 0
    private var addonSkip = 0
    private var useTmdb = false
    private var job: Job? = null

    init {
        loadMore()
    }

    fun pickYear(y: Int?) {
        if (y == year) return
        year = y
        job?.cancel()
        loading = false
        items = emptyList()
        page = 0
        addonSkip = 0
        useTmdb = false
        endReached = false
        error = null
        loadMore()
    }

    private suspend fun addonPage(): List<Title>? {
        val c = row.catalog ?: return null
        val list = runCatching { Addon.catalog(c, addonSkip) }.getOrNull() ?: return null
        addonSkip += list.size
        return list
    }

    private var viaTmdb = false

    /**
     * Ambil halaman TMDB berikutnya, sisakan hanya yang ada di MovieBox.
     * Kalau satu halaman habis tersaring, lanjut halaman berikutnya (maks. 4).
     */
    private suspend fun tmdbPages(y: Int?): List<Title> {
        viaTmdb = true
        repeat(4) {
            page += 1
            val raw = if (row.kind == RowKind.LATEST && y == null && !row.isSeries) {
                Tmdb.newestPage(row.local, page)
            } else {
                Tmdb.discover(row.isSeries, row.local, y, page)
            }
            if (raw.isEmpty()) {
                endReached = true
                return emptyList()
            }
            val ok = com.moviebox.app.data.Resolver.notMissing(raw)
            if (ok.isNotEmpty()) {
                viewModelScope.launch {
                    com.moviebox.app.data.Resolver.verify(ok) { gone -> items = items.filterNot { it.key == gone.key } }
                }
                return ok
            }
        }
        return emptyList()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        val y = year
        job = viewModelScope.launch {
            try {
                viaTmdb = false
                val fresh = mutableListOf<Title>()
                val known = items.map { it.key }.toMutableSet()
                if (y != null && Tmdb.enabled) {
                    // Per tahun: TMDB lebih lengkap
                    fresh += tmdbPages(y)
                } else if (!useTmdb) {
                    // Katalog addon (sama seperti beranda), disaring per tahun kalau dipilih
                    var tries = 0
                    while (fresh.isEmpty() && tries < 15) {
                        tries++
                        val list = addonPage()
                        if (list.isNullOrEmpty()) {
                            if (items.isEmpty() && fresh.isEmpty() && Tmdb.enabled) useTmdb = true else endReached = true
                            break
                        }
                        fresh += list.filter { y == null || it.year == y.toString() }
                    }
                    if (fresh.isEmpty() && !useTmdb) endReached = true
                    if (useTmdb) {
                        fresh += tmdbPages(y)
                    }
                } else {
                    fresh += tmdbPages(y)
                }
                val add = fresh.filter { known.add(it.key) && !Library.isDisliked(it) && com.moviebox.app.data.ContentFilter.allows(it) }
                items = items + add
                if (add.isEmpty()) endReached = true
                if (items.isEmpty() && endReached) {
                    error = if (y != null) "Belum ada judul tahun $y." else "Belum ada judul."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun CategoryScreen(
    row: HomeRow,
    onBack: () -> Unit,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
) {
    val vm: CategoryViewModel = viewModel(key = "cat-${row.key}") { CategoryViewModel(row) }
    BrowseGrid(
        heading = row.title,
        year = vm.year,
        onYear = vm::pickYear,
        items = vm.items,
        loading = vm.loading,
        error = vm.error,
        endReached = vm.endReached,
        onLoadMore = vm::loadMore,
        onBack = onBack,
        onOpen = onOpen,
        onDetails = onDetails,
    )
}

/** Kisi poster 3 kolom + filter tahun (dipakai Kategori/genre dan "Lainnya"). */
@Composable
fun BrowseGrid(
    heading: String,
    year: Int?,
    onYear: (Int?) -> Unit,
    items: List<Title>,
    loading: Boolean,
    error: String?,
    endReached: Boolean,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }
            Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        }
        YearFilter(year, onYear)
        Box(Modifier.fillMaxSize()) {
            if (items.isEmpty() && loading) {
                CenterLoading()
            } else if (items.isEmpty() && error != null) {
                ErrorBox(error, onRetry = onLoadMore, modifier = Modifier.align(Alignment.Center))
            } else {
                val list = items.distinctBy { it.key }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.key }) { item ->
                        Poster(item, onClick = { onOpen(item) }, onLongClick = { onDetails(item) }, width = null)
                    }
                    if (!endReached) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(list.size, year) { onLoadMore() }
                            SmallLoading()
                        }
                    }
                }
            }
        }
    }
}
