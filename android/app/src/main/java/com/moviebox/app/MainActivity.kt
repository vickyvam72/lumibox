package com.moviebox.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.moviebox.app.data.Genre
import com.moviebox.app.data.Library
import com.moviebox.app.data.Resolver
import com.moviebox.app.data.Title
import com.moviebox.app.data.TitleStore
import com.moviebox.app.ui.GenreScreen
import com.moviebox.app.ui.HistoryScreen
import com.moviebox.app.ui.DownloadsScreen
import com.moviebox.app.ui.SettingsScreen
import com.moviebox.app.ui.HomeFilter
import com.moviebox.app.ui.Accent
import com.moviebox.app.ui.Background
import com.moviebox.app.ui.DetailScreen
import com.moviebox.app.ui.HomeScreen
import com.moviebox.app.ui.HomeViewModel
import com.moviebox.app.ui.LumiLogo
import com.moviebox.app.ui.LumiTheme
import com.moviebox.app.ui.MyScreen
import com.moviebox.app.ui.SearchScreen
import com.moviebox.app.ui.TextDim

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        com.moviebox.app.data.Crash.markStable(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        // Nonton Bareng: anggota otomatis membuka tontonan yang diputar host.
        com.moviebox.app.data.Together.opener = { c ->
            PlayerActivity.start(
                this,
                Title(id = c.subjectId, title = c.title, isSeries = c.isSeries, year = null, poster = c.poster),
                c.season,
                c.episode,
            )
        }
        // Sudah tampil normal 8 detik → pembukaan ini dianggap berhasil.
        window.decorView.postDelayed({ com.moviebox.app.data.Crash.markStable(this) }, 8_000)
        if (com.moviebox.app.data.Crash.consumeRecovered(this)) {
            android.widget.Toast.makeText(
                this,
                "Lumi Box sempat gagal dibuka, cache sudah dibersihkan otomatis.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        setContent {
            LumiTheme {
                // Laporan crash ditampilkan SEBELUM beranda dimuat, supaya tetap bisa disalin
                // walaupun beranda sendiri yang membuat crash.
                var showReport by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(com.moviebox.app.data.Crash.last(this@MainActivity) != null)
                }
                if (showReport) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                    CrashReportDialog(onDone = { showReport = false })
                } else {
                    LumiApp()
                    com.moviebox.app.ui.TogetherDialog()
                }
            }
        }
    }
}

private enum class Tab(val route: String, val label: String) {
    HOME("home", "Beranda"),
    SEARCH("search", "Cari"),
    MINE("mine", "Lumi Saya"),
}

/** Putar langsung: film dari awal/terakhir, serial dari episode terakhir atau episode pertama. */
fun playTitle(context: Context, item: Title) {
    val subjectId = Resolver.cached(item).orEmpty()
    val watched = if (subjectId.isNotBlank()) Library.entry(subjectId) else null
    val (season, episode) = when {
        !item.isSeries -> 0 to 0
        watched != null && watched.season > 0 -> watched.season to watched.episode
        else -> -1 to -1
    }
    PlayerActivity.start(context, item.copy(id = subjectId), season, episode)
}

