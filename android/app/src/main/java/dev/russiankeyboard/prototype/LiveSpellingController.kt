package dev.russiankeyboard.prototype

/** Per-keystroke dictionary path. Preserves raw intent until the word is committed. */
class LiveSpellingController(
    private val engine: RussianCorrectionEngine,
    private val ready: () -> Boolean,
    private val prefix: (String) -> Boolean,
    private val protectedPrefix: (String) -> Boolean = prefix
) {
    data class Plan(val edit: RussianCorrectionEngine.Edit?, val checkpoint: RussianCorrectionEngine.Edit?)
    private data class Pending(val raw: String, val shown: String, val anchor: String, val strict: Boolean = false)
    private var pending: Pending? = null
    private val word = Regex("[А-Яа-яЁё]+$")
    val active: Boolean get() = pending != null
    fun clear() { pending = null }
    fun reconcile(text: String) {
        val old = pending ?: return
        val observed = text.takeLast(old.shown.length)
        pending = if (observed.equals(old.shown, ignoreCase = true) && conventional(observed))
            old.copy(shown = observed, anchor = text.dropLast(observed.length).takeLast(64)) else null
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
            val ordinary = key.all { it in 'а'..'я' || it in 'А'..'Я' || it in "ёЁ" } && conventional(raw) && !knownPrefix(raw)
            val choices = if (ordinary) engine.suggestions(old.anchor + raw) else emptyList()
            val freshChoice = choices.firstOrNull { it.automatic }
            val fresh = freshChoice?.edit?.replacementWithSpace?.trimEnd()
            if (fresh != null) {
                pending = Pending(raw, fresh, old.anchor, freshChoice?.requiresGrowingEvidence == true)
                return Plan(if (fresh == shown) null else RussianCorrectionEngine.Edit(shown, fresh, RussianCorrectionEngine.Reason.TYPO),
                    RussianCorrectionEngine.Edit(raw, fresh, RussianCorrectionEngine.Reason.TYPO))
            }
            // A later letter can expose a competing complete-word candidate.
            // Do not carry an earlier prefix guess past that new ambiguity.
            val keep = !old.strict && ordinary && knownPrefix(shown.substringAfterLast(' ')) &&
                !engine.hasAmbiguousFrequentCandidate(raw) && choices.none { it.edit.replacementWithSpace.trimEnd() != shown }
            if (keep) {
                pending = Pending(raw, shown, old.anchor, old.strict)
                return Plan(null, RussianCorrectionEngine.Edit(raw, shown, RussianCorrectionEngine.Reason.TYPO))
            }
            return Plan(RussianCorrectionEngine.Edit(shown, raw, RussianCorrectionEngine.Reason.TYPO), null)
        }
        if (!ready() || !key.all { it in 'а'..'я' || it in 'А'..'Я' || it in "ёЁ" }) return null
        val match = word.find(text) ?: return null
        if (match.value.length !in 3..28 || protectedPrefix(match.value.lowercase())) return null
        val choice = engine.suggestions(text).firstOrNull { it.automatic } ?: return null
        val shown = choice.edit.replacementWithSpace.trimEnd()
        pending = Pending(match.value, shown, text.substring(0, match.range.first).takeLast(64), choice.requiresGrowingEvidence)
        val edit = RussianCorrectionEngine.Edit(match.value, shown, RussianCorrectionEngine.Reason.TYPO)
        return Plan(edit, edit)
    }
}
