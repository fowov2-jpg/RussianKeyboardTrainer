package dev.offline.smartkeyboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarContextTest {
    private fun asset(name: String): Sequence<String> {
        val file = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name")
        ).firstOrNull { it.isFile } ?: error("Missing bundled file: $name")
        return file.readLines().asSequence()
    }

    private val words by lazy { FastCorrector.parseDictionary(asset("ru_words.txt")) }
    private val hints by lazy { GrammarHints.parse(asset("ru_verb_forms.txt")) }
    private val context by lazy { ContextRanker.parse(asset("ru_bigrams.txt")) }
    private val engine by lazy { BlockSearchCorrector(words, context, hints) }

    @Test fun grammarHintPrefersCorrectAgreementButDoesNotSilentlyRewriteValidWord() {
        assertEquals("хотим", hints.suggestion("мы", "хочу"))
        assertEquals("хочешь", hints.suggestion("ты", "хочу"))
        assertEquals(null, hints.suggestion("я", "хочу"))
        val decision = engine.decide("хочу", "мы")
        assertEquals("хочу", decision.text)
        assertEquals(false, decision.changed)
        assertEquals("хотим", decision.suggestions.first().word)
    }

    @Test fun typoIsCorrectedWithLocalPhraseContext() {
        assertEquals("хотим", engine.decide("хотми", "мы").text)
        assertEquals("хочу", engine.decide("хочу", "я").text)
        assertEquals("знаешь", engine.suggestions("знаю", previous = "ты").first().word)
    }

    @Test fun noSurprisingGrammarSuggestionsWithoutDirectPronoun() {
        assertEquals(null, hints.suggestion(null, "хочу"))
        assertEquals(null, hints.suggestion("сегодня", "хочу"))
        assertEquals("хочу", engine.decide("хочу", "сегодня").text)
    }

    @Test fun grammarFileTargetsArePresentInDictionary() {
        assertTrue(words.any { it.word == "хотим" })
        assertTrue(words.any { it.word == "хочу" })
        assertTrue(words.any { it.word == "думаем" })
    }
}
