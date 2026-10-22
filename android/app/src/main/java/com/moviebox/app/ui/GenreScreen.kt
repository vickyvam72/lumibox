package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.app.data.Genres
import com.moviebox.app.data.MovieBoxWeb
import com.moviebox.app.data.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** type: "all", "movie", atau "series". */
class GenreViewModel(private val label: String, private val type: String) : ViewModel() {
    var items by mutableStateOf<List<Title>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    var year by mutableStateOf<Int?>(null)
        private set
    private var page = 0
    private var job: kotlinx.coroutines.Job? = null

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
        endReached = false
        error = null
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        val genre = Genres.byLabel(label)?.movieBox ?: label
        loading = true
        error = null
        val y = year
        job = viewModelScope.launch {
            try {
                val next = page + 1
                // TMDB (kalau ada kunci): isi benar-benar sesuai genre. Tanpa kunci: MovieBox,
                // tapi hanya judul yang genrenya memang cocok (buang video musik/acak).
                val tmdb = com.moviebox.app.data.Tmdb.enabled
                suspend fun source(series: Boolean): List<Title> {
                    if (tmdb) {
                        val list = runCatching { com.moviebox.app.data.Tmdb.byGenre(label, series, next, y) }.getOrDefault(emptyList())
                        if (list.isNotEmpty()) {
                            // hanya yang ada di MovieBox; halaman kosong setelah disaring → coba halaman lain sekali
                            val ok = com.moviebox.app.data.Resolver.notMissing(list.filterNot(com.moviebox.app.data.Tmdb::isJunk))
                            viewModelScope.launch {
                                com.moviebox.app.data.Resolver.verify(ok) { gone -> items = items.filterNot { it.key == gone.key } }
                            }
                            return ok
                        }
                        if (page > 0) return list
                    }
                    val tab = if (series) MovieBoxWeb.TAB_SERIES else MovieBoxWeb.TAB_MOVIES
                    return runCatching { MovieBoxWeb.filter(tab, genre, page = next, year = y?.toString() ?: "ALL") }.getOrDefault(emptyList())
                        .filter { t -> t.genres.any { it.equals(label, true) || it.equals(genre, true) } }
                        .filter { y == null || it.year == y.toString() }
                }
                val movies = async { if (type == "series") emptyList() else source(false) }
                val series = async { if (type == "movie") emptyList() else source(true) }
                val m = com.moviebox.app.data.Library.personalize(movies.await())
                val s = com.moviebox.app.data.Library.personalize(series.await())
                val merged = mutableListOf<Title>()
                for (i in 0 until maxOf(m.size, s.size)) {
                    m.getOrNull(i)?.let { merged += it }
                    s.getOrNull(i)?.let { merged += it }
                }
                val known = items.map { it.key }.toSet()
                // satu judul bisa muncul di daftar film & serial atau di dua halaman → buang duplikat
                val fresh = merged.distinctBy { it.key }.filter { it.key !in known }
                if (fresh.isEmpty()) {
                    endReached = true
                    if (items.isEmpty()) error = if (y != null) "Belum ada judul $label tahun $y." else "Belum ada judul untuk genre ini."
                } else {
                    page = next
                    items = items + fresh
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat genre"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun GenreScreen(
    label: String,
    type: String,
    onBack: () -> Unit,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
) {
    val vm: GenreViewModel = viewModel(key = "genre-$label-$type") { GenreViewModel(label, type) }
    val heading = when (type) {
        "movie" -> "Film $label"
        "series" -> "Serial $label"
        else -> label
    }
    BrowseGrid(
        heading = heading,
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
