package com.moviebox.app.data

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Yang sedang diputar (dikirim host ke anggota). */
data class TogetherContent(
    val subjectId: String,
    val title: String,
    val poster: String?,
    val isSeries: Boolean,
    val season: Int,
    val episode: Int,
) {
    val key: String get() = "$subjectId|$season|$episode"
}

/** Dipasang oleh PlayerActivity selama pemutar terbuka. */
interface TogetherPlayer {
    val exo: ExoPlayer
    val loading: Boolean
    fun content(): TogetherContent?
    fun switchTo(c: TogetherContent)
}

/**
 * Nonton Bareng (Watch Together), cara kerjanya sama seperti di LumiTube:
 * setiap HP memutar videonya sendiri; yang dikirim antar-HP hanya perintah kecil
 * (judul/episode apa, posisi, putar/jeda) lewat ntfy.sh, terenkripsi AES-GCM dengan kunci dari kode room.
 * Host memilih tontonan; anggota mengikuti. Host bisa mengizinkan anggota ikut mengatur.
 */
object Together {
    private const val SERVER = "https://ntfy.sh"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    var roomCode by mutableStateOf<String?>(null)
        private set
    var isHost by mutableStateOf(false)
        private set
    var members by mutableIntStateOf(1)
        private set
    var connected by mutableStateOf(false)
        private set
    var guestControl by mutableStateOf(false)
        private set
    var showDialog by mutableStateOf(false)

    /** Judul yang sedang diputar host (untuk ditampilkan ke anggota). */
    var hostTitle by mutableStateOf<String?>(null)
        private set

    /** Pemutar yang sedang terbuka. */
    var player: TogetherPlayer? = null

    /** Membuka pemutar baru (dipasang MainActivity). */
    var opener: ((TogetherContent) -> Unit)? = null

    val isGuest: Boolean get() = roomCode != null && !isHost

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listenJob: Job? = null
    private var loopJob: Job? = null
    private var appContext: Context? = null
    @Volatile private var listenConn: HttpURLConnection? = null
    private val myId = randomCode(10)
    private var topic = ""
    private var key: SecretKeySpec? = null
    private val guests = LinkedHashMap<String, String>()

    fun updateGuestControl(on: Boolean) {
        if (!isHost) return
        guestControl = on
        publishState(force = true)
    }

    fun create(context: Context) {
        leave()
        start(context, randomCode(6), host = true)
    }

    fun join(context: Context, code: String): Boolean {
        val c = code.trim().uppercase().filter { it in ALPHABET }
        if (c.length != 6) return false
        leave()
        start(context, c, host = false)
        return true
    }

    fun leave(notify: Boolean = true) {
        roomCode ?: return
        val wasHost = isHost
        val t = topic
        val k = key
        if (notify) scope.launch { runCatching { publishTo(t, k, JSONObject().put("t", if (wasHost) "end" else "bye")) } }
        listenJob?.cancel(); loopJob?.cancel()
        listenJob = null; loopJob = null
        runCatching { listenConn?.disconnect() }
        listenConn = null
        roomCode = null; isHost = false; connected = false; members = 1
        guests.clear(); pending = null; sentKey = null
        hostState = null; guestControl = false; hostTitle = null
    }

    private fun start(context: Context, code: String, host: Boolean) {
        appContext = context.applicationContext
        roomCode = code
        isHost = host
        members = 1
        topic = "lumibox_" + sha256("topic:$code").toHex().take(24)
        key = SecretKeySpec(sha256("lumibox-together:$code"), "AES")
        listenJob = scope.launch { listenLoop(topic) }
        loopJob = scope.launch { if (host) hostLoop() else guestLoop() }
        if (!host) {
            scope.launch {
                delay(1500) // tunggu langganan tersambung
                send(JSONObject().put("t", "hello").put("name", android.os.Build.MODEL ?: "Tamu"))
            }
        }
    }

