package dev.offline.smartkeyboard.core

import java.util.Locale

/**
 * A deliberately conservative offline spelling prototype.
 * Dictionary-only, no network, no model. Expand dictionary and evaluate false positives
 * before using aggressive autocorrection in real conversations.
 *
 * The delete index is inspired by SymSpell-style candidate retrieval. This is not T9
 * code, and it does not claim to reproduce Tegic's proprietary implementation.
 */
class FastCorrector(entries: List<Entry>) {
    data class Entry(val word: String, val frequency: Int)
    data class Guess(val text: String, val corrected: Boolean)

    private val locale = Locale.forLanguageTag("ru-RU")
    private val words: List<Entry> = entries.map { Entry(it.word.lowercase(locale), it.frequency) }
    private val exact = words.associateBy { it.word }
    private val deleted: Map<String, Set<Int>> = buildMap {
        words.forEachIndexed { index, entry ->
            for (key in deletions(entry.word, 2)) {
                val current = get(key)?.toMutableSet() ?: mutableSetOf()
                current.add(index)
                put(key, current)
            }
        }
    }

    // Only manually reviewed high-confidence examples; NOT a general grammar engine.
    private val knownTypos = mapOf(
        "прывет" to "привет",
        "превет" to "привет",
        "сиводня" to "сегодня",
        "севодня" to "сегодня",
        "сможеш" to "сможешь",
        "чимто" to "чем-то"
    )

    fun guess(raw: String): Guess {
        val word = raw.lowercase(locale)
        if (word.length < 4 || word.length > 32 || word.any { !it.isLetter() }) {
            return Guess(raw, false)
        }
        if (exact.containsKey(word)) return Guess(raw, false)
        val reviewed = knownTypos[word]
        if (reviewed != null && exact.containsKey(reviewed)) {
            return Guess(matchCase(raw, reviewed), true)
        }
        if (word.length < 5) return Guess(raw, false)

        val indices = HashSet<Int>()
        val queryKeys = deletions(word, 2)
        for (key in queryKeys) {
            deleted[key]?.let { indices.addAll(it) }
        }
        // For a full-length word with one typo, exact matching through delete signatures
        // tends to be fast, but all proposals are verified by real edit distance.
        val candidates = indices.asSequence()
            .map { words[it] }
            .filter { kotlin.math.abs(it.word.length - word.length) <= 1 }
            .map { it to editDistanceAtMostTwo(word, it.word) }
            .filter { it.second in 1..2 }
            .sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.frequency })
            .take(3)
            .toList()

        val first = candidates.firstOrNull() ?: return Guess(raw, false)
        val second = candidates.getOrNull(1)
        val safe = first.second == 1 &&
            first.first.word.length == word.length &&
            first.first.frequency >= 100 &&
            (second == null || second.second > 1)
        return if (safe) Guess(matchCase(raw, first.first.word), true)
        else Guess(raw, false)
    }

    private fun matchCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true)
            replacement.replaceFirstChar { it.titlecase(locale) }
        else replacement

    companion object {
        fun parseDictionary(lines: Sequence<String>): List<Entry> = lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size != 2 || parts[0].startsWith("#")) null
            else parts[1].toIntOrNull()?.let { Entry(parts[0], it) }
        }.toList()

        private fun deletions(text: String, depth: Int): Set<String> {
            val found = hashSetOf(text)
            var frontier = hashSetOf(text)
            repeat(depth) {
                val next = HashSet<String>()
                for (word in frontier) {
                    for (i in word.indices) {
                        val smaller = word.removeRange(i, i + 1)
                        if (found.add(smaller)) next.add(smaller)
                    }
                }
                frontier = next
            }
            return found
        }

        private fun editDistanceAtMostTwo(a: String, b: String): Int {
            if (kotlin.math.abs(a.length - b.length) > 2) return 3
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(
                        d[i - 1][j] + 1,
                        d[i][j - 1] + 1,
                        d[i - 1][j - 1] + cost
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                    }
                }
            }
            return d[a.length][b.length]
        }
    }
}
