package com.moviebox.app.data

/**
 * Satu judul di daftar. Bisa berasal dari MovieBox (id = subjectId) atau dari addon
 * (id masih kosong, ada imdbId; subjectId dicari otomatis oleh [Resolver]).
 */
data class Title(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val year: String?,
    val poster: String?,
    val imdbId: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val rating: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
) {
    /** Kunci unik untuk daftar/navigasi. */
    val key: String
        get() = id.ifBlank { imdbId ?: title }
}

data class Section(
    val title: String,
    val banner: Boolean,
    val items: List<Title>,
)

data class SeasonInfo(
    val number: Int,
    val episodes: List<Int>,
)

data class Dub(
    val subjectId: String,
    val label: String,
    val language: String?,
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
    val dubs: List<Dub>,
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
    val imdbId: String? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun asTitle() = Title(id, title, isSeries, null, poster, imdbId = imdbId)
}

/** Katalog dari addon Stremio (satu baris di beranda). */
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
) {
    val isSeries: Boolean get() = type == "series"
    val key: String get() = "$type/$id"
}

/** Judul & gambar episode dari meta addon. */
data class EpisodeMeta(
    val season: Int,
    val episode: Int,
    val title: String?,
    val thumbnail: String?,
)

data class AddonMeta(
    val name: String?,
    val description: String?,
    val background: String?,
    val logo: String?,
    val episodes: List<EpisodeMeta>,
) {
    fun episode(season: Int, episode: Int): EpisodeMeta? =
        episodes.firstOrNull { it.season == season && it.episode == episode }
}
