package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class StreamingEditRebaserTest {
    @Test fun analysisRetainsKeysTypedWhileTheModelWasRunning() {
        val source = "привет как дела "
        val current = source + "у тебя"
        val plan = StreamingEditRebaser.rebase(source, "Привет, как дела? ", "?", source.length, current, current.length)!!
        assertEquals(current, plan.original)
        assertEquals("Привет, как дела у тебя", plan.replacement)
        assertNull(plan.provisionalTerminal)
    }
    @Test fun changedPrefixMovedCaretAndTruncatedSourceCannotBeRewritten() {
        assertNull(StreamingEditRebaser.rebase("как дела", "Как дела?", "?", 8, "где дела", 8))
        assertNull(StreamingEditRebaser.rebase("как дела", "Как дела?", "?", 8, "как", 3))
        assertNull(StreamingEditRebaser.rebase("как дела", "Как дела?", "?", 8, "дела у", 10))
    }
    @Test fun boundedEditorWindowUsesAbsoluteOffsets() {
        val plan = StreamingEditRebaser.rebase("как дела ", "Как дела? ", "?", 109,
            "предыдущее как дела у тебя", 115)!!
        assertEquals("Как дела у тебя", plan.replacement)
    }
}
