package com.moviebox.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.moviebox.app.data.AddonMeta
import com.moviebox.app.data.Details
import com.moviebox.app.data.Title
import com.moviebox.app.data.Tmdb
import com.moviebox.app.data.TmdbInfo

/** Poster: TMDB (id → en → asli) kalau ada kunci, selain itu poster bawaan. */
@Composable
fun rememberPoster(item: Title): String? {
    val key = Tmdb.apiKey
    val poster by produceState(initialValue = Tmdb.cached(item)?.poster ?: item.poster, item.key, key) {
        if (Tmdb.enabled) Tmdb.info(item)?.poster?.let { value = it }
    }
    return poster
}

@Composable
fun rememberTmdb(item: Title): TmdbInfo? {
    val key = Tmdb.apiKey
    val info by produceState(initialValue = Tmdb.cached(item), item.key, item.title, key) {
        if (Tmdb.enabled) value = Tmdb.info(item)
    }
    return info
}

/** Gabungan data tampilan dari semua sumber. */
data class DisplayInfo(
    val title: String,
    val logo: String?,
    val poster: String?,
    val backdrop: String?,
    val description: String?,
    val genres: List<String>,
    val score: String?,
    val ageRating: String?,
    val year: String?,
    val runtimeMin: Int?,
)

fun mergeInfo(t: Title, tmdb: TmdbInfo?, meta: AddonMeta?, d: Details?): DisplayInfo {
    val durationMin = d?.durationSeconds?.let { (it / 60).toInt() }?.takeIf { it > 0 }
    return DisplayInfo(
        title = tmdb?.title?.takeIf { it.isNotBlank() } ?: t.title.ifBlank { d?.title ?: "" },
        logo = tmdb?.logo,
        poster = tmdb?.poster ?: t.poster ?: d?.poster,
        backdrop = tmdb?.backdrop ?: t.backdrop ?: meta?.background,
        description = tmdb?.overview?.takeIf { it.isNotBlank() }
            ?: meta?.description ?: t.description ?: d?.description,
        genres = (tmdb?.genres.orEmpty().ifEmpty { t.genres }.ifEmpty { meta?.genres.orEmpty() }
            .ifEmpty { d?.genres.orEmpty() }).take(4),
        score = tmdb?.score ?: t.rating ?: meta?.rating ?: d?.rating,
        ageRating = tmdb?.ageRating ?: d?.ageRating,
        year = t.year ?: d?.year,
        runtimeMin = tmdb?.runtimeMin ?: meta?.runtimeMin ?: durationMin,
    )
}

/** "2024 • 13+ • 1j 52m" */
fun metaLine(info: DisplayInfo, isSeries: Boolean, seasons: Int? = null): String = buildList {
    info.year?.let { add(it) }
    info.ageRating?.let { add(it) }
    if (isSeries) {
        add(if (seasons != null && seasons > 0) "$seasons Season" else "Serial")
    } else {
        info.runtimeMin?.let { m -> add(if (m >= 60) "${m / 60}j ${m % 60}m" else "${m}m") } ?: add("Film")
    }
}.joinToString("  •  ")

/** Baris info: ★ skor · [rating usia] · tahun · durasi. */
@androidx.compose.runtime.Composable
fun InfoBadges(info: DisplayInfo, isSeries: Boolean, seasons: Int? = null) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        info.score?.let {
            androidx.compose.material3.Text("★ $it", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        info.ageRating?.let {
            androidx.compose.material3.Text(
                it,
                modifier = androidx.compose.ui.Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 12.sp,
            )
        }
        val rest = metaLine(info.copy(ageRating = null), isSeries, seasons)
        if (rest.isNotBlank()) {
            androidx.compose.material3.Text(rest, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}

/** Teks dalam Bahasa Indonesia; diterjemahkan otomatis kalau bukan. */
@Composable
fun rememberIndonesian(text: String?): String? {
    if (text.isNullOrBlank()) return text
    val result by produceState(
        initialValue = com.moviebox.app.data.Translator.cached(text)
            ?: if (com.moviebox.app.data.Translator.looksIndonesian(text)) text else null,
        text,
    ) {
        value = com.moviebox.app.data.Translator.toIndonesian(text)
    }
    return result
}