    private fun toast(text: String) {
        appContext?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ jaringan

    private suspend fun listenLoop(t: String) {
        var since: String? = null
        while (scope.isActive && topic == t && roomCode != null) {
            try {
                withContext(Dispatchers.IO) {
                    val url = "$SERVER/$t/json" + (since?.let { "?since=$it" } ?: "")
                    val conn = URL(url).openConnection() as HttpURLConnection
                    listenConn = conn
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 90_000 // ntfy mengirim keepalive tiap ±45 detik
                    try {
                        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                        withContext(Dispatchers.Main) { connected = true }
                        conn.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                                ev.optString("id").takeIf { it.isNotEmpty() }?.let { since = it }
                                if (ev.optString("event") != "message") continue
                                val msg = decrypt(ev.optString("message")) ?: continue
                                withContext(Dispatchers.Main) { handle(msg) }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                connected = false
                delay(3000)
            }
        }
    }

    private fun send(msg: JSONObject) {
        val t = topic
        val k = key
        scope.launch { runCatching { publishTo(t, k, msg) } }
    }

    private suspend fun publishTo(t: String, k: SecretKeySpec?, msg: JSONObject) {
        if (t.isEmpty() || k == null) return
        msg.put("from", myId)
        val body = encrypt(k, msg.toString()).toByteArray()
        withContext(Dispatchers.IO) {
            val conn = URL("$SERVER/$t").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.setRequestProperty("Cache", "no")
                conn.setRequestProperty("Firebase", "no")
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    // ------------------------------------------------------------ pesan

    private fun handle(msg: JSONObject) {
        if (msg.optString("from") == myId) return
        when (msg.optString("t")) {
            "hello" -> if (isHost) {
                val name = msg.optString("name", "Tamu")
                guests[msg.optString("from")] = name
                members = 1 + guests.size
                toast("$name bergabung")
                publishState(force = true)
            }
            "bye" -> if (isHost) {
                guests.remove(msg.optString("from"))?.let { name ->
                    members = 1 + guests.size
                    toast("$name keluar")
                    publishState(force = true)
                }
            }
            "end" -> if (!isHost) {
                toast("Room ditutup oleh host")
                leave(notify = false)
            }
            "state" -> if (!isHost) applyState(msg)
            "req" -> if (isHost && guestControl) applyRequest(msg)
        }
    }

    private fun contentOf(st: JSONObject): TogetherContent? {
        val id = st.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return TogetherContent(
            subjectId = id,
            title = st.optString("title"),
            poster = st.optString("poster").takeIf { it.isNotEmpty() },
            isSeries = st.optBoolean("series"),
            season = st.optInt("s"),
            episode = st.optInt("e"),
        )
    }

    private fun JSONObject.putContent(c: TogetherContent?): JSONObject {
        put("id", c?.subjectId ?: "")
        if (c != null) {
            put("title", c.title).put("poster", c.poster ?: "").put("series", c.isSeries)
                .put("s", c.season).put("e", c.episode)
        }
        return this
    }

    private fun open(c: TogetherContent) {
        val p = player
        if (p != null) p.switchTo(c) else opener?.invoke(c)
    }

    // ------------------------------------------------------------ host

    private var sentKey: String? = null
    private var sentPlaying = false
    private var sentPos = 0L
    private var sentAt = 0L
    private var bufSince = 0L

    private suspend fun hostLoop() {
        while (scope.isActive && roomCode != null && isHost) {
            publishState(force = false)
            delay(1000)
        }
    }

    private fun publishState(force: Boolean) {
        val now = System.currentTimeMillis()
        val p = player
        val c = p?.content()
        if (p == null || c == null) {
            if (force || sentKey != "" || now - sentAt > 15_000) {
                sentKey = ""; sentPlaying = false; sentAt = now
                send(JSONObject().put("t", "state").putContent(null).put("play", false).put("at", now).put("n", members).put("gc", guestControl))
            }
            return
        }
        val exo = p.exo
        val rawBuf = p.loading || exo.playbackState == Player.STATE_BUFFERING
        if (!rawBuf) bufSince = 0L else if (bufSince == 0L) bufSince = now
        val buf = rawBuf && now - bufSince > 1200
        val playing = exo.playWhenReady && !buf
        val pos = when {
            p.loading && c.key != sentKey -> 0L
            p.loading -> sentPos
            else -> exo.currentPosition.coerceAtLeast(0)
        }
        val expected = sentPos + if (sentPlaying) now - sentAt else 0L
        val changed = force || c.key != sentKey || playing != sentPlaying ||
            kotlin.math.abs(pos - expected) > 3000 || now - sentAt > 15_000
        if (!changed) return
        sentKey = c.key; sentPlaying = playing; sentPos = pos; sentAt = now
        send(
            JSONObject().put("t", "state").putContent(c)
                .put("pos", pos).put("play", playing).put("at", now)
                .put("n", members).put("gc", guestControl),
        )
    }

    private fun applyRequest(st: JSONObject) {
        val c = contentOf(st) ?: return
        val p = player
        if (p?.content()?.key != c.key) {
            open(c)
            return
        }
        val exo = p.exo
        val target = st.optLong("pos")
        if (kotlin.math.abs(exo.currentPosition - target) > 3000) exo.seekTo(target)
        if (st.optBoolean("play")) exo.play() else exo.pause()
        publishState(force = true)
    }

    // ------------------------------------------------------------ anggota

    private var hostState: JSONObject? = null
    private var pending: JSONObject? = null
    private var lastApplied = 0L
    private var lastReqAt = 0L
    private var lastStateAt = 0L
    private var lastWarnAt = 0L
    private var waitWarned = false

    private suspend fun guestLoop() {
        while (scope.isActive && roomCode != null && !isHost) {
            pending?.let { st ->
                val p = player
                if (p != null && p.content()?.key == contentOf(st)?.key && p.exo.duration > 0 && !p.loading) {
                    pending = null
                    lastApplied = System.currentTimeMillis()
                    p.exo.seekTo(expectedPos(st))
                    if (st.optBoolean("play")) p.exo.play() else p.exo.pause()
                }
            }
            checkGuestChange()
            val st = hostState
            if (st != null && st.optBoolean("play") && System.currentTimeMillis() - lastStateAt > 40_000) {
                player?.exo?.let { if (it.playWhenReady) it.pause() }
                if (!waitWarned) {
                    waitWarned = true
                    toast("Menunggu host…")
                }
            }
            delay(400)
        }
    }

    private fun expectedPos(st: JSONObject): Long {
        val pos = st.optLong("pos")
        val drift = if (st.optBoolean("play")) (System.currentTimeMillis() - st.optLong("at")).coerceIn(0, 60_000) else 0L
        return (pos + drift).coerceAtLeast(0)
    }

    /** Anggota mengubah sendiri: kirim ke host (kalau diizinkan) atau kembalikan ke pilihan host. */
    private fun checkGuestChange() {
        val st = hostState ?: return
        if (pending != null) return
        val p = player ?: return
        val c = p.content() ?: return
        val now = System.currentTimeMillis()
        if (now - lastApplied < 2500) return
        val exo = p.exo
        if (p.loading || exo.playbackState == Player.STATE_BUFFERING) return
        val host = contentOf(st)
        val contentDiff = c.key != host?.key
        val playDiff = !contentDiff && host != null && exo.playWhenReady != st.optBoolean("play")
        val seekDiff = !contentDiff && host != null && kotlin.math.abs(exo.currentPosition - expectedPos(st)) > 5000
        if (!contentDiff && !playDiff && !seekDiff) return
        if (guestControl) {
            if (now - lastReqAt < 3000) return
            lastReqAt = now
            send(
                JSONObject().put("t", "req").putContent(c)
                    .put("pos", exo.currentPosition.coerceAtLeast(0)).put("play", exo.playWhenReady),
            )
        } else if (host != null) {
            if (now - lastWarnAt > 4000) {
                lastWarnAt = now
                toast("Pemutaran mengikuti host")
            }
            applyState(st)
        }
    }

    private fun applyState(st: JSONObject) {
        hostState = st
        lastStateAt = System.currentTimeMillis()
        lastApplied = lastStateAt
        waitWarned = false
        guestControl = st.optBoolean("gc", false)
        members = st.optInt("n", members)
        val c = contentOf(st)
        hostTitle = c?.title
        val p = player
        if (c == null) {
            // Host belum/tidak memutar apa pun.
            p?.exo?.let { if (it.playWhenReady) it.pause() }
            return
        }
        if (p?.content()?.key != c.key) {
            pending = st
            open(c)
            return
        }
        val exo = p.exo
        if (p.loading || exo.duration <= 0) {
            pending = st
            return
        }
        val target = expectedPos(st)
        val play = st.optBoolean("play")
        if (kotlin.math.abs(exo.currentPosition - target) > (if (play) 2500 else 1500)) exo.seekTo(target)
        if (play) { if (!exo.playWhenReady) exo.play() } else if (exo.playWhenReady) exo.pause()
    }

    // ------------------------------------------------------------ util

    private fun randomCode(n: Int): String {
        val r = SecureRandom()
        return (1..n).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun encrypt(k: SecretKeySpec, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return android.util.Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), android.util.Base64.NO_WRAP)
    }

    private fun decrypt(text: String): JSONObject? = try {
        val k = key ?: throw IllegalStateException()
        val raw = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12))
        JSONObject(String(c.doFinal(raw, 12, raw.size - 12)))
    } catch (_: Exception) {
        null
    }
}
