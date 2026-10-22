package com.moviebox.app.data

/** Genre: nama Indonesia, nilai filter MovieBox, dan ID genre TMDB. */
data class Genre(val label: String, val movieBox: String)

object Genres {
    /** Daftar genre untuk menu Kategori. */
    val all = listOf(
        Genre("Aksi", "Action"),
        Genre("Petualangan", "Adventure"),
        Genre("Animasi", "Animation"),
        Genre("Anime", "Anime"),
        Genre("Komedi", "Comedy"),
        Genre("Kriminal", "Crime"),
        Genre("Dokumenter", "Documentary"),
        Genre("Drama", "Drama"),
        Genre("Keluarga", "Family"),
        Genre("Fantasi", "Fantasy"),
        Genre("Sejarah", "History"),
        Genre("Horor", "Horror"),
        Genre("Musik", "Music"),
        Genre("Misteri", "Mystery"),
        Genre("Romantis", "Romance"),
        Genre("Fiksi Ilmiah", "Sci-Fi"),
        Genre("Thriller", "Thriller"),
        Genre("Perang", "War"),
        Genre("Western", "Western"),
    )

    fun byLabel(label: String): Genre? = all.firstOrNull { it.label == label }

    /** ID genre TMDB untuk discover (film / serial). "," = semua, "|" = salah satu. */
    fun tmdbFor(label: String, series: Boolean): String? = if (!series) when (label) {
        "Aksi" -> "28"; "Petualangan" -> "12"; "Animasi", "Anime" -> "16"; "Komedi" -> "35"
        "Kriminal" -> "80"; "Dokumenter" -> "99"; "Drama" -> "18"; "Keluarga" -> "10751"
        "Fantasi" -> "14"; "Sejarah" -> "36"; "Horor" -> "27"; "Musik" -> "10402"
        "Misteri" -> "9648"; "Romantis" -> "10749"; "Fiksi Ilmiah" -> "878"; "Thriller" -> "53"
        "Perang" -> "10752"; "Western" -> "37"; else -> null
    } else when (label) {
        "Aksi", "Petualangan" -> "10759"; "Animasi", "Anime" -> "16"; "Komedi" -> "35"
        "Kriminal" -> "80"; "Dokumenter" -> "99"; "Drama" -> "18"; "Keluarga" -> "10751|10762"
        "Fantasi", "Fiksi Ilmiah" -> "10765"; "Misteri", "Horor" -> "9648"; "Thriller" -> "80|9648"
        "Perang", "Sejarah" -> "10768"; "Western" -> "37"; "Romantis" -> "18"; else -> null
    }

    /** ID genre TMDB (dipakai addon Catalog Plus) → nama Indonesia. */
    private val tmdbIds = mapOf(
        "28" to "Aksi", "12" to "Petualangan", "16" to "Animasi", "35" to "Komedi",
        "80" to "Kriminal", "99" to "Dokumenter", "18" to "Drama", "10751" to "Keluarga",
        "14" to "Fantasi", "36" to "Sejarah", "27" to "Horor", "10402" to "Musik",
        "9648" to "Misteri", "10749" to "Romantis", "878" to "Fiksi Ilmiah", "10770" to "Film TV",
        "53" to "Thriller", "10752" to "Perang", "37" to "Western",
        "10759" to "Aksi & Petualangan", "10762" to "Anak", "10763" to "Berita", "10764" to "Reality",
        "10765" to "Fiksi Ilmiah & Fantasi", "10766" to "Sinetron", "10767" to "Talk Show",
        "10768" to "Perang & Politik",
    )

    /** Nama genre Inggris → Indonesia. */
    private val english = mapOf(
        "action" to "Aksi", "adventure" to "Petualangan", "animation" to "Animasi", "anime" to "Anime",
        "comedy" to "Komedi", "crime" to "Kriminal", "documentary" to "Dokumenter", "drama" to "Drama",
        "family" to "Keluarga", "fantasy" to "Fantasi", "history" to "Sejarah", "horror" to "Horor",
        "music" to "Musik", "musical" to "Musikal", "mystery" to "Misteri", "romance" to "Romantis",
        "sci-fi" to "Fiksi Ilmiah", "science fiction" to "Fiksi Ilmiah", "thriller" to "Thriller",
        "war" to "Perang", "western" to "Western", "biography" to "Biografi", "sport" to "Olahraga",
        "kids" to "Anak", "reality" to "Reality", "reality-tv" to "Reality", "talk show" to "Talk Show",
        "soap" to "Sinetron", "short" to "Film Pendek", "film-noir" to "Film Noir", "news" to "Berita",
        "game-show" to "Kuis", "action & adventure" to "Aksi & Petualangan",
        "sci-fi & fantasy" to "Fiksi Ilmiah & Fantasi", "war & politics" to "Perang & Politik",
    )

    /** Rapikan daftar genre dari sumber mana pun jadi nama Indonesia tanpa duplikat. */
    fun normalize(raw: List<String>): List<String> = raw
        .flatMap { it.split(',', '/', '|') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { g -> tmdbIds[g] ?: english[g.lowercase()] ?: g }
        .filter { g -> g.any { it.isLetter() } }
        .distinct()
}
