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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moviebox.app.data.Addon
import com.moviebox.app.data.AddonCatalog
import com.moviebox.app.data.Title
import kotlinx.coroutines.launch

class CategoryViewModel(private val key: String) : ViewModel() {
    var catalog by mutableStateOf<AddonCatalog?>(null)
        private set
    var items by mutableStateOf<List<Title>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val c = catalog ?: (Addon.catalogByKey(key) ?: Addon.catalogs().firstOrNull { it.key == key })
                    ?: throw IllegalStateException("Kategori tidak ditemukan")
                catalog = c
                val next = Addon.catalog(c, skip = items.size)
                val known = items.map { it.key }.toSet()
                val fresh = next.filter { it.key !in known }
                if (fresh.isEmpty()) endReached = true else items = items + fresh
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat kategori"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun CategoryScreen(
    key: String,
    onBack: () -> Unit,
    onOpen: (Title) -> Unit,
    onDetails: (Title) -> Unit,
) {
    val vm: CategoryViewModel = viewModel(key = "cat-$key") { CategoryViewModel(key) }
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
            Text(
                vm.catalog?.name ?: "Kategori",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxSize()) {
            if (vm.items.isEmpty() && vm.loading) {
                CenterLoading()
            } else if (vm.items.isEmpty() && vm.error != null) {
                ErrorBox(vm.error ?: "", onRetry = { vm.loadMore() }, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.items, key = { it.key }) { item ->
                        Poster(item, onClick = { onOpen(item) }, onLongClick = { onDetails(item) }, width = null)
                    }
                    if (!vm.endReached) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(vm.items.size) { vm.loadMore() }
                            SmallLoading()
                        }
                    }
                }
            }
        }
    }
}
