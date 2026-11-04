package dev.offline.smartkeyboard.core

import java.util.Locale
import kotlin.math.ln

/**
 * Small explicit bigram hints. No user text is logged or uploaded.
 * Lines use: previous_word next_word weight.
 * This is NOT a corpus-trained language model.
 */
class ContextRanker private constructor(
    private val weights: Map<String, Map<String, Int>>
) {
    private val locale = Locale.forLanguageTag("ru-RU")

    fun bonus(previous: String?, candidate: String): Double {
        if (previous == null) return 0.0
        val count = weights[previous.lowercase(locale)]?.get(candidate) ?: 0
        return if (count > 0) 0.85 * ln((1 + count).toDouble()) else 0.0
    }

    companion object {
        val EMPTY = ContextRanker(emptyMap())
        fun parse(lines: Sequence<String>): ContextRanker {
            val data = LinkedHashMap<String, MutableMap<String, Int>>()
            for (line in lines) {
                val parts = line.trim().lowercase(Locale.forLanguageTag("ru-RU")).split(Regex("\\s+"))
                if (parts.size != 3 || parts[0].startsWith("#")) continue
                val weight = parts[2].toIntOrNull() ?: continue
                if (weight <= 0 || weight > 1_000_000) continue
                data.getOrPut(parts[0]) { LinkedHashMap() }[parts[1]] = weight
            }
            return ContextRanker(data)
        }
    }
}
