package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class CorrectionUndoStateTest {
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
