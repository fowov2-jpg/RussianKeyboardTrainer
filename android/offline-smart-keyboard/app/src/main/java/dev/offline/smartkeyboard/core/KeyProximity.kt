package dev.offline.smartkeyboard.core

import kotlin.math.abs

/**
 * Weighted keyboard typo distance for the visible ЙЦУКЕН layout.
 *
 * Adjacent touch-target substitutions are cheaper than distant letters. This
 * compares actual letters, not just the hidden T9 block codes. All processing
 * happens locally; no touch coordinates or typed words are persisted.
 */
internal object KeyProximity {
    private data class Position(val x: Double, val y: Int)
    private val positions: Map<Char, Position> = buildMap {
        listOf(
            "йцукенгшщзхъ",
            "фывапролджэ",
            "ячсмитьбю"
        ).forEachIndexed { row, letters ->
            val offset = when (row) {
                0 -> 0.0
                1 -> 0.5
                else -> 1.5
            }
            letters.forEachIndexed { i, ch -> put(ch, Position(offset + i, row)) }
        }
        put('ё', Position(4.0, 0))
    }

    private fun substitute(a: Char, b: Char): Double {
        if (a == b) return 0.0
        val x = positions[a] ?: return 1.0
        val y = positions[b] ?: return 1.0
        return if (abs(x.x - y.x) <= 1.5 && abs(x.y - y.y) <= 1) 0.42 else 1.0
    }

    // Optimal string alignment: neighboring substitutions cost 0.42,
    // one accidental swap costs 0.65, insertion/deletion costs 1.
    fun distance(a: String, b: String): Double {
        if (a.isEmpty()) return b.length.toDouble()
        if (b.isEmpty()) return a.length.toDouble()
        var beforePrevious = DoubleArray(b.length + 1)
        var previous = DoubleArray(b.length + 1) { it.toDouble() }
        var current = DoubleArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i.toDouble()
            for (j in 1..b.length) {
                var cost = minOf(
                    previous[j] + 1.0,
                    current[j - 1] + 1.0,
                    previous[j - 1] + substitute(a[i - 1], b[j - 1])
                )
                if (i > 1 && j > 1 &&
                    a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = minOf(cost, beforePrevious[j - 2] + 0.65)
                }
                current[j] = cost
            }
            val swap = beforePrevious
            beforePrevious = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
