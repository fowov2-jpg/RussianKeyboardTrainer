package dev.offline.smartkeyboard.core

import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic synthetic holdout benchmark.
 * No private messages or typed text are recorded, uploaded or included.
 *
 * Reports useful metrics without imposing unreliable JVM latency thresholds
 * on busy CI runners. Real Android IME input-to-display latency is NOT measured.
 */
class SyntheticEvaluationTest {
    private fun asset(name: String): Sequence<String> {
        val file = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name")
        ).firstOrNull { it.isFile } ?: error("Missing bundled asset $name")
        return file.readLines().asSequence()
    }

    private val dictionary by lazy { FastCorrector.parseDictionary(asset("ru_words.txt")) }
    private val engine by lazy {
        BlockSearchCorrector(
            dictionary,
            ContextRanker.parse(asset("ru_bigrams.txt")),
            GrammarHints.parse(asset("ru_verb_forms.txt"))
        )
    }

    @Test fun reportSyntheticTyposAndPreventAccidentalCleanWordChanges() {
        val common = dictionary
            .filter { it.word.length in 6..13 && it.frequency >= 900 &&
                it.word.all { ch -> ch in 'а'..'я' || ch == 'ё' } }
            .distinctBy { it.word }
            .sortedBy { it.word }
            .shuffled(Random(202612))
            .take(140)
        assertTrue("Need a nontrivial test dictionary", common.size >= 60)
        val known = dictionary.map { it.word }.toSet()
        val records = ArrayList<Pair<String, String>>()
        for (entry in common) {
            val word = entry.word
            val middle = word.length / 2
            val missingOne = word.removeRange(middle, middle + 1)
            if (missingOne !in known) records += missingOne to word
            if (word.length >= 8) {
                val missingTwo = word.removeRange(middle, middle + 1)
                    .removeRange(1, 2)
                if (missingTwo !in known) records += missingTwo to word
            }
            if (word[middle] != word[middle - 1]) {
                val chars = word.toCharArray()
                val swapped = chars[middle]
                chars[middle] = chars[middle - 1]
                chars[middle - 1] = swapped
                val mistake = String(chars)
                if (mistake !in known) records += mistake to word
            }
        }

        var corrected = 0
        var missed = 0
        var wronglyChanged = 0
        val latencies = ArrayList<Long>(records.size)
        for ((mistake, intended) in records) {
            val start = System.nanoTime()
            val decision = engine.decide(mistake)
            latencies += System.nanoTime() - start
            when {
                decision.text == intended -> corrected++
                decision.changed -> wronglyChanged++
                else -> missed++
            }
        }
        var changedClean = 0
        for (entry in common) {
            if (engine.decide(entry.word).changed) changedClean++
        }
        assertEquals("Known-correct words must stay intact", 0, changedClean)
        assertTrue("Synthetic sample too small", records.size > 100)
        assertEquals(records.size, corrected + missed + wronglyChanged)

        latencies.sort()
        val p95Micros = latencies[(latencies.size * 95 / 100).coerceAtMost(latencies.lastIndex)] / 1000.0
        val precision = if (corrected + wronglyChanged > 0)
            corrected.toDouble() / (corrected + wronglyChanged) else 0.0
        val recall = corrected.toDouble() / records.size
        println(
            String.format(Locale.US,
                "SYNTHETIC_OFFLINE_EVAL cases=%d corrected=%d missed=%d wrong=%d clean_changed=%d " +
                    "precision=%.3f recall=%.3f p95_lookup_us=%.1f (CI JVM only, not Android)",
                records.size, corrected, missed, wronglyChanged, changedClean,
                precision, recall, p95Micros
            )
        )
    }
}
