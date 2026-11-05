package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionCorrectionsTest {
    private val dictionary by lazy {
        val source = listOf(
            File("src/main/assets/ru_words.txt"),
            File("app/src/main/assets/ru_words.txt")
        ).firstOrNull { it.exists() }
            ?: error("Real production dictionary is missing")
        FastCorrector.parseDictionary(source.readLines().asSequence())
    }
    private val engine by lazy { BlockSearchCorrector(dictionary) }

    @Test fun realDictionaryContainsFrequentRussianWords() {
        assertTrue(dictionary.any { it.word == "исправление" })
        assertTrue(dictionary.any { it.word == "пропало" })
        assertTrue(dictionary.any { it.word == "сейчас" })
    }

    @Test fun realChatTyposAreCorrected() {
        assertEquals("Сейчас", engine.decide("Сецчас").text)
        assertEquals("исправление", engine.decide("испрачление").text)
        // "пропалр" could mean "пропало" or "пропали"; show candidates
        // instead of silently guessing the intended meaning.
        assertEquals("пропалр", engine.decide("пропалр").text)
        val alternatives = engine.suggestions("пропалр", limit = 5).map { it.word }
        assertTrue(alternatives.contains("пропало"))
        assertTrue(alternatives.contains("пропали"))
    }

    @Test fun correctlyTypedWordsAndAmbiguousShortCodesAreNotChanged() {
        assertEquals("сейчас", engine.decide("сейчас").text)
        assertEquals("исправление", engine.decide("исправление").text)
        assertEquals("нп", engine.decide("нп").text)
    }
}
