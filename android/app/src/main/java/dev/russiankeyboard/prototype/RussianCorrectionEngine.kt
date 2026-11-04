package dev.russiankeyboard.prototype

import java.util.Locale

/** Fast deterministic fallback; no neural inference or personal data collection. */
class RussianCorrectionEngine(
    private val morphology: () -> MorphologyDictionary? = { null },
    private val lexicon: () -> RussianLexicon? = { null }
) {
    enum class Reason { TYPO, COMMA, COMPLETION, PREDICTION, PHRASE }
    data class Edit(
        val expectedSuffix: String,
        val replacementWithSpace: String,
        val reason: Reason
    )
    data class Suggestion(val edit: Edit, val automatic: Boolean)

    private val safeTypos = mapOf(
        "севодня" to "сегодня",
        "сиводня" to "сегодня",
        "превет" to "привет",
        "привт" to "привет",
        "пожалуста" to "пожалуйста",
        "спосибо" to "спасибо",
        "извените" to "извините",
        "очнь" to "очень",
        "потомучто" to "потому что",
        "здравстуйте" to "здравствуйте"
    )
    private val wordAtEnd = Regex("[А-Яа-яЁё]+$")
    private val russianLocale = Locale.forLanguageTag("ru")

    /**
     * Mixed-case text may be an acronym, nickname or deliberate styling.
     * Accept only lowercase, Titlecase, and ALL CAPS to avoid changing it.
     */
    private fun hasConventionalCasing(word: String): Boolean {
        val lower = word.lowercase(russianLocale)
        val upper = word.uppercase(russianLocale)
        val title = lower.replaceFirstChar { it.uppercase(russianLocale) }
        return word == lower || word == upper || word == title
    }

    /**
     * Only change complete human-language words. Suffixes after @, #, '.', '/',
     * digits, underscores, or hyphens may belong to handles, URLs, paths,
     * identifiers or compounds: fail closed rather than alter that content.
     */
    private fun hasSafeWordStart(text: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = text[start - 1]
        return previous.isWhitespace() ||
            previous == '(' || previous == '[' || previous == '{' ||
            previous == '«' || previous == '"' || previous == '\''
    }
    private val commaBeforeThat = Regex(
        "(думаю|знаю|понимаю|сказал|сказала) что$",
        RegexOption.IGNORE_CASE
    )

    /** Same boundaries and casing for both automatic and user-approved edits. */
    fun suggestions(textBeforeCursor: String, isSensitiveField: Boolean = false): List<Suggestion> {
        if (isSensitiveField) return emptyList()
        val wordMatch = wordAtEnd.find(textBeforeCursor)
        if (wordMatch != null) {
            val typed = wordMatch.value
            val normalized = typed.lowercase(Locale.ROOT)
            if (morphology()?.contains(normalized) == true) return emptyList()
            if (!hasSafeWordStart(textBeforeCursor, wordMatch.range.first) ||
                !hasConventionalCasing(typed)) return emptyList()
            val curated = safeTypos[normalized]
            val candidate = lexicon()?.lookup(normalized)
            val choices = mutableListOf<Pair<String, Boolean>>()
            if (curated != null) choices.add(curated to true)
            if (candidate != null && candidate.word != curated) {
                choices.add(candidate.word to (curated == null && candidate.automatic))
            }
            return choices.map { (corrected, automatic) ->
                val restored = when {
                    typed.all { it.isUpperCase() } -> corrected.uppercase(Locale.forLanguageTag("ru"))
                    typed.first().isUpperCase() ->
                        corrected.replaceFirstChar { it.uppercase(Locale.forLanguageTag("ru")) }
                    else -> corrected
                }
                Suggestion(Edit(typed, "$restored ", Reason.TYPO), automatic)
            }
        }
        return emptyList()
    }

    fun suggest(textBeforeCursor: String, isSensitiveField: Boolean = false): Suggestion? =
        suggestions(textBeforeCursor, isSensitiveField).firstOrNull()

    /** Runs only on space. Ambiguous/rare/two-edit candidates need a tap. */
    fun onSpace(textBeforeCursor: String, isSensitiveField: Boolean = false): Edit? {
        if (isSensitiveField) return null
        val suggestion = suggest(textBeforeCursor)
        if (suggestion?.automatic == true) return suggestion.edit

        // Conservative demonstration rule, NOT an AI punctuation model.
        val match = commaBeforeThat.find(textBeforeCursor) ?: return null
        if (!hasSafeWordStart(textBeforeCursor, match.range.first)) return null
        val original = match.value
        if (!original.split(' ').all(::hasConventionalCasing)) return null
        val insertionIndex = original.lastIndexOf(' ')
        val updated = original.substring(0, insertionIndex) + ", " + original.substring(insertionIndex + 1)
        return Edit(original, "$updated ", Reason.COMMA)
    }
}
