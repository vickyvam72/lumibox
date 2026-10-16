package com.moviebox.app.data

data class Title(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val year: String?,
    val poster: String?,
)

data class Section(
    val title: String,
    val banner: Boolean,
    val items: List<Title>,
)

data class SeasonInfo(
    val number: Int,
    val episodes: List<Int>,
)

data class Details(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val year: String?,
    val description: String?,
    val rating: String?,
    val duration: String?,
    val stars: String?,
    val genres: List<String>,
    val poster: String?,
    val seasons: List<SeasonInfo>,
    /** Subject versi audio asli (bukan dubbing). */
    val originalSubjectId: String?,
    /** Nama bahasa asli, mis. "Korean", "Spanish". */
    val originalLanguage: String?,
    val country: String?,
) {
    fun asTitle() = Title(id, title, isSeries, year, poster)

    /** Episode setelah (season, episode), atau null kalau sudah habis. */
    fun nextEpisode(season: Int, episode: Int): Pair<Int, Int>? {
        val sorted = seasons.sortedBy { it.number }
        val current = sorted.firstOrNull { it.number == season }
        if (current != null) {
            val next = current.episodes.sorted().firstOrNull { it > episode }
            if (next != null) return season to next
        }
        val nextSeason = sorted.firstOrNull { it.number > season && it.episodes.isNotEmpty() }
            ?: return null
        return nextSeason.number to nextSeason.episodes.min()
    }

    fun firstEpisode(): Pair<Int, Int> {
        val s = seasons.sortedBy { it.number }.firstOrNull { it.episodes.isNotEmpty() }
            ?: return 1 to 1
        return s.number to s.episodes.min()
    }
}

data class Stream(
    val label: String,
    val quality: String?,
    val height: Int,
    val sizeBytes: Long?,
    val url: String,
    val headers: List<Pair<String, String>>,
    val resourceId: String?,
)

data class Caption(
    val name: String,
    val url: String,
    val code: String? = null,
)

/** Satu entri "Lanjutkan Menonton". */
data class HistoryEntry(
    val id: String,
    val title: String,
    val poster: String?,
    val isSeries: Boolean,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun asTitle() = Title(id, title, isSeries, null, poster)
}
