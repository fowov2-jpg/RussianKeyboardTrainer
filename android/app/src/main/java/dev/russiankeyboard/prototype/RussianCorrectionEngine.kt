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
            if (corrected != null) {
                val restored = if (typed.first().isUpperCase()) {
                    corrected.replaceFirstChar { it.uppercase(Locale.ROOT) }
                } else corrected
                return Edit(typed, "$restored ", Reason.TYPO)
            }
        }

        // Conservative demonstration rule, NOT an AI punctuation model.
        val match = commaBeforeThat.find(textBeforeCursor) ?: return null
        if (match.range.first > 0 && textBeforeCursor[match.range.first - 1].isLetter()) return null
        val original = match.value
        val insertionIndex = original.lastIndexOf(' ')
        val updated = original.substring(0, insertionIndex) + ", " + original.substring(insertionIndex + 1)
        return Edit(original, "$updated ", Reason.COMMA)
    }
}
