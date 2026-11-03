package dev.russiankeyboard.prototype

/**
 * Byte-for-byte compatible with src/rsk_trainer/tokenizer.py for Unicode
 * code points. Unknown characters map to the <UNK> token, not to a guess.
 */
class RankerCharTokenizer(private val vocabulary: List<String>, val maxChars: Int) {
    init {
        require(maxChars >= 52) { "maxChars must leave room for both 24-char words" }
        require(vocabulary.take(4) == listOf("<PAD>", "<UNK>", "<CLS>", "<SEP>"))
        require(vocabulary.distinct().size == vocabulary.size)
    }

    private val index: Map<String, Int> = vocabulary.withIndex().associate { it.value to it.index }

    data class Encoded(val ids: LongArray, val mask: LongArray)

    private fun splitUnicode(value: String): List<String> =
        value.codePoints().toArray().map { String(Character.toChars(it)) }

    fun encodePair(context: String, typed: String, candidate: String): Encoded {
        val t = splitUnicode(typed).take(24)
        val c = splitUnicode(candidate).take(24)
        val left = splitUnicode(context)
        val contextBudget = maxOf(0, maxChars - 4 - t.size - c.size)
        val ctx = if (contextBudget == 0) emptyList() else left.takeLast(contextBudget)
        val sequence = ArrayList<Long>(maxChars)
        sequence.add(2L)
        for (part in listOf(ctx, t, c)) {
            for (ch in part) sequence.add((index[ch] ?: 1).toLong())
            sequence.add(3L)
        }
        val ids = LongArray(maxChars)
        val mask = LongArray(maxChars)
        for (i in 0 until minOf(maxChars, sequence.size)) {
            ids[i] = sequence[i]
            mask[i] = 1L
        }
        return Encoded(ids, mask)
    }
}
