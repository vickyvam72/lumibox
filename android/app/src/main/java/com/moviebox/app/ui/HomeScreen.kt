@file:OptIn(ExperimentalMaterial3Api::class)

package com.moviebox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.app.data.Repo
import com.moviebox.app.data.Section
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
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

    private var page = 0
    private val seenIds = mutableSetOf<String>()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val next = Repo.home(page + 1)
                // Buang judul yang sudah tampil di halaman sebelumnya.
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
        loadMore()
    }
}

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MovieBox", fontWeight = FontWeight.Bold, color = Accent) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Cari")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                vm.sections.isEmpty() && vm.loading -> CenterLoading()
                vm.sections.isEmpty() && vm.error != null ->
                    ErrorBox(vm.error ?: "", onRetry = { vm.refresh() })

                else -> HomeList(vm, onOpen)
            }
        }
    }
}

@Composable
private fun HomeList(vm: HomeViewModel, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(vm.sections, key = { "${it.title}-${it.items.firstOrNull()?.id}" }) { section ->
            Column {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(section.items, key = { it.id }) { item ->
                        if (section.banner) {
                            BannerCard(item, onClick = { onOpen(item.id) })
                        } else {
                            PosterCard(item, onClick = { onOpen(item.id) })
                        }
                    }
                }
            }
        }

        // Baris terakhir: memicu muat halaman berikutnya (infinite scroll).
        item(key = "footer") {
            when {
                vm.error != null -> ErrorBox(vm.error ?: "", onRetry = { vm.loadMore() })
                !vm.endReached -> {
                    LaunchedEffect(vm.sections.size) { vm.loadMore() }
                    SmallLoading()
                }
            }
        }
    }
}
