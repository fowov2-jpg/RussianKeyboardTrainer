package dev.russiankeyboard.prototype

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sentence-split-trained backoff n-grams. Scores are not calibrated confidence. */
class ContextLanguageModel(buffer: ByteBuffer) {
    private val data = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    private val count: Int
    private val values: Int
    private val bytes: Int
    init {
        require(data.limit() >= 16 && String(ByteArray(8) { data.get(it) }, Charsets.US_ASCII) == "RSKCTX01")
        count = data.getInt(8)
        require(count in 1..2_000_000)
        values = 12 + (count + 1) * 4
        bytes = values + count * 4
        require(bytes <= data.limit())
        var previous = 0
        for (i in 0..count) {
            val next = data.getInt(12 + i * 4)
            require(next >= previous && (i != 0 || next == 0))
            previous = next
        }
        require(bytes.toLong() + previous == data.limit().toLong())
    }
    private fun lookup(key: String): Double? {
        val input = key.toByteArray(Charsets.UTF_8)
        var low = 0; var high = count - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val start = data.getInt(12 + mid * 4)
            val length = data.getInt(12 + (mid + 1) * 4) - start
            var cmp = 0
            for (i in 0 until minOf(length, input.size)) {
                cmp = (data.get(bytes + start + i).toInt() and 255) - (input[i].toInt() and 255)
                if (cmp != 0) break
            }
            if (cmp == 0) cmp = length - input.size
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return data.getFloat(values + mid * 4).toDouble().takeIf { it.isFinite() }
            }
        }
        return null
    }
    fun logProbability(left: List<String>, word: String): Double {
        if (left.size >= 2) lookup(left.takeLast(2).joinToString(" ") + " " + word)?.let { return it }
        if (left.isNotEmpty()) lookup(left.last() + " " + word)?.let { return it - 0.7 }
        return (lookup(word) ?: -16.0) - 1.4
    }
    fun score(left: List<String>, word: String, right: List<String>): Double {
        var value = logProbability(left, word)
        if (right.isNotEmpty()) value += 0.7 * logProbability(left + word, right[0])
        if (right.size >= 2) value += 0.35 * logProbability(listOf(word, right[0]), right[1])
        return value
    }
}
