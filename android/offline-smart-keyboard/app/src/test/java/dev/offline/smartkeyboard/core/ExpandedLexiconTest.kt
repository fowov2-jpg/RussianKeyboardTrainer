package dev.offline.smartkeyboard.core

import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the exact two lexicons packaged in the APK.
 * This is a reproducible synthetic test, not an estimate of real chat quality.
 */
class ExpandedLexiconTest {
    private fun read(name: String): List<FastCorrector.Entry> {
        val file = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull(File::isFile) ?: error("Missing embedded word list $name")
        return FastCorrector.parseDictionary(file.readLines().asSequence())
    }

    private val entries by lazy {
        read("ru_words.txt") + read("ru_words_leeds_10k.txt")
    }
    private val engine by lazy { BlockSearchCorrector(entries) }

    @Test fun extendedWordCoverageAndExactWordSafety() {
        assertTrue("APK should contain at least 10,000 word entries", engine.dictionarySize() >= 10_000)
        val words = listOf(
            "привет", "сегодня", "проверим", "исправление", "происходить",
            "возможность", "развитие", "действительно", "отношение", "внимание"
        )
        for (word in words) assertEquals("Should not rewrite $word", word, engine.decide(word).text)
    }

    @Test fun evaluateSyntheticUnseenTyposAndLookupTime() {
        val existing = entries.map { it.word }.toHashSet()
        val sample = entries.asSequence()
            .filter { it.word.length in 6..12 && it.word.all { c -> c in 'а'..'я' || c == 'ё' } }
            .distinctBy { it.word }
            .map { it.word }
            .toList().shuffled(Random(130013)).take(160)
        val pairs = ArrayList<Pair<String, String>>()
        for (word in sample) {
            val pos = word.length / 2
            val dropped = word.removeRange(pos, pos + 1)
            if (dropped !in existing) pairs += dropped to word
            val chars = word.toCharArray()
            val swapped = chars[pos]; chars[pos] = chars[pos-1]; chars[pos-1] = swapped
            val typo = String(chars)
            if (typo !in existing && typo != word) pairs += typo to word
        }
        assertTrue("Synthetic holdout should contain 150+ cases", pairs.size >= 150)
        val times = ArrayList<Long>()
        var corrected = 0
        var wrong = 0
        for ((typo, original) in pairs) {
            val started = System.nanoTime()
            val guess = engine.decide(typo)
            times.add(System.nanoTime() - started)
            if (guess.text == original) corrected++
            else if (guess.changed) wrong++
        }
        times.sort()
        val p95Us = times[times.size * 95 / 100] / 1000.0
        val wrongRatio = wrong.toDouble() / pairs.size
        println(String.format(Locale.US,
            "EXTENDED_LEXICON_EVAL entries=%d cases=%d corrected=%d wrong=%d precision=%.3f p95_us=%.1f",
            engine.dictionarySize(), pairs.size, corrected, wrong,
            if (corrected + wrong > 0) corrected.toDouble() / (corrected + wrong) else 0.0,
            p95Us
        ))
        assertTrue("Avoid dangerous overcorrection on synthetic holdout: $wrong/$pairs", wrongRatio < .10)
    }
}
