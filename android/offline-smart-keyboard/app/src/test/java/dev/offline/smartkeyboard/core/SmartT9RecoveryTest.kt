package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartT9RecoveryTest {
    private val original = FastCorrector.Entry("привет", 22000)
    private val blockIndex = NineBlockIndex()
    private val recovery = SmartT9Recovery(blockIndex)

    init {
        val code = T9Predictor.encode(original.word)!!
        blockIndex.add(original, code, true)
        recovery.add(original, code, true)
    }

    private fun words(digits: String): Set<String> {
        val result = LinkedHashSet<String>()
        recovery.collect(digits) { result.add(it.word) }
        return result
    }

    @Test fun exactAndWrongFirstDigitRecoverOriginalCombination() {
        assertEquals("564236", T9Predictor.encode("привет"))
        assertTrue(words("564236").contains("привет"))
        // The example from the conversation: wrong group 2 instead of 5.
        assertTrue(words("264236").contains("привет"))
        assertTrue(words("224236").contains("привет")) // two wrong groups
    }

    @Test fun missingExtraAndTransposedBlocksRecoverOriginalCombination() {
        assertTrue(words("64236").contains("привет"))  // missing first block
        assertTrue(words("56423").contains("привет"))  // missing last block
        assertTrue(words("6423").contains("привет"))   // two missing blocks
        assertTrue(words("5564236").contains("привет")) // extra first block
        assertTrue(words("5642236").contains("привет")) // extra middle block
        assertTrue(words("546236").contains("привет")) // swapped digits
        assertTrue(words("2264236").contains("привет")) // extra + wrong
    }

    @Test fun neverReturnUnrelatedWordsOrInvalidDigits() {
        assertFalse(words("999999").contains("привет"))
        assertTrue(words("000000").isEmpty())
        assertTrue(words("12a34").isEmpty())
        assertTrue(words("1".repeat(25)).isEmpty())
    }

    @Test fun rankingUsesActualLettersNotOnlyNumericBlocks() {
        val engine = BlockSearchCorrector(listOf(original))
        assertEquals("привет", engine.decide("аривет").text)
        assertEquals("привет", engine.decide("ривет").text)
        assertEquals("привет", engine.decide("привет").text)
    }
}
