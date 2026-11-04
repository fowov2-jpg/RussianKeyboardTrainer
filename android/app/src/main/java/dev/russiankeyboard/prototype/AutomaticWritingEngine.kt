package dev.russiankeyboard.prototype

/** Automatic edits are limited to known spelling edits, explicit grammar and real RUPunct output. */
class AutomaticWritingEngine(
    private val spelling: RussianCorrectionEngine,
    private val assistant: ContextualAssistant?,
    private val punctuate: (String, String, PunctuationPlanner.Mode, Boolean) -> PunctuationPlanner.Result?,
    private val correctSpelling: Boolean = true
) {
    data class Result(val original: String, val replacement: String, val provisionalTerminal: String?, val finalizedReplacement: String? = null)
    fun analyze(text: String, conversation: String, profile: WritingProfile,
                mode: PunctuationPlanner.Mode, sentenceStart: Boolean = true): Result? {
        if (text.isBlank() || text.length > 640 || text.any { it == '\u0000' }) return null
        // Restrict an automatic pass to the current paragraph. Earlier text remains intact.
        val paragraph = text.substringAfterLast('\n')
        if (paragraph.isBlank()) return null
        val streaming = mode == PunctuationPlanner.Mode.STREAMING
        val updated = surface(paragraph, profile, completeLast = !streaming)
        val punctuated = punctuate(updated, conversation, mode, sentenceStart || text.contains('\n'))
        val replacement = punctuated?.text ?: updated
        val prefix = text.substring(0, text.length - paragraph.length)
        val full = prefix + replacement
        // Preparing Send does not need another inference: apply only the fast
        // spelling/agreement stage to the already predicted finalized surface.
        val finalized = if (streaming) prefix + surface(punctuated?.finalizedText ?: replacement, profile, completeLast = true) else null
        return if (full == text && (finalized == null || finalized == text)) null
            else Result(text, full, punctuated?.provisionalTerminal, finalized)
    }

    private fun surface(value: String, profile: WritingProfile, completeLast: Boolean): String {
        val protected = Regex("(?i)(?:https?://|www\\.)\\S+|[^\\s]+@[^\\s]+|[@#][\\p{L}\\p{N}_]+|[\\p{L}\\p{N}]+(?:[_/-][\\p{L}\\p{N}]+)+")
            .findAll(value).map { it.range }.toList()
        val quoted = value.any { it in "«»\"“”" }
        val words = Regex("[А-Яа-яЁё]+")
        val output = StringBuilder(); var cursor = 0
        for (word in words.findAll(value).take(80)) {
            output.append(value, cursor, word.range.first)
            val end = word.range.last + 1
            val safeEnd = end == value.length || !(value[end].isLetterOrDigit() || value[end] in "_/-@")
            val committed = completeLast || end < value.length
            val edit = if (committed && correctSpelling && !quoted && safeEnd && protected.none { word.range.first <= it.last && word.range.last >= it.first })
                spelling.onSpace(value.substring(0, end)) else null
            output.append(if (edit?.reason == RussianCorrectionEngine.Reason.TYPO && edit.expectedSuffix == word.value)
                edit.replacementWithSpace.trimEnd() else word.value)
            cursor = word.range.last + 1
        }
        output.append(value, cursor, value.length)
        val written = output.toString()
        val tail = if (completeLast) null else words.findAll(written).lastOrNull()?.takeIf { it.range.last + 1 == written.length }
        return if (tail != null) {
            val prefix = written.substring(0, tail.range.first)
            (assistant?.automaticGrammar(prefix, profile) ?: prefix) + tail.value
        } else assistant?.automaticGrammar(written, profile) ?: written
    }
}
