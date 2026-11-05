package dev.russiankeyboard.prototype

/** Apply an analysed prefix while retaining later keystrokes verbatim. */
object StreamingEditRebaser {
    data class Plan(val original: String, val replacement: String, val provisionalTerminal: String?, val tail: String)
    fun rebase(original: String, replacement: String, terminal: String?, sourceEnd: Int,
               current: String, currentEnd: Int): Plan? {
        if (sourceEnd < original.length || currentEnd < sourceEnd || currentEnd < current.length) return null
        val start = sourceEnd - original.length - (currentEnd - current.length)
        val end = sourceEnd - (currentEnd - current.length)
        if (start < 0 || end !in start..current.length || current.substring(start, end) != original) return null
        val tail = current.substring(end)
        var updated = replacement
        var provisional = terminal
        if (tail.isNotEmpty() && terminal != null) {
            val spaces = updated.takeLastWhile { it.isWhitespace() }
            val suffix = terminal + spaces
            if (updated.endsWith(suffix)) updated = updated.dropLast(suffix.length) + spaces
            provisional = null
        }
        return Plan(original + tail, updated + tail, provisional, tail)
    }
}
