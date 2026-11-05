package dev.offline.smartkeyboard.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Optional, research-only evaluation of GERA .m2 school essay corrections.
 *
 * GERA source files are downloaded transiently in GitHub Actions and NEVER
 * bundled or committed, as the repository does not declare a redistribution
 * license. Only aggregate test counts are printed.
 */
class GeraExternalEvaluationTest {
    private fun asset(name: String): Sequence<String> {
        val path = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull(File::isFile) ?: error("Missing app asset $name")
        return path.readLines().asSequence()
    }

    private val original by lazy { FastCorrector.parseDictionary(asset("ru_words.txt")) }
    private val baseline by lazy {
        BlockSearchCorrector(
            original + FastCorrector.parseDictionary(asset("ru_words_leeds_10k.txt")),
            ContextRanker.parse(asset("ru_bigrams.txt")),
            GrammarHints.parse(asset("ru_verb_forms.txt")),
            original.map { it.word }.toSet()
        )
    }
    private val trained by lazy {
        BlockSearchCorrector(
            original + FastCorrector.parseDictionary(asset("ru_words_leeds_10k.txt")) +
                FastCorrector.parseDictionary(asset("ru_gec_wordforms.txt")),
            ContextRanker.parse(asset("ru_gec_bigrams.txt") + asset("ru_bigrams.txt")),
            GrammarHints.parse(asset("ru_verb_forms.txt")),
            original.map { it.word }.toSet(),
            CorpusTypos.parse(asset("ru_rlc_repeated_typos.txt"))
        )
    }
    private val word = Regex("^[а-яё]{3,22}$")
    private data class Counts(var correct: Int = 0, var wrong: Int = 0, var top5: Int = 0)

    private fun samples(file: File): List<Pair<String, String>> {
        val samples = LinkedHashSet<Pair<String, String>>()
        var tokens = emptyList<String>()
        for (line in file.readLines()) {
            if (line.startsWith("S ")) {
                tokens = line.substring(2).trim().split(Regex("\\s+"))
            } else if (line.startsWith("A ")) {
                val parts = line.split("|||")
                if (parts.size < 3 || parts[1] !in listOf("S:ORTH", "S:TYPO")) continue
                val indices = parts[0].removePrefix("A ").trim()
                    .split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                if (indices.size != 2 || indices[1] != indices[0] + 1) continue
                val source = tokens.getOrNull(indices[0])?.lowercase() ?: continue
                val target = parts[2].trim().lowercase()
                if (source != target && word.matches(source) && word.matches(target)) {
                    samples += source to target
                }
            }
        }
        return samples.toList()
    }

    private fun evaluate(engine: BlockSearchCorrector, samples: List<Pair<String, String>>): Counts {
        val out = Counts()
        for ((typed, intended) in samples) {
            val d = engine.decide(typed)
            if (d.text == intended) out.correct++
            else if (d.changed) out.wrong++
            if (engine.suggestions(typed, limit = 5).any { it.word == intended })
                out.top5++
        }
        return out
    }

    @Test fun evaluateExternalGeraDevAndTestWithoutPackagingCorpus() {
        val files = listOf("GERA.dev.m2", "GERA.test.m2")
        for (filename in files) {
            val source = listOf(File("data/raw/$filename"),
                File("android/offline-smart-keyboard/data/raw/$filename"))
                .firstOrNull(File::isFile)
            if (source == null) {
                println("GERA_EVAL_SKIPPED file=$filename reason=source-not-downloaded")
                continue
            }
            val cases = samples(source)
            assertTrue("GERA corpus import failed: $filename", cases.size >= 60)
            val before = evaluate(baseline, cases)
            val after = evaluate(trained, cases)
            println("GERA_EXTERNAL_EVAL file=$filename cases=${cases.size} " +
                "baseline_right=${before.correct} baseline_wrong=${before.wrong} " +
                "baseline_top5=${before.top5} " +
                "trained_right=${after.correct} trained_wrong=${after.wrong} " +
                "trained_top5=${after.top5}")
        }
    }
}
