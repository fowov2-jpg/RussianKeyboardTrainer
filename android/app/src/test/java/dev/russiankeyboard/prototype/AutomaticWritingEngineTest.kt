package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class AutomaticWritingEngineTest {
    private fun engine(enabled: Boolean = true) = AutomaticWritingEngine(RussianCorrectionEngine(), null,
        { _, _, _, _ -> null }, enabled)
    @Test fun onlyCurrentParagraphAndSafeWholeWordsAreChanged() {
        val mode = PunctuationPlanner.Mode.STREAMING
        val ongoing=engine().analyze("севодня\nспосибо", "", WritingProfile(), mode)!!
        assertEquals("севодня\nспосибо", ongoing.replacement)
        assertEquals("севодня\nспасибо", ongoing.finalizedReplacement)
        assertEquals("севодня\nспасибо ", engine().analyze("севодня\nспосибо ", "", WritingProfile(), mode)?.replacement)
        for (text in listOf("https://example.com/спосибо", "@спосибо", "#спосибо", "спосибо_мир", "спосибо-тест", "«спосибо»", "ПрЕвЕт")) {
            assertNull(text, engine().analyze(text, "", WritingProfile(), mode))
        }
        assertNull(engine(false).analyze("спосибо", "", WritingProfile(), mode))
    }
    @Test fun explicitContextAndParagraphBoundaryReachPunctuationWithoutChangingEarlierText() {
        val engine = AutomaticWritingEngine(RussianCorrectionEngine(), null, { text, history, _, first ->
            assertEquals("как дела", text); assertEquals("Анна: привет", history); assertTrue(first)
            PunctuationPlanner.Result("Как дела?", "?")
        })
        val result = engine.analyze("прошлый текст\nкак дела", "Анна: привет", WritingProfile(), PunctuationPlanner.Mode.STREAMING, false)!!
        assertEquals("прошлый текст\nКак дела?", result.replacement)
        assertEquals("?", result.provisionalTerminal)
    }
}
