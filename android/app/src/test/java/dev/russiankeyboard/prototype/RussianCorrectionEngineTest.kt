package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class RussianCorrectionEngineTest {
    private val engine = RussianCorrectionEngine()

    @Test fun fixesObviousTypoAfterSpace() {
        val edit = engine.onSpace("Я севодня")!!
        assertEquals("севодня", edit.expectedSuffix)
        assertEquals("сегодня ", edit.replacementWithSpace)
        assertEquals(RussianCorrectionEngine.Reason.TYPO, edit.reason)
    }

    @Test fun preservesInitialUppercase() {
        assertEquals("Сегодня ", engine.onSpace("Севодня")?.replacementWithSpace)
    }

    @Test fun preservesAllCaps() {
        assertEquals("СЕГОДНЯ ", engine.onSpace("СЕВОДНЯ")?.replacementWithSpace)
    }

    @Test fun supportsSpacedReplacement() {
        assertEquals("потому что ", engine.onSpace("потомучто")?.replacementWithSpace)
    }

    @Test fun insertsCommaOnlyForCuratedPatterns() {
        val edit = engine.onSpace("Я думаю что")!!
        assertEquals("думаю что", edit.expectedSuffix)
        assertEquals("думаю, что ", edit.replacementWithSpace)
        assertEquals(RussianCorrectionEngine.Reason.COMMA, edit.reason)
    }

    @Test fun doesNotInsertCommaIntoSubstring() {
        assertNull(engine.onSpace("передумаю что"))
    }

    @Test fun leavesUnknownAndAmbiguousSentencesAlone() {
        assertNull(engine.onSpace("она мне очень понравился"))
        assertNull(engine.onSpace("ты вчера ходил"))
        assertNull(engine.onSpace("я написала что"))
        assertNull(engine.onSpace(""))
    }

    @Test fun neverChangesSensitiveField() {
        assertNull(engine.onSpace("Я севодня", isSensitiveField = true))
        assertNull(engine.onSpace("Я думаю что", isSensitiveField = true))
    }
}
