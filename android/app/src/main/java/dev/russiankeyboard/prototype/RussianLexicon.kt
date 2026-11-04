package dev.russiankeyboard.prototype

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only index: strings are decoded only for a result, never all at once. */
class RussianLexicon(buffer: ByteBuffer) {
    data class Candidate(val word: String, val automatic: Boolean)

    private val data = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    private data class Table(val count: Int, val offsets: Int, val values: Int,
                             val flags: Int, val bytes: Int, val end: Int)
    private val words: Table
    private val known: Table
    private val typos: Table

    init {
        require(data.limit() >= 8 && (0 until 8).map { data.get(it).toInt().toChar() }
            .joinToString("") == "RSKLEX02") { "Unsupported lexicon format" }
        words = table(8, false)
        known = table(words.end, false)
        typos = table(known.end, true)
        require(typos.end == data.limit()) { "Trailing or truncated lexicon data" }
    }

    val wordCount: Int get() = words.count
    val typoCount: Int get() = typos.count

    private fun table(start: Int, withValues: Boolean): Table {
        require(start >= 0 && start.toLong() + 4 <= data.limit())
        val count = data.getInt(start)
        require(count in 1..2_000_000)
        val offsets = start + 4
        val values = offsets.toLong() + (count.toLong() + 1) * 4
        val flags = values + if (withValues) count.toLong() * 4 else 0
        val bytes = flags + if (withValues) count else 0
        require(bytes <= data.limit())
        var previous = 0
        for (i in 0..count) {
            val next = data.getInt(offsets + i * 4)
            require(next >= previous && (i != 0 || next == 0))
            previous = next
        }
        val end = bytes + previous
        require(end <= data.limit())
        if (withValues) {
            for (i in 0 until count) {
                require(data.getInt(values.toInt() + i * 4) in 0 until words.count)
                require((data.get(flags.toInt() + i).toInt() and 0xff) <= 11)
            }
        }
        return Table(count, offsets, values.toInt(), flags.toInt(), bytes.toInt(), end.toInt())
    }

    private fun find(table: Table, key: ByteArray): Int {
        var low = 0
        var high = table.count - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val start = data.getInt(table.offsets + mid * 4)
            val length = data.getInt(table.offsets + (mid + 1) * 4) - start
            var comparison = 0
            for (i in 0 until minOf(length, key.size)) {
                comparison = (data.get(table.bytes + start + i).toInt() and 0xff) -
                    (key[i].toInt() and 0xff)
                if (comparison != 0) break
            }
            if (comparison == 0) comparison = length - key.size
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun word(id: Int): String {
        val start = data.getInt(words.offsets + id * 4)
        val size = data.getInt(words.offsets + (id + 1) * 4) - start
        val result = ByteArray(size) { data.get(words.bytes + start + it) }
        return String(result, Charsets.UTF_8)
    }

    fun isKnown(word: String): Boolean = find(known, word.toByteArray(Charsets.UTF_8)) >= 0

    /** A sampled typo can be a suggestion without qualifying for auto-replace. */
    fun lookup(typed: String): Candidate? {
        if (typed.length !in 4..28) return null
        val key = typed.toByteArray(Charsets.UTF_8)
        if (find(known, key) >= 0) return null
        val index = find(typos, key)
        if (index < 0) return null
        val id = data.getInt(typos.values + index * 4)
        return Candidate(word(id), (data.get(typos.flags + index).toInt() and 1) != 0)
    }
}
