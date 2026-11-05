package dev.offline.smartkeyboard.core

import java.util.Locale

/**
 * Aggregated human-annotated RLC orthographic errors.
 *
 * These are deliberately NEVER unconditional replacements: a form mistaken
 * in one context can be correct in another. The normal letter-distance,
 * frequency and competing-candidate checks still govern autocorrection.
 *
 * The bundled asset contains no original sentences or personal information.
 */
class CorpusTypos private constructor(private val proposals: Map<String, String>) {
    fun candidate(word: String): String? = proposals[word.lowercase(Locale.forLanguageTag("ru-RU"))]

    fun size(): Int = proposals.size

    companion object {
        val EMPTY = CorpusTypos(emptyMap())
        fun parse(lines: Sequence<String>): CorpusTypos {
            val choices = HashMap<String, MutableMap<String, Int>>()
            for (line in lines) {
                val trimmed = line.trim().lowercase(Locale.forLanguageTag("ru-RU"))
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size != 3) continue
                val (source, target, countRaw) = parts
                val count = countRaw.toIntOrNull() ?: continue
                if (source == target || count < 2 || count > 100_000 ||
                    source.length !in 4..22 || target.length !in 4..22 ||
                    !source.all { it in 'а'..'я' || it == 'ё' } ||
                    !target.all { it in 'а'..'я' || it == 'ё' }) continue
                choices.getOrPut(source) { HashMap() }[target] = count
            }
            val unique = LinkedHashMap<String, String>()
            for ((source, targetCounts) in choices) {
                val sorted = targetCounts.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                )
                val best = sorted.firstOrNull() ?: continue
                val next = sorted.getOrNull(1)
                // Require dominant evidence when the same typo has many targets.
                if (next == null || best.value >= next.value * 2) {
                    unique[source] = best.key
                }
            }
            return CorpusTypos(unique)
        }
    }
}
