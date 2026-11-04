package dev.russiankeyboard.prototype

/** Per-keystroke dictionary path. Preserves raw intent until the word is committed. */
class LiveSpellingController(
    private val engine: RussianCorrectionEngine,
    private val ready: () -> Boolean,
    private val prefix: (String) -> Boolean,
    private val protectedPrefix: (String) -> Boolean = prefix
) {
    data class Plan(val edit: RussianCorrectionEngine.Edit?, val checkpoint: RussianCorrectionEngine.Edit?)
    private data class Pending(val raw: String, val shown: String, val anchor: String)
    private var pending: Pending? = null
    private val word = Regex("[А-Яа-яЁё]+$")
    fun clear() { pending = null }
    fun reconcile(text: String) {
        val old = pending ?: return
        val observed = text.takeLast(old.shown.length)
        pending = if (observed.equals(old.shown, ignoreCase = true) && conventional(observed))
            Pending(old.raw, observed, text.dropLast(observed.length).takeLast(64)) else null
    }
    private fun conventional(value: String): Boolean = value == value.lowercase() || value == value.uppercase() ||
        value == value.lowercase().replaceFirstChar { it.uppercase() }
    private fun knownPrefix(value: String): Boolean = prefix(value.lowercase())

    /** Called after a literal key has reached the editor. Never synthesizes a space. */
    fun appended(text: String, key: String): Plan? {
        if (key.isEmpty()) return null
        val old = pending
        pending = null
        if (old != null && text.endsWith(old.shown + key) && text.dropLast(old.shown.length + key.length).endsWith(old.anchor)) {
            val raw = old.raw + key
            val shown = old.shown + key
            val continuation = key.all { it.isLetterOrDigit() || it in "_/-@#" }
            if (!continuation) {
                val original = if (key == " ") old.raw else raw
                return Plan(null, RussianCorrectionEngine.Edit(original, shown, RussianCorrectionEngine.Reason.TYPO))
            }
            val keep = key.all { it in 'а'..'я' || it in 'А'..'Я' || it in "ёЁ" } &&
                conventional(raw) && !knownPrefix(raw) && knownPrefix(shown.substringAfterLast(' '))
            if (keep) {
                pending = Pending(raw, shown, old.anchor)
                return Plan(null, RussianCorrectionEngine.Edit(raw, shown, RussianCorrectionEngine.Reason.TYPO))
            }
            return Plan(RussianCorrectionEngine.Edit(shown, raw, RussianCorrectionEngine.Reason.TYPO), null)
        }
        if (!ready() || !key.all { it in 'а'..'я' || it in 'А'..'Я' || it in "ёЁ" }) return null
        val match = word.find(text) ?: return null
        if (match.value.length !in 4..28 || protectedPrefix(match.value.lowercase())) return null
        val choice = engine.suggestions(text).firstOrNull { it.automatic } ?: return null
        val shown = choice.edit.replacementWithSpace.trimEnd()
        pending = Pending(match.value, shown, text.substring(0, match.range.first).takeLast(64))
        val edit = RussianCorrectionEngine.Edit(match.value, shown, RussianCorrectionEngine.Reason.TYPO)
        return Plan(edit, edit)
    }
}
