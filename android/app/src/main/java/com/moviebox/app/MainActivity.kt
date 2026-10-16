package com.moviebox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moviebox.app.ui.DetailScreen
import com.moviebox.app.ui.HomeScreen
import com.moviebox.app.ui.MovieBoxTheme
import com.moviebox.app.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MovieBoxTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { id -> nav.navigate("details/$id") },
                onSearch = { nav.navigate("search") },
            )
        }
        composable("search") {
            SearchScreen(
                onOpen = { id -> nav.navigate("details/$id") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "details/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
                subjectId = id,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
