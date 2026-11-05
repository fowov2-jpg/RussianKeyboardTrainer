package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingLetterRecoveryTest {
    private val entries = listOf(
        "сегодня", "проверим", "исправление", "пользуюсь",
        "клавиатура", "клавиатуры", "клавиатуре", "клавиатуру",
        "привет", "привык", "работаю", "работаете"
    ).mapIndexed { i, word -> FastCorrector.Entry(word, 22000 - i * 500) }
    private val engine = BlockSearchCorrector(entries)

    @Test fun restoreTwoMissingLettersWhenUnambiguous() {
        assertEquals("сегодня", engine.decide("сгдня").text)
        assertEquals("проверим", engine.decide("прверм").text)
    }

    @Test fun restoreOneMissingLetter() {
        assertEquals("проверим", engine.decide("проврим").text)
        assertEquals("сегодня", engine.decide("сгодня").text)
    }

    @Test fun keepCorrectAndAmbiguousWords() {
        assertEquals("сегодня", engine.decide("сегодня").text)
        assertEquals("клавиатура", engine.decide("клавиатура").text)
        assertEquals("клвиатур", engine.decide("клвиатур").text)
        val words = engine.suggestions("клвиатур", limit = 10).map { it.word }
        assertTrue(words.contains("клавиатура"))
        assertTrue(words.contains("клавиатуры"))
    }

    @Test fun doNotRewriteArbitraryShortWords() {
        assertEquals("пвет", engine.decide("пвет").text)
        assertEquals("дг", engine.decide("дг").text)
    }
}
