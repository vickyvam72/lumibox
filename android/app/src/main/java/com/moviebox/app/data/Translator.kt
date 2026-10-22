package com.moviebox.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Menerjemahkan deskripsi ke Bahasa Indonesia secara otomatis (dengan cache). */
object Translator {
    private lateinit var prefs: SharedPreferences
    private val memory = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("lumibox_translate", Context.MODE_PRIVATE)
    }

    private val idWords = setOf(
        "yang", "dan", "di", "untuk", "dengan", "ini", "itu", "tidak", "dari", "ke", "dia", "mereka",
        "sebuah", "seorang", "akan", "harus", "saat", "ketika", "setelah", "dalam", "pada", "adalah",
    )
    private val enWords = setOf(
        "the", "and", "of", "to", "in", "is", "a", "his", "her", "their", "with", "for", "when",
        "after", "who", "must", "an", "on", "as", "from", "they", "she", "he",
    )

    /** Tebakan cepat apakah teks sudah berbahasa Indonesia. */
    fun looksIndonesian(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-zà-ÿ]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val id = words.count { it in idWords }
        val en = words.count { it in enWords }
        return id >= en && id > 0
    }

    fun cached(text: String): String? = memory[text]

    suspend fun toIndonesian(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty() || looksIndonesian(clean)) return clean
        memory[clean]?.let { return it }
        val key = clean.hashCode().toString()
        prefs.getString(key, null)?.let {
            memory[clean] = it
            return it
        }
        val translated = withContext(Dispatchers.IO) { runCatching { google(clean) }.getOrNull() } ?: return clean
        memory[clean] = translated
        prefs.edit().putString(key, translated).apply()
        return translated
    }

    private fun google(text: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=id&dt=t&q=" +
            URLEncoder.encode(text.take(4_500), "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONArray(body).optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                parts.optJSONArray(i)?.optString(0)?.let { sb.append(it) }
            }
            return sb.toString().trim().takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }
}
