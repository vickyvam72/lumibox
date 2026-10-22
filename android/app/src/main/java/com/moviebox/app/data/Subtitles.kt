package com.moviebox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Satu baris subtitle. Waktu dalam milidetik. */
data class Cue(val start: Long, val end: Long, val text: String)

/** Pembaca subtitle sendiri (SRT, VTT, ASS/SSA) supaya waktunya bisa digeser (sinkron). */
object Subtitles {

    suspend fun load(url: String): List<Cue> = withContext(Dispatchers.IO) {
        parse(decode(download(url)))
    }

    private fun download(url: String): ByteArray {
        if (url.startsWith("file:") || url.startsWith("/")) {
            val path = if (url.startsWith("file:")) Uri.parse(url).path.orEmpty() else url
            return java.io.File(path).readBytes()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Subtitle HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val broken = utf8.count { it == '�' }
        return if (broken > 3) String(bytes, Charset.forName("windows-1252")) else utf8
    }

    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        return if (text.contains("[Script Info]") || text.contains("\nDialogue:")) parseAss(text) else parseSrtVtt(text)
    }

    private fun parseSrtVtt(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { "-->" in it }
            if (idx < 0) continue
            val parts = lines[idx].split("-->")
            if (parts.size < 2) continue
            val start = time(parts[0].trim()) ?: continue
            val end = time(parts[1].trim().substringBefore(' ')) ?: continue
            val body = clean(lines.drop(idx + 1).joinToString("\n"))
            if (body.isNotBlank() && end > start) cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.start }
    }

    private fun parseAss(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (line in text.lines()) {
            if (!line.startsWith("Dialogue:")) continue
            val fields = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (fields.size < 10) continue
            val start = time(fields[1].trim()) ?: continue
            val end = time(fields[2].trim()) ?: continue
            val body = clean(fields[9])
            if (body.isNotBlank() && end > start) cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.start }
    }

    /** "01:02:03,456" · "01:02:03.456" · "02:03.456" · "0:01:02.34" (ASS) */
    private fun time(s: String): Long? {
        val parts = s.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val secPart = parts.last()
        val sec = secPart.substringBefore('.').toLongOrNull() ?: return null
        val fracRaw = secPart.substringAfter('.', "0")
        val ms = when (fracRaw.length) {
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    private fun clean(s: String): String = s
        .replace("\\N", "\n").replace("\\n", "\n")
        .replace(Regex("\\{[^}]*\\}"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .lines().joinToString("\n") { it.trim() }
        .trim()

    /** Baris yang tampil pada posisi [positionMs] dengan geseran [offsetMs]. */
    fun active(cues: List<Cue>, positionMs: Long, offsetMs: Long): String? {
        if (cues.isEmpty()) return null
        val t = positionMs - offsetMs
        // cari cue terakhir yang mulai <= t
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        val lines = mutableListOf<String>()
        var i = found
        while (i >= 0 && found - i < 6) {
            val c = cues[i]
            if (c.start <= t && t < c.end) lines.add(0, c.text)
            i--
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
