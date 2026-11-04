package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class StreamingTextBoundaryTest {
    @Test fun unfinishedDictionaryPrefixStaysOutsideNeuralSentence() {
        val span = StreamingTextBoundary.split("привет как д", { false }, { it == "д" })
        assertEquals("привет как ", span.input)
        assertEquals("д", span.tail)
    }
    @Test fun knownWordsCompletedWordsAndObviousErrorsRemainAvailable() {
        for (text in listOf("привет как дела", "привет как дела ", "привт")) {
            assertEquals(text, StreamingTextBoundary.split(text, { it == "дела" }, { it == "дела" }).input)
        }
    }
}
