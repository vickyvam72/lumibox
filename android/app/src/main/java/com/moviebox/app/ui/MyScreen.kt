package com.moviebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moviebox.app.data.Library
import com.moviebox.app.data.Title

/** Tab "Lumi Saya": Lanjutkan Menonton + Daftar Saya. */
@Composable
fun MyScreen(onOpen: (Title) -> Unit, onPlay: (Title) -> Unit, onDetails: (Title) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LumiLogo(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Lumi Saya",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        item {
            if (Library.history.isEmpty()) {
                EmptyCard("Lanjutkan Menonton", "Film dan serial yang kamu tonton akan muncul di sini.")
            } else {
                ContinueRow(onPlayEntry = onPlay, onDetails = onDetails)
            }
        }

        item {
            val list = Library.myList.toList()
            if (list.isEmpty()) {
                EmptyCard("Daftar Saya", "Ketuk \"+ Daftar Saya\" di sebuah film untuk menyimpannya di sini.")
            } else {
                Column {
                    SectionTitle("Daftar Saya")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { item ->
                            Poster(item, onClick = { onOpen(item) }, onLongClick = { onDetails(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, message: String) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(10.dp))
            .padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.padding(top = 6.dp))
        Text(message, color = TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
