package com.moviebox.app.data

/** Ubah nama bahasa / negara (format bebas dari MovieBox) jadi kode ISO 639-1. */
object Lang {

    private val rules: List<Pair<List<String>, String>> = listOf(
        listOf("indonesia", "bahasa", "indo") to "id",
        listOf("english", "inggris", "united states", "usa", "united kingdom", "uk", "america", "australia", "canada") to "en",
        listOf("korea") to "ko",
        listOf("japan", "jepang") to "ja",
        listOf("chinese", "china", "mandarin", "cantonese", "taiwan", "hong kong", "中文") to "zh",
        listOf("thai") to "th",
        listOf("filipino", "tagalog", "philippin") to "tl",
        listOf("malay") to "ms",
        listOf("vietnam") to "vi",
        listOf("hindi", "india") to "hi",
        listOf("spanish", "español", "spain", "mexic", "argentin", "colombia") to "es",
        listOf("portug", "brazil", "brasil") to "pt",
        listOf("french", "france", "français") to "fr",
        listOf("german", "deutsch", "germany") to "de",
        listOf("italian", "italy") to "it",
        listOf("russian", "russia") to "ru",
        listOf("turkish", "turkey", "türk") to "tr",
        listOf("arabic", "arab", "egypt", "saudi") to "ar",
        listOf("persian", "iran") to "fa",
        listOf("dutch", "netherlands") to "nl",
        listOf("polish", "poland") to "pl",
        listOf("swedish", "sweden") to "sv",
        listOf("danish", "denmark") to "da",
        listOf("norwegian", "norway") to "no",
        listOf("finnish", "finland") to "fi",
        listOf("greek", "greece") to "el",
        listOf("hebrew", "israel") to "he",
        listOf("ukrain") to "uk",
        listOf("tamil") to "ta",
        listOf("telugu") to "te",
        listOf("bengali", "bangla") to "bn",
        listOf("urdu", "pakistan") to "ur",
    )

    private val isoCodes = setOf(
        "id", "in", "en", "ko", "ja", "zh", "th", "tl", "ms", "vi", "hi", "es", "pt", "fr", "de",
        "it", "ru", "tr", "ar", "fa", "nl", "pl", "sv", "da", "no", "fi", "el", "he", "uk", "ta",
        "te", "bn", "ur",
    )

    fun code(nameOrCode: String?): String? {
        if (nameOrCode.isNullOrBlank()) return null
        val raw = nameOrCode.trim().lowercase()
        val head = raw.substringBefore('_').substringBefore('-')
        if (head.length in 2..3 && head in isoCodes) return if (head == "in") "id" else head
        // "uk" juga bisa berarti United Kingdom; untuk teks panjang cek aturan nama dulu.
        for ((keys, code) in rules) {
            if (keys.any { key ->
                    if (key.length <= 2) raw == key else raw.contains(key)
                }
            ) {
                return code
            }
        }
        return null
    }

    fun ofCaption(c: Caption): String? = code(c.code) ?: code(c.name)
}
