package dev.offline.smartkeyboard.core

/**
 * Offline recovery for noisy 1..9 digit codes.
 *
 * A normal code searches exactly one of the nine first-digit shards.
 * Wildcard tables recover wrong digits, while deletion signatures recover
 * missing taps without expanding all 9 possible inserted digits. Extra taps
 * and neighboring swaps are checked with bounded exact-code probes.
 *
 * It never decides what to commit. The letter-aware corrector ranks results
 * and refuses ambiguous automatic replacements.
 */
internal class SmartT9Recovery(private val shards: NineBlockIndex) {
    private val oneMissing = HashMap<String, MutableList<FastCorrector.Entry>>()
    private val twoMissing = HashMap<String, MutableList<FastCorrector.Entry>>()

    fun add(entry: FastCorrector.Entry, code: String, highPriority: Boolean) {
        if (code.length !in 3..18) return

        // O(length) index entries per word; no combinatorial query expansion.
        for (i in code.indices) {
            val key = code.removeRange(i, i + 1)
            val bucket = oneMissing.getOrPut(key) { ArrayList() }
            if (bucket.lastOrNull() !== entry) bucket.add(entry)
        }
        // Two missing keystrokes: index only frequent / curated vocabulary.
        if (!highPriority || code.length !in 6..14) return
        for (i in 0 until code.length - 1) {
            for (j in i + 1 until code.length) {
                val key = buildString(code.length - 2) {
                    for (k in code.indices) if (k != i && k != j) append(code[k])
                }
                val bucket = twoMissing.getOrPut(key) { ArrayList() }
                if (bucket.lastOrNull() !== entry) bucket.add(entry)
            }
        }
    }

    /** Progressive retrieval. The caller deduplicates words and limits ranking work. */
    fun collect(code: String, add: (FastCorrector.Entry) -> Unit) {
        if (code.length !in 2..24 || code.any { it !in '1'..'9' }) return

        // 0-2 replaced digits, including an incorrect first digit:
        // 264236 -> 564236 -> привет.
        shards.collect(code, add)

        // The user omitted one/two keys:
        // 64236 -> 564236, by a preindexed code with its first digit removed.
        oneMissing[code]?.forEach(add)
        if (code.length >= 4) twoMissing[code]?.forEach(add)

        // The user inserted one extra digit: remove one of the input digits.
        for (i in code.indices) {
            val shortened = code.removeRange(i, i + 1)
            shards.collectExact(shortened, add)
        }

        // Neighboring digits arrived in reversed order.
        for (i in 0 until code.length - 1) {
            if (code[i] == code[i + 1]) continue
            val chars = code.toCharArray()
            chars[i] = code[i + 1]
            chars[i + 1] = code[i]
            shards.collectExact(String(chars), add)
        }

        // Combined extra keystroke and wrong block: only when reasonably short.
        // Do not recursively enumerate 9^length possible strings.
        if (code.length in 5..12) {
            for (i in code.indices) {
                shards.collectOneMismatch(code.removeRange(i, i + 1), add)
            }
        }
    }
}
