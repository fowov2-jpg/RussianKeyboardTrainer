package dev.russiankeyboard.prototype

/** A legitimate unfinished word must not be interpreted as a complete sentence token. */
object StreamingTextBoundary {
    data class Span(val input: String, val tail: String)
    fun split(text: String, known: (String) -> Boolean, prefix: (String) -> Boolean): Span {
        val match = Regex("[А-Яа-яЁё]+$").find(text) ?: return Span(text, "")
        val normalized = match.value.lowercase()
        return if (!known(normalized) && prefix(normalized)) Span(text.substring(0, match.range.first), match.value)
            else Span(text, "")
    }
}
