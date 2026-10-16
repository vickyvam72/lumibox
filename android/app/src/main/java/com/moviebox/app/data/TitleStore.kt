package com.moviebox.app.data

import java.util.concurrent.ConcurrentHashMap

/** Menyimpan objek Title sementara supaya bisa dibuka lewat rute navigasi berdasarkan key. */
object TitleStore {
    private val map = ConcurrentHashMap<String, Title>()

    fun put(t: Title): String {
        map[t.key] = t
        return t.key
    }

    fun get(key: String): Title? = map[key]
}
