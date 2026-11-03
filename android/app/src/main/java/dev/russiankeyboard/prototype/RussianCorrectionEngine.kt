package dev.russiankeyboard.prototype

import java.util.Locale

/** Fast deterministic fallback; no neural inference or personal data collection. */
class RussianCorrectionEngine {
    enum class Reason { TYPO, COMMA }
    data class Edit(
        val expectedSuffix: String,
        val replacementWithSpace: String,
        val reason: Reason
    )

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

    /**
     * Runs only on space, not on every keystroke.
     * Never guesses an addressee's gender or inserts a final period from a pause.
     */
    fun onSpace(textBeforeCursor: String, isSensitiveField: Boolean = false): Edit? {
        if (isSensitiveField) return null

        val wordMatch = wordAtEnd.find(textBeforeCursor)
        if (wordMatch != null) {
            val typed = wordMatch.value
            val normalized = typed.lowercase(Locale.ROOT)
            val corrected = safeTypos[normalized]
            if (corrected != null &&
                hasSafeWordStart(textBeforeCursor, wordMatch.range.first) &&
                hasConventionalCasing(typed)
            ) {
                val restored = when {
                    typed.all { it.isUpperCase() } -> corrected.uppercase(Locale.forLanguageTag("ru"))
                    typed.first().isUpperCase() ->
                        corrected.replaceFirstChar { it.uppercase(Locale.forLanguageTag("ru")) }
                    else -> corrected
                }
                return Edit(typed, "$restored ", Reason.TYPO)
            }
        }

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
