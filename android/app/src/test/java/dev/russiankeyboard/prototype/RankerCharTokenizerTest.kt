package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class RankerCharTokenizerTest {
    private val vocab = listOf("<PAD>", "<UNK>", "<CLS>", "<SEP>", "а", "б", " ", "я", "ё")
    private val tokenizer = RankerCharTokenizer(vocab, 52)

    @Test fun keepsPythonSeparatorOrderAndPadsRight() {
        val result = tokenizer.encodePair("а ", "б", "я")
        assertArrayEquals(longArrayOf(2, 4, 6, 3, 5, 3, 7, 3), result.ids.take(8).toLongArray())
        assertTrue(result.ids.drop(8).all { it == 0L })
        assertTrue(result.mask.take(8).all { it == 1L })
        assertTrue(result.mask.drop(8).all { it == 0L })
    }

    @Test fun truncatesContextFromLeft() {
        val result = tokenizer.encodePair("а".repeat(100), "б".repeat(24), "я".repeat(24))
        assertArrayEquals(longArrayOf(2, 3), result.ids.take(2).toLongArray())
        assertEquals(52, result.mask.sum())
    }

    @Test fun emojiIsOneUnknownTokenRatherThanTwoSurrogates() {
        val result = tokenizer.encodePair("😀", "а", "ё")
        assertArrayEquals(longArrayOf(2, 1, 3, 4, 3, 8, 3), result.ids.take(7).toLongArray())
    }

    @Test fun rejectsImpossibleVocabularyAndSmallMaxLength() {
        try {
            RankerCharTokenizer(vocab, 30)
            fail("Expected failure")
        } catch (_: IllegalArgumentException) {}
        try {
            RankerCharTokenizer(listOf("a"), 80)
            fail("Expected failure")
        } catch (_: IllegalArgumentException) {}
    }
}
