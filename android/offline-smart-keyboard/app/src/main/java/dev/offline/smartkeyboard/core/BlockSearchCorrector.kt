package dev.offline.smartkeyboard.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.abs

/**
 * A conventional ЙЦУКЕН keyboard with a HIDDEN 2–9 block search index.
 * Search pools combine nearby block codes, deletion keys and the actual typed letters.
 * Correct real dictionary words are always left alone, regardless of context.
 * No network access and no enumeration of all letters inside each T9 group.
 */
class BlockSearchCorrector(
    input: List<FastCorrector.Entry>,
    private val context: ContextRanker = ContextRanker.EMPTY
) {
    data class Suggestion(val word: String, val score: Double, val edits: Int)
    data class Decision(val text: String, val changed: Boolean, val suggestions: List<Suggestion>)

    private val locale = Locale.forLanguageTag("ru-RU")
    private val dictionary = input
        .filter { it.frequency > 0 && it.word.length in 1..32 }
        .map { FastCorrector.Entry(it.word.lowercase(locale), it.frequency) }
        .groupBy { it.word }
        .map { (_, entries) -> entries.maxBy { it.frequency } }
    private val exactWords = dictionary.associateBy { it.word }
    private val exactCodes = HashMap<String, MutableList<FastCorrector.Entry>>()
    private val maskedCodes = HashMap<String, MutableList<FastCorrector.Entry>>()
    private val deletedWords = HashMap<String, MutableList<FastCorrector.Entry>>()

    // Demonstration exceptions only, not a generally reliable grammar system.
    private val reviewed = mapOf(
        "прывет" to "привет", "превет" to "привет",
        "сиводня" to "сегодня", "севодня" to "сегодня",
        "сможеш" to "сможешь", "чимто" to "чем-то"
    )

    init {
        for (entry in dictionary) {
            val code = T9Predictor.encode(entry.word)
            if (code != null && code.length in 2..24) {
                exactCodes.getOrPut(code) { ArrayList() }.add(entry)
                for (i in code.indices) {
                    maskedCodes.getOrPut(mask(code, i)) { ArrayList() }.add(entry)
                }
            }
            val word = entry.word
            if (word.length in 3..24 && word.all { it.isLetter() }) {
                for (i in word.indices) {
                    deletedWords.getOrPut(word.removeRange(i, i + 1)) { ArrayList() }.add(entry)
                }
            }
        }
    }

    fun dictionarySize(): Int = dictionary.size

    private fun mask(code: String, i: Int): String =
        code.substring(0, i) + '*' + code.substring(i + 1)

    fun suggestions(raw: String, limit: Int = 3, previous: String? = null): List<Suggestion> {
        val word = raw.lowercase(locale)
        if (limit <= 0 || word.length !in 2..24 || word.any { !it.isLetter() }) return emptyList()
        val pool = LinkedHashMap<String, FastCorrector.Entry>()
        fun add(entry: FastCorrector.Entry) {
            if (pool.size < 900) pool.putIfAbsent(entry.word, entry)
        }
        exactWords[word]?.let(::add)
        reviewed[word]?.let { exactWords[it]?.let(::add) }
        // Letters come first: avoid losing close lexical matches in crowded numeric buckets.
        deletedWords[word]?.forEach(::add) // query omits one character
        for (i in word.indices) {
            val without = word.removeRange(i, i + 1)
            deletedWords[without]?.forEach(::add) // substitutions / one extra character
            exactWords[without]?.let(::add)
            if (i + 1 < word.length) {
                val swapped = word.toCharArray()
                val tmp = swapped[i]; swapped[i] = swapped[i + 1]; swapped[i + 1] = tmp
                exactWords[String(swapped)]?.let(::add)
            }
        }
        val code = T9Predictor.encode(word)
        if (code != null) {
            exactCodes[code]?.forEach(::add)
            for (i in code.indices) maskedCodes[mask(code, i)]?.forEach(::add)
        }
        return pool.values.asSequence().mapNotNull { entry ->
            val edits = distance(word, entry.word, 2)
            if (edits > 2 && reviewed[word] != entry.word) null
            else {
                // Edit cost remains dominant, context only resolves genuinely ambiguous typos.
                val exactCredit = if (entry.word == word) -100.0 else 0.0
                val groupPenalty = if (code != null && code == T9Predictor.encode(entry.word)) 0.0 else 0.25
                val score = edits * 3.0 + groupPenalty -
                    ln(entry.frequency.coerceAtLeast(1).toDouble()) * 0.09 -
                    context.bonus(previous, entry.word) + exactCredit
                Suggestion(entry.word, score, edits)
            }
        }.sortedWith(compareBy<Suggestion> { it.score }.thenBy { it.word }).take(limit).toList()
    }

    /** Autochange only an unrecognized word with a strong enough winning candidate. */
    fun decide(raw: String, previous: String? = null): Decision {
        if (raw.length !in 4..24 || raw.any { !it.isLetter() })
            return Decision(raw, false, suggestions(raw, previous = previous))
        val word = raw.lowercase(locale)
        val picks = suggestions(raw, previous = previous)
        if (exactWords.containsKey(word)) return Decision(raw, false, picks)
        val preferred = reviewed[word]
        val target = if (preferred != null && exactWords.containsKey(preferred)) preferred else {
            val best = picks.firstOrNull()
            val second = picks.getOrNull(1)
            if (best != null && best.edits == 1 &&
                (exactWords[best.word]?.frequency ?: 0) >= 100 &&
                (second == null || second.score - best.score >= 2.0)
            ) best.word else null
        }
        if (target == null || target == word) return Decision(raw, false, picks)
        val result = if (raw.first().isUpperCase()) target.replaceFirstChar { it.titlecase(locale) } else target
        return Decision(result, true, picks)
    }

    companion object {
        /** Damerau-OSA with early row exit. Used for ranking, not for pool generation. */
        private fun distance(a: String, b: String, limit: Int): Int {
            if (abs(a.length - b.length) > limit) return limit + 1
            var prev2 = IntArray(b.length + 1)
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                var rowMin = curr[0]
                for (j in 1..b.length) {
                    var d = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    )
                    if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) {
                        d = minOf(d, prev2[j - 2] + 1)
                    }
                    curr[j] = d
                    rowMin = minOf(rowMin, d)
                }
                if (rowMin > limit) return limit + 1
                val tmp = prev2; prev2 = prev; prev = curr; curr = tmp
            }
            return prev[b.length]
        }
    }
}
