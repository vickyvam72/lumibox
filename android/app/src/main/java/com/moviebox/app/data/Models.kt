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

data class Dub(
    val subjectId: String,
    val label: String,
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
)

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
)
