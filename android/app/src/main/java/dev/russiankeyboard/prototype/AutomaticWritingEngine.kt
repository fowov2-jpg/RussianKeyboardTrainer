package dev.russiankeyboard.prototype

/** Automatic edits are limited to known spelling edits, explicit grammar and real RUPunct output. */
class AutomaticWritingEngine(
    private val spelling: RussianCorrectionEngine,
    private val assistant: ContextualAssistant?,
    private val punctuate: (String, String, PunctuationPlanner.Mode, Boolean) -> PunctuationPlanner.Result?,
    private val correctSpelling: Boolean = true
) {
    data class Result(val original: String, val replacement: String, val provisionalTerminal: String?)
    fun analyze(text: String, conversation: String, profile: WritingProfile,
                mode: PunctuationPlanner.Mode, sentenceStart: Boolean = true): Result? {
        if (text.isBlank() || text.length > 640 || text.any { it == '\u0000' }) return null
        // Restrict an automatic pass to the current paragraph. Earlier text remains intact.
        val paragraph = text.substringAfterLast('\n')
        if (paragraph.isBlank()) return null
        var updated = paragraph
        val protected = Regex("(?i)(?:https?://|www\\.)\\S+|[^\\s]+@[^\\s]+|[@#][\\p{L}\\p{N}_]+|[\\p{L}\\p{N}]+(?:[_/-][\\p{L}\\p{N}]+)+")
            .findAll(paragraph).map { it.range }.toList()
        val quoted = paragraph.any { it in "«»\"“”" }
        val words = Regex("[А-Яа-яЁё]+")
        val output = StringBuilder(); var cursor = 0
        for (word in words.findAll(updated).take(80)) {
            output.append(updated, cursor, word.range.first)
            val end = word.range.last + 1
            val safeEnd = end == updated.length || !(updated[end].isLetterOrDigit() || updated[end] in "_/-@")
            val edit = if (correctSpelling && !quoted && safeEnd && protected.none { word.range.first <= it.last && word.range.last >= it.first })
                spelling.onSpace(updated.substring(0, end)) else null
            output.append(if (edit?.reason == RussianCorrectionEngine.Reason.TYPO && edit.expectedSuffix == word.value)
                edit.replacementWithSpace.trimEnd() else word.value)
            cursor = word.range.last + 1
        }
        output.append(updated, cursor, updated.length)
        updated = assistant?.automaticGrammar(output.toString(), profile) ?: output.toString()
        val punctuated = punctuate(updated, conversation, mode, sentenceStart || text.contains('\n'))
        val replacement = punctuated?.text ?: updated
        val full = text.substring(0, text.length - paragraph.length) + replacement
        return if (full == text) null else Result(text, full, punctuated?.provisionalTerminal)
    }
}
