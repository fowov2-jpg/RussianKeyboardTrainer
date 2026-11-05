package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Run offline evaluations against held-out *real* annotation pairs.
 *
 * IMPORTANT: GEC and RLC are mostly learner/grammatical errors, not physical
 * keyboard slips. Isolated word scores are diagnostic, NOT an estimate of
 * full-sentence GEC accuracy or everyday keyboard accuracy.
 *
 * No personal source sentences or network access are required for this test.
 */
class CorpusTrainingEvalTest {
    private fun read(name: String, test: Boolean = false): Sequence<String> {
        val folder = if (test) "src/test/assets" else "src/main/assets"
        val file = listOf(File(folder, name), File("app/$folder", name))
            .firstOrNull { it.isFile } ?: error("Missing corpus-derived asset $name")
        return file.readLines().asSequence()
    }
    private val original by lazy { FastCorrector.parseDictionary(read("ru_words.txt")) }
    private val leeds by lazy { FastCorrector.parseDictionary(read("ru_words_leeds_10k.txt")) }
    private val learned by lazy { FastCorrector.parseDictionary(read("ru_gec_wordforms.txt")) }
    private val explicit by lazy { ContextRanker.parse(read("ru_bigrams.txt")) }
    private val grammar by lazy { GrammarHints.parse(read("ru_verb_forms.txt")) }
    private val context by lazy {
        ContextRanker.parse(
            (read("ru_gec_bigrams.txt") + read("ru_bigrams.txt")).asSequence()
        )
    }
    private val typos by lazy { CorpusTypos.parse(read("ru_rlc_repeated_typos.txt")) }
    private val priority by lazy { original.map { it.word }.toSet() }

    private val baseline by lazy {
        BlockSearchCorrector(original + leeds, explicit, grammar, priority)
    }
    private val trained by lazy {
        BlockSearchCorrector(original + leeds + learned, context, grammar, priority, typos)
    }

    @Test fun packagedCorpusFeaturesArePresentAndCorrectWordsArePreserved() {
        assertTrue("Missing GEC wordforms", learned.size >= 1500)
        assertTrue("Missing repeated RLC corrections", typos.size() >= 100)
        assertTrue("Full lexicon missing", trained.dictionarySize() >= 11500)
        assertTrue(trained.dictionarySize() > baseline.dictionarySize())
        for (word in listOf(
            "привет", "сегодня", "работа", "работу", "учится", "учиться",
            "происходить", "клавиатура", "пропало", "хочу", "хотим"
        )) {
            assertEquals("Known correct word must survive: $word",
                word, trained.decide(word).text)
        }
        assertEquals("учится", trained.decide("учится").text)
        assertEquals("учиться", trained.decide("учиться").text)
    }

    @Test fun rlcTrainingIsOnlyAProposalNotAnUnconditionalChange() {
        assertEquals("сейчас", typos.candidate("сеичас"))
        assertEquals(null, typos.candidate("сейчас"))
        assertEquals("сейчас", trained.decide("сейчас").text)
    }

    private data class Stats(
        val cases: Int, val right: Int, val wrong: Int, val suggested: Int, val unchanged: Int
    )
    private fun evaluate(engine: BlockSearchCorrector, pairs: List<Pair<String, String>>): Stats {
        var right = 0
        var wrong = 0
        var suggested = 0
        var unchanged = 0
        for ((raw, expected) in pairs) {
            val answer = engine.decide(raw)
            if (answer.text == expected) right++
            else if (answer.changed) wrong++
            else unchanged++
            if (answer.suggestions.any { it.word == expected } ||
                engine.suggestions(raw, limit = 5).any { it.word == expected })
                suggested++
        }
        return Stats(pairs.size, right, wrong, suggested, unchanged)
    }

    private fun testPairs(name: String): List<Pair<String, String>> =
        read(name, test = true).mapNotNull { line ->
            if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
            val fields = line.trim().split('\t')
            if (fields.size < 2) return@mapNotNull null
            val source = fields[0].lowercase(Locale.forLanguageTag("ru-RU"))
            val target = fields[1].lowercase(Locale.forLanguageTag("ru-RU"))
            if (source == target) null else source to target
        }.toList()

    @Test fun printHeldOutRussianGecAndRlcWordLevelBenchmarks() {
        for (name in listOf("gec_holdout.tsv", "rlc_test_holdout.tsv")) {
            val pairs = testPairs(name)
            assertTrue("Not enough withheld examples in $name", pairs.size >= 60)
            val before = evaluate(baseline, pairs)
            val after = evaluate(trained, pairs)
            println("REAL_CORPUS_EVAL file=$name count=${pairs.size} " +
                "baseline_right=${before.right} baseline_wrong=${before.wrong} " +
                "baseline_top5=${before.suggested} " +
                "trained_right=${after.right} trained_wrong=${after.wrong} " +
                "trained_top5=${after.suggested} trained_unchanged=${after.unchanged}")
            // The goal is reproducible measurement, not forcing a synthetic
            // dataset improvement by incorrectly rewriting valid grammar forms.
            assertEquals(pairs.size, after.right + after.wrong + after.unchanged)
        }
    }
}
