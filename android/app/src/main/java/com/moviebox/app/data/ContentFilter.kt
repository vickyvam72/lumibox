package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Saringan konten (Setting → Konten). Bawaan: drama pendek (DramaBox dkk.) dan film/serial
 * India/Bollywood TIDAK ditampilkan di mana pun (beranda, kategori, rekomendasi, pencarian).
 */
object ContentFilter {
    private lateinit var prefs: SharedPreferences

    var showShortDrama by mutableStateOf(false)
        private set
    var showIndian by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_filter", Context.MODE_PRIVATE)
        showShortDrama = prefs.getBoolean("short_drama", false)
        showIndian = prefs.getBoolean("indian", false)
    }

    fun setShortDrama(on: Boolean) {
        showShortDrama = on
        prefs.edit().putBoolean("short_drama", on).apply()
    }

    fun setIndian(on: Boolean) {
        showIndian = on
        prefs.edit().putBoolean("indian", on).apply()
    }

    private val indianLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "or", "ur", "as")
    private val indianWords = listOf(
        "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "punjabi",
        "bollywood", "tollywood", "kollywood",
    )
    private val shortDramaWords = listOf(
        "sulih suara", "dramabox", "dubbing", "(dub", "[dub", "reelshort", "shortmax", "goodshort",
        "flextv", "netshort", "dramawave", "short drama", "mini drama",
    )
    private val shortDramaGenres = setOf("modern drama", "short drama", "mini drama")

    fun isIndian(t: Title): Boolean {
        if (t.lang != null && t.lang.lowercase() in indianLanguages) return true
        val c = t.country?.lowercase()
        if (c != null && (c == "in" || c.contains("india"))) return true
        val name = t.title.lowercase()
        return indianWords.any { name.contains(it) }
    }

    fun isShortDrama(t: Title): Boolean {
        if (t.shortDrama) return true
        if (t.genres.any { it.lowercase() in shortDramaGenres }) return true
        val name = t.title.lowercase()
        val desc = (t.description ?: "").lowercase()
        return shortDramaWords.any { name.contains(it) || desc.contains(it) }
    }

    /** Boleh ditampilkan sesuai Setting? */
    fun allows(t: Title): Boolean =
        (showShortDrama || !isShortDrama(t)) && (showIndian || !isIndian(t))

    fun apply(list: List<Title>): List<Title> = list.filter(::allows)

    /** Detail MovieBox (negara/genre/jenis) juga dicek. */
    fun allowsDetails(d: Details): Boolean {
        if (!showShortDrama && d.isShortDrama) return false
        if (!showIndian) {
            val c = d.country?.lowercase()
            if (c != null && c.contains("india")) return false
            val lang = d.originalLanguage?.lowercase()
            if (lang != null && indianWords.any { lang.contains(it) }) return false
        }
        return true
    }
}
