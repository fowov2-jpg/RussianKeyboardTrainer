package dev.russiankeyboard.prototype

/**
 * Candidate acceptance gate for future on-device ranker integration.
 * This is deliberately not connected to the IME: the CI model is synthetic.
 *
 * Mirrors src/rsk_trainer/correction_policy.py. The caller must additionally
 * recheck the live InputConnection cursor and suffix before any real edit.
 */
object RankerKeepPolicy {
    enum class Kind { SPELLING, GRAMMAR }

    data class Candidate(
        val text: String,
        val score: Float,
        val isKeep: Boolean = false,
        val kind: Kind = Kind.SPELLING
    )

    fun chooseReplacement(
        typed: String,
        candidates: List<Candidate>,
        keepMargin: Float,
        requestRevision: Long,
        currentRevision: Long,
        sensitiveField: Boolean = false,
        allowGrammar: Boolean = false
    ): String {
        require(keepMargin.isFinite() && keepMargin >= 0f) {
            "keepMargin must be finite and nonnegative"
        }
        if (sensitiveField || requestRevision != currentRevision || typed.isEmpty()) return typed

        val keep = candidates.firstOrNull {
            it.isKeep && it.text == typed && it.score.isFinite()
        } ?: return typed

        val best = candidates.asSequence().filter {
            !it.isKeep && it.text.isNotEmpty() && it.text != typed &&
                it.score.isFinite() && (it.kind == Kind.SPELLING ||
                (allowGrammar && it.kind == Kind.GRAMMAR))
        }.maxByOrNull { it.score }

        return if (best != null &&
            best.score.toDouble() > keep.score.toDouble() + keepMargin.toDouble()
        ) best.text else typed
    }
}
