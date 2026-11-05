package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTypoTest {
    private val engine = BlockSearchCorrector(
        listOf(
            "сейчас", "одной", "рукой", "левша", "левой",
            "использую", "хорошо", "проверим", "печатаю"
        ).map { FastCorrector.Entry(it, 12000) }
    )

    @Test fun nearKeyErrorsAreCheaperThanRandomLetters() {
        assertTrue(KeyProximity.distance("чкйчас", "сейчас") < 0.91)
        assertTrue(KeyProximity.distance("чкйчас", "сейчас") <
            KeyProximity.distance("хзйчас", "сейчас"))
    }

    @Test fun restoreObservedOneHandedTypos() {
        assertEquals("сейчас", engine.decide("чкйчас").text)
        assertEquals("одной", engine.decide("однтй").text)
        assertEquals("рукой", engine.decide("руеой").text)
        assertEquals("левша", engine.decide("левла").text)
        assertEquals("левой", engine.decide("левоц").text)
        assertEquals("использую", engine.decide("ичпользую").text)
    }

    @Test fun keepCorrectAndUncertainWords() {
        assertEquals("сейчас", engine.decide("сейчас").text)
        assertEquals("левша", engine.decide("левша").text)
        assertEquals("квантос", engine.decide("квантос").text)
    }
}
