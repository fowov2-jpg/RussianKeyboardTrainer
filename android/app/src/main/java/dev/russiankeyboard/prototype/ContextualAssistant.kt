package dev.russiankeyboard.prototype

import java.util.Locale
import kotlin.math.ln

/** Offline proposals only. KEEP and explicit approval remain separate from scores. */
class ContextualAssistant(
    private val lexicon: RussianLexicon,
    private val spelling: SpellingIndex,
    private val language: ContextLanguageModel,
    private val predictions: Map<String, List<String>>,
    private val grammar: Map<String, GrammarForm>,
    private val morphology: MorphologyDictionary? = null
) {
    data class GrammarForm(val gender: String, val forms: Map<String, String>)
    data class Review(val original: String, val proposed: String, val changes: Int)
    private val letters = Regex("[A-Za-zА-Яа-яЁё]+")
    private val tokenAtEnd = Regex("[A-Za-zА-Яа-яЁё]+$")
    private val russian = Regex("[а-яё]+")
    private val contextTokens = Regex("[а-яё]+|[.!?,;:]", RegexOption.IGNORE_CASE)
    private val curated = RussianCorrectionEngine()
    private val locale = Locale.forLanguageTag("ru")
    private val lookalikes = mapOf('a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у')

    private fun normalize(word: String): String? {
        val lower = word.lowercase(locale)
        if (lower.none { it in 'а'..'я' || it == 'ё' }) return null
        val out = lower.map { char ->
            if (char in 'а'..'я' || char == 'ё') char else lookalikes[char] ?: return null
        }.joinToString("")
        return out.takeIf { it.length in 1..28 }
    }
    private fun conventional(word: String): Boolean {
        val lower = word.lowercase(locale)
        return word == lower || word == word.uppercase(locale) || word == lower.replaceFirstChar { it.uppercase(locale) }
    }
    private fun restore(word: String, typed: String): String = when {
        typed.all { it.isUpperCase() } -> word.uppercase(locale)
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase(locale) }
        else -> word
    }
    private fun safeStart(text: String, start: Int): Boolean = start == 0 ||
        text[start-1].isWhitespace() || text[start-1] in "([{«\"'"
    private fun safeEnd(text: String, end: Int): Boolean = end == text.length ||
        text[end].isWhitespace() || text[end] in ")]},.!?;:»\"'"
    private fun context(text: String): List<String> = contextTokens.findAll(text.lowercase(locale)).map { it.value }.toList()

    private fun candidates(typed: String, left: List<String>, right: List<String>): List<String> {
        val normalized = normalize(typed) ?: return emptyList()
        if (!conventional(typed)) return emptyList()
        if (morphology?.contains(normalized) == true) {
            return if (normalized != typed.lowercase(locale)) listOf(restore(normalized, typed)) else emptyList()
        }
        val fixed = curated.suggestions(typed).map { it.edit.replacementWithSpace.trimEnd().lowercase(locale) }
        val known = lexicon.isKnown(normalized) || spelling.contains(normalized)
        if (known && fixed.isEmpty()) {
            return if (normalized != typed.lowercase(locale)) listOf(restore(normalized, typed)) else emptyList()
        }
        val sampled = lexicon.lookup(normalized)?.word
        val found = spelling.search(normalized, if (normalized.length < 4) 1 else 2)
        val all = linkedMapOf<String, Pair<Int, Int>>()
        for (word in fixed) all[word] = 0 to 0
        if (sampled != null) all[sampled] = 1 to 0
        for (item in found) if (item.word !in all) all[item.word] = item.distance to item.rank
        return all.entries.sortedByDescending { (word, info) ->
            language.score(left, word, right) - 2.3 * info.first - 0.06 * ln(info.second + 2.0)
        }.map { restore(it.key, typed) }.take(5)
    }

    fun suggestions(before: String): List<RussianCorrectionEngine.Suggestion> {
        if (before.isEmpty()) return emptyList()
        if (before.last().isWhitespace()) {
            val tokens = context(before)
            if (tokens.isEmpty()) return emptyList()
            val key = tokens.takeLast(2).joinToString(" ")
            val words = predictions[key] ?: predictions[tokens.last()] ?: return emptyList()
            return words.take(3).map { word ->
                RussianCorrectionEngine.Suggestion(RussianCorrectionEngine.Edit("", "$word ",
                    RussianCorrectionEngine.Reason.PREDICTION), false)
            }
        }
        val match = tokenAtEnd.find(before) ?: return emptyList()
        if (!safeStart(before, match.range.first) || !conventional(match.value)) return emptyList()
        val normalized = normalize(match.value) ?: return emptyList()
        val left = context(before.substring(0, match.range.first))
        val corrections = candidates(match.value, left, emptyList())
        if (corrections.isNotEmpty()) return corrections.take(3).map { value ->
            RussianCorrectionEngine.Suggestion(RussianCorrectionEngine.Edit(match.value, "$value ",
                RussianCorrectionEngine.Reason.TYPO), false)
        }
        // Completion never changes a known word unless the user selects it.
        return spelling.complete(normalized).map { item ->
            RussianCorrectionEngine.Suggestion(RussianCorrectionEngine.Edit(match.value,
                "${restore(item.word, match.value)} ", RussianCorrectionEngine.Reason.COMPLETION), false)
        }
    }

    /** Two context-aware passes let adjacent misspellings inform each other. */
    fun review(original: String): Review? {
        if (original.isBlank() || original.length > 240) return null
        var updated = original
        repeat(2) {
            val matches = letters.findAll(updated).toList()
            var changed = 0
            val result = StringBuilder(); var cursor = 0
            for (match in matches.take(40)) {
                result.append(updated, cursor, match.range.first)
                val end = match.range.last + 1
                var value = match.value
                val prefix = result.toString().trimEnd()
                val possibleName = value.firstOrNull()?.isUpperCase() == true &&
                    value.drop(1).all { it.isLowerCase() } && prefix.isNotEmpty() && prefix.last() !in ".!?\n"
                if (!possibleName && safeStart(updated, match.range.first) && safeEnd(updated, end)) {
                    val left = context(result.toString())
                    val right = context(updated.substring(end)).take(2)
                    val candidate = candidates(value, left, right).firstOrNull()
                    if (candidate != null && candidate != value) { value = candidate; changed++ }
                }
                result.append(value); cursor = end
            }
            result.append(updated, cursor, updated.length)
            if (changed > 0) updated = result.toString()
        }
        // Only an explicit adjacent third-person subject establishes gender.
        // "я/ты" and an addressee's gender are never guessed.
        val agreement = Regex("(?iu)(?<![\\p{L}\\p{N}_@#/.-])(он|она|оно)((?:[ \\t]+(?:мне|тебе|нам|вам|ему|ей|им|уже|ещё|сегодня|вчера|тут|там|тоже|не|очень)){0,2}[ \\t]+)([а-яё]+)(?![\\p{L}\\p{N}_/-])")
        updated = agreement.replace(updated) { match ->
            val typed = match.groupValues[3]
            val desired = when (match.groupValues[1].lowercase(locale)) { "она" -> "Fem"; "оно" -> "Neut"; else -> "Masc" }
            val record = grammar[typed.lowercase(locale)]
            val replacement = if (record != null && record.gender != desired && conventional(typed) &&
                conventional(match.groupValues[1]) && safeStart(updated, match.range.first)) record.forms[desired] else null
            if (replacement != null) match.groupValues[1] + match.groupValues[2] + restore(replacement, typed) else match.value
        }
        // Existing conservative comma patterns are previewed in a full phrase.
        val comma = Regex("(?iu)(?<![\\p{L}\\p{N}_@#/.-])(думаю|знаю|понимаю|сказал|сказала)( +)(что)(?![\\p{L}\\p{N}_])")
        updated = comma.replace(updated) {
            if (conventional(it.groupValues[1]) && conventional(it.groupValues[3]) && safeStart(updated, it.range.first))
                it.groupValues[1] + ", " + it.groupValues[3] else it.value
        }
        if (updated == original) return null
        val originalWords = letters.findAll(original).map { it.value }.toList()
        val newWords = letters.findAll(updated).map { it.value }.toList()
        val changes = originalWords.zip(newWords).count { it.first != it.second } +
            kotlin.math.abs(originalWords.size - newWords.size) + if (updated.count { it == ',' } > original.count { it == ',' }) 1 else 0
        return Review(original, updated, maxOf(1, changes))
    }

    companion object {
        fun parsePredictions(lines: List<String>): Map<String, List<String>> = lines.filter { it.isNotEmpty() }.associate { line ->
            val fields = line.split('\t'); fields.first() to fields.drop(1).take(3)
        }
        fun parseGrammar(lines: List<String>): Map<String, GrammarForm> = lines.filter { it.isNotEmpty() }.associate { line ->
            val fields = line.split('\t')
            require(fields.size == 5)
            fields[0] to GrammarForm(fields[1], listOf("Masc", "Fem", "Neut").mapIndexedNotNull { i, gender ->
                fields[i + 2].takeIf { it.isNotEmpty() }?.let { gender to it }
            }.toMap())
        }
    }
}
