package dev.offline.smartkeyboard.core

/**
 * Nine *letter* shards (digits 1..9), selected by the first hidden T9 code.
 *
 * Normal lookups stay in one shard. When the first key was mistyped, separate
 * wildcard maps allow a bounded cross-block recovery without scanning words.
 * This index is immutable after startup: the service publishes it atomically.
 */
internal class NineBlockIndex {
    private class Shard {
        val exact = HashMap<String, MutableList<FastCorrector.Entry>>()
        val oneMismatch = HashMap<String, MutableList<FastCorrector.Entry>>()
        val twoMismatches = HashMap<String, MutableList<FastCorrector.Entry>>()
        val prefixes = HashMap<String, MutableList<FastCorrector.Entry>>()
        var words = 0
    }

    private val shards = Array(9) { Shard() }
    private val firstMismatch = HashMap<String, MutableList<FastCorrector.Entry>>()
    private val firstDoubleMismatch = HashMap<String, MutableList<FastCorrector.Entry>>()

    private fun shard(code: String): Shard? {
        val head = code.firstOrNull() ?: return null
        if (head !in '1'..'9') return null
        return shards[head - '1']
    }

    private fun mask(code: String, i: Int): String =
        code.substring(0, i) + '*' + code.substring(i + 1)

    private fun mask2(code: String, i: Int, j: Int): String {
        val chars = code.toCharArray()
        chars[i] = '*'
        chars[j] = '*'
        return String(chars)
    }

    fun add(entry: FastCorrector.Entry, code: String, expanded: Boolean) {
        if (code.length !in 2..24) return
        val block = shard(code) ?: return
        block.words++
        block.exact.getOrPut(code) { ArrayList() }.add(entry)

        for (i in code.indices) {
            val key = mask(code, i)
            val map = if (i == 0) firstMismatch else block.oneMismatch
            map.getOrPut(key) { ArrayList() }.add(entry)
        }
        if (expanded && code.length in 4..14) {
            for (i in 0 until code.length - 1) {
                for (j in i + 1 until code.length) {
                    val key = mask2(code, i, j)
                    val map = if (i == 0) firstDoubleMismatch else block.twoMismatches
                    map.getOrPut(key) { ArrayList() }.add(entry)
                }
            }
        }
        // Bounded prefix cache: only 4 levels, at most 5 frequent words
        // per prefix. This supports narrowing immediately after each key.
        for (length in 1..minOf(code.length, 4)) {
            val prefix = code.substring(0, length)
            val best = block.prefixes.getOrPut(prefix) { ArrayList() }
            if (best.any { it.word == entry.word }) continue
            best.add(entry)
            best.sortWith(compareByDescending<FastCorrector.Entry> { it.frequency }
                .thenBy { it.word })
            if (best.size > 5) best.removeAt(best.lastIndex)
        }
    }

    fun collect(code: String, accept: (FastCorrector.Entry) -> Unit) {
        val block = shard(code) ?: return
        block.exact[code]?.forEach(accept)
        for (i in code.indices) {
            val map = if (i == 0) firstMismatch else block.oneMismatch
            map[mask(code, i)]?.forEach(accept)
        }
        if (code.length in 4..14) {
            for (i in 0 until code.length - 1) {
                for (j in i + 1 until code.length) {
                    val map = if (i == 0) firstDoubleMismatch else block.twoMismatches
                    map[mask2(code, i, j)]?.forEach(accept)
                }
            }
        }
    }

    /** Direct lookup after removing an accidentally inserted digit. */
    fun collectExact(code: String, accept: (FastCorrector.Entry) -> Unit) {
        shard(code)?.exact?.get(code)?.forEach(accept)
    }

    /** One code substitution, including an incorrect very first block. */
    fun collectOneMismatch(code: String, accept: (FastCorrector.Entry) -> Unit) {
        val block = shard(code) ?: return
        for (i in code.indices) {
            val indexed = if (i == 0) firstMismatch else block.oneMismatch
            indexed[mask(code, i)]?.forEach(accept)
        }
    }

    /** Candidates for the first 1-4 typed blocks, already frequency-ranked. */
    fun completions(digits: String, limit: Int = 3): List<FastCorrector.Entry> {
        if (digits.isEmpty() || limit <= 0 || digits.length > 4) return emptyList()
        return shard(digits)?.prefixes?.get(digits).orEmpty().take(limit)
    }

    fun blockSizes(): Map<Char, Int> =
        ('1'..'9').associateWith { shards[it - '1'].words }
}
