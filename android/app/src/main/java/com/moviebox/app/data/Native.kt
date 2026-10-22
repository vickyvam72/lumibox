package com.moviebox.app.data

/**
 * Fungsi-fungsi dari library Rust (bridge/src/lib.rs).
 * Semua fungsi BLOCKING dan mengembalikan JSON: {"ok":true,"data":...} / {"ok":false,"error":"..."}.
 * Jangan panggil dari main thread — gunakan [Repo].
 */
object Native {
    init {
        System.loadLibrary("moviebox_bridge")
    }

    @JvmStatic external fun initCore(filesDir: String, cacheDir: String): String
    @JvmStatic external fun warmup(): String
    @JvmStatic external fun homepage(tabId: String, page: Int): String
    @JvmStatic external fun search(query: String, page: Int): String
    @JvmStatic external fun webSearch(query: String, page: Int): String
    @JvmStatic external fun details(subjectId: String): String
    @JvmStatic external fun streams(subjectId: String, season: Int, episode: Int): String
    @JvmStatic external fun streamsFull(subjectId: String, season: Int, episode: Int): String
    @JvmStatic external fun captions(subjectId: String, resourceId: String, season: Int, episode: Int): String
}