@Composable
private fun LumiApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    // Satu HomeViewModel untuk seluruh aplikasi (beranda + rekomendasi di Cari).
    val homeVm: HomeViewModel = viewModel(viewModelStoreOwner = context as ComponentActivity)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    val details: (Title) -> Unit = { item ->
        nav.navigate("details/${Uri.encode(TitleStore.put(item))}")
    }
    // Ketuk judul → halaman informasi dulu; diputar lewat tombol "Putar".
    val open: (Title) -> Unit = details
    val play: (Title) -> Unit = { item -> playTitle(context, item) }
    val genre: (Genre) -> Unit = { g ->
        val type = when (homeVm.filter) {
            HomeFilter.MOVIES -> "movie"
            HomeFilter.SERIES -> "series"
            HomeFilter.ALL -> "all"
        }
        nav.navigate("genre/${Uri.encode(g.label)}/$type")
    }

    Scaffold(
        containerColor = Background,
        bottomBar = { if (showBar) BottomBar(nav, route) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            NavHost(navController = nav, startDestination = Tab.HOME.route) {
                composable(Tab.HOME.route) {
                    HomeScreen(
                        vm = homeVm, onOpen = open, onDetails = details, onPlay = play, onGenre = genre,
                        onRow = { r -> nav.navigate("row/${Uri.encode(r.key)}") },
                    )
                }
                composable(Tab.SEARCH.route) {
                    SearchScreen(suggestions = homeVm.allTitles(), onOpen = open, onDetails = details)
                }
                composable(Tab.MINE.route) {
                    MyScreen(
                        onOpen = open,
                        onPlay = play,
                        onDetails = details,
                        onHistory = { nav.navigate("history") },
                        onDownloads = { nav.navigate("downloads") },
                        onSettings = { nav.navigate("settings") },
                    )
                }
                composable("history") {
                    HistoryScreen(onBack = { nav.popBackStack() }, onPlay = details)
                }
                composable("downloads") {
                    DownloadsScreen(
                        onBack = { nav.popBackStack() },
                        onPlayOffline = { PlayerActivity.startOffline(context, it) },
                    )
                }
                composable("settings") {
                    SettingsScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    route = "details/{key}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType }),
                ) { entry ->
                    DetailScreen(
                        titleKey = Uri.decode(entry.arguments?.getString("key").orEmpty()),
                        onBack = { nav.popBackStack() },
                        onPlay = { t, s, e -> PlayerActivity.start(context, t, s, e) },
                    )
                }
                composable(
                    route = "row/{key}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType }),
                ) { entry ->
                    val key = Uri.decode(entry.arguments?.getString("key").orEmpty())
                    val row = homeVm.allRows().firstOrNull { it.key == key }
                    if (row == null) {
                        androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        com.moviebox.app.ui.CategoryScreen(
                            row = row,
                            onBack = { nav.popBackStack() },
                            onOpen = open,
                            onDetails = details,
                        )
                    }
                }
                composable(
                    route = "genre/{label}/{type}",
                    arguments = listOf(
                        navArgument("label") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType },
                    ),
                ) { entry ->
                    GenreScreen(
                        label = Uri.decode(entry.arguments?.getString("label").orEmpty()),
                        type = entry.arguments?.getString("type") ?: "all",
                        onBack = { nav.popBackStack() },
                        onOpen = open,
                        onDetails = details,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar(containerColor = Color(0xFF0B0B0B), tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    when (tab) {
                        Tab.HOME -> Icon(Icons.Default.Home, contentDescription = null)
                        Tab.SEARCH -> Icon(Icons.Default.Search, contentDescription = null)
                        Tab.MINE -> LumiLogo(24.dp)
                    }
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextDim,
                    unselectedTextColor = TextDim,
                    indicatorColor = Accent.copy(alpha = 0.22f),
                ),
            )
        }
    }
}

/** Kalau aplikasi sempat berhenti mendadak, tampilkan laporannya supaya bisa disalin & dikirim. */
@Composable
private fun CrashReportDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.moviebox.app.data.Crash.last(context)) }
    val text = report ?: run {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            com.moviebox.app.data.Crash.clear(context)
            report = null
        },
        title = { Text("Aplikasi sempat berhenti") },
        text = {
            Column {
                Text("Lumi Box tertutup mendadak sebelumnya. Salin laporan ini dan kirim ke pengembang supaya bisa diperbaiki.")
                Spacer(Modifier.height(8.dp))
                Text(
                    text.lines().take(4).joinToString("\n"),
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Laporan Lumi Box", text))
                android.widget.Toast.makeText(context, "Laporan disalin", android.widget.Toast.LENGTH_SHORT).show()
                com.moviebox.app.data.Crash.clear(context)
                report = null
            }) { Text("Salin laporan") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                com.moviebox.app.data.Crash.clear(context)
                report = null
            }) { Text("Tutup") }
        },
    )
}
