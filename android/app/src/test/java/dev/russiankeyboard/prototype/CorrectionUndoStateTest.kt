package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class CorrectionUndoStateTest {
    @Test fun neuralCapitalizationAndPunctuationCanUndoTheEarlierLiveTypoAsOneEdit() {
        val undo = CorrectionUndoState()
        undo.record(RussianCorrectionEngine.Edit("севодня", "сегодня", RussianCorrectionEngine.Reason.TYPO))
        undo.record(RussianCorrectionEngine.Edit("я сегодня", "Я сегодня", RussianCorrectionEngine.Reason.PHRASE), combine = true)
        assertEquals("я севодня", undo.takeIfMatching("Я сегодня"))
    }
    private fun edit() = RussianCorrectionEngine().onSpace("Я севодня")!!

    @Test fun backspaceRestoresOriginalWordOnce() {
        val undo = CorrectionUndoState()
        undo.record(edit())
        assertEquals("сегодня ".length, undo.suffixLength())
        assertEquals("севодня", undo.takeIfMatching("сегодня "))
        assertEquals(0, undo.suffixLength())
        assertNull(undo.takeIfMatching("сегодня "))
    }

    @Test fun neverUndoStaleSuffix() {
        val undo = CorrectionUndoState()
        undo.record(edit())
        assertNull(undo.takeIfMatching("сегодня!"))
        assertEquals(0, undo.suffixLength())
    }

    @Test fun clearInvalidatesCheckpoint() {
        val undo = CorrectionUndoState()
        undo.record(edit())
        undo.clear()
        assertNull(undo.takeIfMatching("сегодня "))
    }

    @Test fun undoSupportsMultiwordAndCommaEdits() {
        val undo = CorrectionUndoState()
        undo.record(RussianCorrectionEngine().onSpace("потомучто")!!)
        assertEquals("потомучто", undo.takeIfMatching("потому что "))
        undo.record(RussianCorrectionEngine().onSpace("Я думаю что")!!)
        assertEquals("думаю что", undo.takeIfMatching("думаю, что "))
    }
}
