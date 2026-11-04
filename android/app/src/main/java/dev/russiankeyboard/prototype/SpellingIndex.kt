package dev.russiankeyboard.prototype

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/** Mapped trie with bounded Damerau-Levenshtein search, including unseen errors. */
class SpellingIndex(buffer: ByteBuffer) {
    data class Match(val word: String, val distance: Int, val rank: Int)
    private val data = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    val wordCount: Int
    private val offsets = 12
    private val bytes: Int
    private val nodes: Int
    private val nodeCount: Int

    init {
        require(data.limit() >= 16 && String(ByteArray(8) { data.get(it) }, Charsets.US_ASCII) == "RSKTRI01")
        wordCount = data.getInt(8)
        require(wordCount in 1..200_000)
        bytes = offsets + (wordCount + 1) * 4
        require(bytes + 4 <= data.limit())
        var previous = 0
        for (i in 0..wordCount) {
            val next = data.getInt(offsets + i * 4)
            require(next >= previous && (i != 0 || next == 0))
            previous = next
        }
        val countAt = bytes.toLong() + previous
        require(countAt + 4 <= data.limit())
        nodeCount = data.getInt(countAt.toInt())
        nodes = countAt.toInt() + 4
        require(nodeCount in 1..2_000_000 && nodes.toLong() + nodeCount.toLong() * 20 == data.limit().toLong())
        for (i in 0 until nodeCount) {
            require(field(i, 0) in 0..65535)
            require(field(i, 1) in -1 until nodeCount && field(i, 2) in -1 until nodeCount)
            // Children and sibling chains must move forward: no cycles from corrupt data.
            require(field(i, 1) == -1 || field(i, 1) > i)
            require(field(i, 2) == -1 || field(field(i, 2), 0) > field(i, 0))
            require(field(i, 3) in -1 until wordCount && field(i, 4) in 0 until wordCount)
        }
    }

    private fun field(node: Int, column: Int) = data.getInt(nodes + node * 20 + column * 4)
    private fun word(id: Int): String {
        val start = data.getInt(offsets + id * 4)
        val length = data.getInt(offsets + (id + 1) * 4) - start
        return String(ByteArray(length) { data.get(bytes + start + it) }, Charsets.UTF_8)
    }
    private fun prefixNode(prefix: String): Int {
        var node = 0
        for (char in prefix) {
            var child = field(node, 1)
            while (child != -1 && field(child, 0) != char.code) child = field(child, 2)
            if (child == -1) return -1
            node = child
        }
        return node
    }
    fun contains(value: String): Boolean {
        val node = prefixNode(value)
        return node >= 0 && field(node, 3) >= 0
    }
    fun hasPrefix(value: String): Boolean = value.isNotEmpty() && value.length <= 28 && prefixNode(value) >= 0
    fun hasContinuation(value: String): Boolean {
        if (value.isEmpty() || value.length > 28) return false
        val node = prefixNode(value)
        return node >= 0 && field(node, 1) >= 0
    }

    fun complete(prefix: String, limit: Int = 3): List<Match> {
        if (prefix.isEmpty() || prefix.length > 28 || limit !in 1..16) return emptyList()
        val start = prefixNode(prefix)
        if (start < 0) return emptyList()
        val queue = PriorityQueue<Int>(compareBy { field(it, 4) })
        queue.add(start)
        val result = mutableListOf<Match>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3000 && result.size < limit) {
            val node = queue.remove()
            val id = field(node, 3)
            if (id >= 0) {
                val value = word(id)
                if (value != prefix) result.add(Match(value, 0, id))
            }
            var child = field(node, 1)
            while (child >= 0) { queue.add(child); child = field(child, 2) }
        }
        return result
    }

    fun search(typed: String, maximumDistance: Int = 2, limit: Int = 32): List<Match> {
        if (typed.length !in 2..28 || maximumDistance !in 1..2 || limit !in 1..64) return emptyList()
        val result = mutableListOf<Match>()
        var visited = 0
        fun walk(node: Int, previous: IntArray, beforePrevious: IntArray?, previousChar: Char) {
            if (++visited > 30_000 || Thread.currentThread().isInterrupted) return
            val char = field(node, 0).toChar()
            val current = IntArray(typed.length + 1)
            current[0] = previous[0] + 1
            var minimum = current[0]
            for (i in 1..typed.length) {
                current[i] = minOf(current[i - 1] + 1, previous[i] + 1,
                    previous[i - 1] + if (typed[i - 1] == char) 0 else 1)
                if (i > 1 && beforePrevious != null && char == typed[i - 2] && previousChar == typed[i - 1]) {
                    current[i] = minOf(current[i], beforePrevious[i - 2] + 1)
                }
                minimum = minOf(minimum, current[i])
            }
            val id = field(node, 3)
            if (id >= 0 && current[typed.length] <= maximumDistance) {
                result.add(Match(word(id), current[typed.length], id))
            }
            if (minimum <= maximumDistance) {
                var child = field(node, 1)
                while (child >= 0 && visited <= 30_000) {
                    walk(child, current, previous, char)
                    child = field(child, 2)
                }
            }
        }
        var child = field(0, 1)
        val initial = IntArray(typed.length + 1) { it }
        while (child >= 0 && visited <= 30_000) {
            walk(child, initial, null, '\u0000'); child = field(child, 2)
        }
        return result.sortedWith(compareBy<Match> { it.distance }.thenBy { it.rank }).take(limit)
    }
}
