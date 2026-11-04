package dev.offline.smartkeyboard.core

import java.util.Locale

/**
 * Russian T9-like dictionary decoder. Lookup is O(1) in the number of stored words:
 * every digit prefix is indexed once on startup, keeping only the top candidates.
 * This is an original educational implementation, not the historic Tegic code.
 */
class T9Predictor(entries: List<FastCorrector.Entry>) {
    data class Candidate(val word: String, val exact: Boolean)

    companion object {
        // 1 is punctuation; 0 is space, as on classic numeric handsets.
        val KEY_LETTERS: Map<Char, String> = linkedMapOf(
            '2' to "абвг", '3' to "деёжз", '4' to "ийкл", '5' to "мноп",
            '6' to "рсту", '7' to "фхцч", '8' to "шщъы", '9' to "ьэюя"
        )
        private val charToKey: Map<Char, Char> = buildMap {
            for ((key, letters) in KEY_LETTERS) {
                for (letter in letters) put(letter, key)
            }
        }

        fun encode(word: String): String? {
            if (word.isEmpty()) return null
            val result = StringBuilder(word.length)
            for (letter in word.lowercase(Locale.forLanguageTag("ru-RU"))) {
                val key = charToKey[letter] ?: return null
                result.append(key)
            }
            return result.toString()
        }
    }

    private data class Ranked(val word: String, val frequency: Int)

    private val exact: Map<String, List<Ranked>>
    private val prefix: Map<String, List<Ranked>>

    init {
        val exactMap = HashMap<String, MutableList<Ranked>>()
        val prefixMap = HashMap<String, MutableList<Ranked>>()
        for (entry in entries) {
            val code = encode(entry.word) ?: continue  // Hyphenated terms need a later tokenizer.
            val ranked = Ranked(entry.word, entry.frequency)
            insert(exactMap.getOrPut(code) { ArrayList() }, ranked)
            for (n in 1..code.length) {
                insert(prefixMap.getOrPut(code.substring(0, n)) { ArrayList() }, ranked)
            }
        }
        exact = exactMap
        prefix = prefixMap
    }

    // Keep the top 6 per prefix (small, constant memory per index key).
    private fun insert(bucket: MutableList<Ranked>, item: Ranked) {
        if (bucket.any { it.word == item.word }) return
        bucket.add(item)
        bucket.sortWith(compareByDescending<Ranked> { it.frequency }.thenBy { it.word })
        if (bucket.size > 6) bucket.removeAt(bucket.lastIndex)
    }

    /** Exact-length words come before longer completions. No model is executed. */
    fun candidates(digits: String, limit: Int = 3): List<Candidate> {
        if (digits.isEmpty() || digits.length > 32 || digits.any { it !in '2'..'9' }) {
            return emptyList()
        }
        val first = exact[digits].orEmpty().map { Candidate(it.word, true) }
        val next = prefix[digits].orEmpty().map { Candidate(it.word, false) }
        return (first + next).distinctBy { it.word }.take(limit)
    }

    /** Only commit an exact-length word; unknown digit codes must not invent words. */
    fun commit(digits: String): String? = candidates(digits).firstOrNull { it.exact }?.word

    /** While entering digits, display a prefix of the best matching dictionary word. */
    fun preview(digits: String): String {
        val proposed = candidates(digits).firstOrNull()?.word ?: return digits
        return proposed.take(digits.length)
    }
}
