package dev.russiankeyboard.prototype

/**
 * One-entry in-memory undo checkpoint. Never writes typed text to disk.
 * The IME invalidates it on the next key or when the editor changes.
 */
class CorrectionUndoState {
    private var pending: RussianCorrectionEngine.Edit? = null

    fun record(edit: RussianCorrectionEngine.Edit) {
        pending = edit
    }

    fun suffixLength(): Int = pending?.replacementWithSpace?.length ?: 0

    fun takeIfMatching(observedSuffix: String?): String? {
        val edit = pending ?: return null
        pending = null
        return if (observedSuffix == edit.replacementWithSpace) edit.expectedSuffix else null
    }

    fun clear() {
        pending = null
    }
}
