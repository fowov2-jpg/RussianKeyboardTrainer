package dev.russiankeyboard.prototype

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exact read-only Darts/DAWG membership, including normal е/ё spelling variants.
 * Transition layout adapted from DAWG-Python, © Mikhail Korobov (MIT);
 * see context/DAWG-Python-LICENSE.txt. Dictionary: OpenCorpora, CC BY-SA 3.0.
 */
class MorphologyDictionary(buffer: ByteBuffer) {
    private val data = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private val count: Int
    init {
        require(data.limit() >= 8)
        count = data.getInt(0)
        require(count in 1..10_000_000 && 4L + count.toLong() * 4 <= data.limit())
    }
    private fun follow(index: Int, label: Int): Int {
        val unit = data.getInt(4 + index * 4)
        val offset = (unit ushr 10) shl ((unit and 512) ushr 6)
        val next = index xor offset xor label
        if (next !in 0 until count) return -1
        return if (data.getInt(4 + next * 4) and (Int.MIN_VALUE or 255) == label) next else -1
    }
    private fun follow(index: Int, char: Char): Int {
        var current = index
        for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
            current = follow(current, byte.toInt() and 255)
            if (current < 0) return -1
        }
        return current
    }
    fun contains(word: String): Boolean {
        if (word.isEmpty() || word.length > 40) return false
        var exact = 0
        for (byte in word.toByteArray(Charsets.UTF_8)) {
            exact = follow(exact, byte.toInt() and 255)
            if (exact < 0) break
        }
        if (exact >= 0 && follow(exact, 1) > 0) return true
        if ('е' !in word && 'ё' !in word) return false
        var states = listOf(0)
        for (char in word) {
            val next = linkedSetOf<Int>()
            for (state in states) {
                follow(state, char).takeIf { it >= 0 }?.let { next.add(it) }
                if (char == 'е' || char == 'ё') {
                    follow(state, if (char == 'е') 'ё' else 'е').takeIf { it >= 0 }?.let { next.add(it) }
                }
            }
            if (next.isEmpty()) return false
            states = next.take(64)
        }
        // RecordDAWG stores a payload separator after each complete word.
        return states.any { follow(it, 1) > 0 }
    }
}
