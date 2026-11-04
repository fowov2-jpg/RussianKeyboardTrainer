package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class PunctuationPlannerTest {
    private val tokenizer = PunctuationTokenizer(File(System.getProperty("rsk.punctuation"), "vocab.txt").readLines())
    private data class Reference(val text: String, val context: String, val ids: LongArray,
                                 val positions: List<Int>, val predictions: List<PunctuationPlanner.Prediction>)
    private fun references(): List<Reference> {
        DataInputStream(requireNotNull(javaClass.getResourceAsStream("/punctuation_reference.bin"))).use { input ->
            val magic = ByteArray(8); input.readFully(magic)
            assertEquals("RUPTEST1", String(magic))
            fun string(): String { val bytes = ByteArray(input.readInt()); input.readFully(bytes); return String(bytes, Charsets.UTF_8) }
            return List(input.readInt()) {
                val text = string(); val context = string()
                val ids = LongArray(input.readInt()) { input.readInt().toLong() }
                val positions = mutableListOf<Int>()
                val predictions = List(input.readInt()) {
                    positions.add(input.readInt())
                    PunctuationPlanner.prediction(FloatArray(33) { input.readFloat() })
                }
                Reference(text, context, ids, positions, predictions)
            }
        }
    }
    @Test fun wordPieceMatchesPinnedHuggingFaceTokenizerAndRealModelLogits() {
        for (reference in references()) {
            val encoded = tokenizer.encode(reference.text, reference.context)
            assertArrayEquals(reference.text, reference.ids, encoded.ids)
            assertEquals(reference.text, reference.positions, encoded.words.map { it.firstToken })
        }
        assertArrayEquals(tokenizer.encode("привет как дела").ids, tokenizer.encode("Привет как дела").ids)
        assertEquals("ПрЕвЕт", tokenizer.encode("ПрЕвЕт").words.single().text)
    }
    @Test fun actualNeuralPredictionsRestoreUserExampleWithoutTurningStatementsIntoQuestions() {
        val expected = mapOf(
            "привет как дела" to ("Привет, как дела?" to "Привет, как дела?"),
            "как дела у тебя" to ("Как дела у тебя?" to "Как дела у тебя?"),
            "я знаю как дела" to ("Я знаю, как дела" to "Я знаю, как дела."),
            "я думаю что завтра будет дождь" to ("Я думаю, что завтра будет дождь" to "Я думаю, что завтра будет дождь."),
            "привет" to ("Привет" to "Привет."),
            "Привет, как дела?" to ("Привет, как дела?" to "Привет, как дела?"),
            "я работаю в компании" to ("Я работаю в компании" to "Я работаю в компании."),
            "спасибо за помощь" to ("Спасибо за помощь" to "Спасибо за помощь.")
        )
        for (reference in references()) {
            val pair = expected[reference.text] ?: continue
            val encoded = tokenizer.encode(reference.text, reference.context)
            for ((mode, text) in listOf(PunctuationPlanner.Mode.STREAMING to pair.first, PunctuationPlanner.Mode.FINALIZE to pair.second)) {
                val result = PunctuationPlanner.plan(reference.text, encoded, reference.predictions, mode)
                assertEquals("${reference.text}: $mode", text, result?.text ?: reference.text)
                if (mode == PunctuationPlanner.Mode.STREAMING && !text.endsWith('?')) assertNull(result?.provisionalTerminal)
            }
        }
    }
    @Test fun protectedSpansAndExistingPunctuationSurviveAggressivePredictions() {
        for (text in listOf("https://example.com/привет", "@привет", "#привет", "привет_мир", "«привет как дела»")) {
            val encoded = tokenizer.encode(text)
            val loud = encoded.words.map { PunctuationPlanner.Prediction(2, .99, .98, 0, .99) }
            assertNull(text, PunctuationPlanner.plan(text, encoded, loud, PunctuationPlanner.Mode.FINALIZE))
        }
        val mixed = tokenizer.encode("ПрЕвЕт")
        assertTrue(mixed.words.single().preserveCase)
        val text = "привет!"
        val encoded = tokenizer.encode(text)
        assertEquals("Привет!", PunctuationPlanner.plan(text, encoded,
            listOf(PunctuationPlanner.Prediction(2, .99, .98, 0, .99)), PunctuationPlanner.Mode.FINALIZE)?.text)
    }
    @Test fun incompleteQuestionAndTruncatedBeginningAreNotFinished() {
        val text = "как дела у"
        val encoded = tokenizer.encode(text, sentenceStart = false)
        val scores = encoded.words.map { PunctuationPlanner.Prediction(2, .99, .98, 0, .99) }
        assertNull(PunctuationPlanner.plan(text, encoded, scores, PunctuationPlanner.Mode.STREAMING))
        assertTrue(tokenizer.encode(List(300) { "клавиатура" }.joinToString(" ")).cutFront)
    }
    @Test fun casingRequiresARealSentenceBreakWhenModelPredictsOne() {
        val predictions = listOf(PunctuationPlanner.Prediction(0, .99, .98, 0, .99),
            PunctuationPlanner.Prediction(10, .99, .98, 0, .99))
        val comma = "Спасибо, пожалуйста"
        assertNull(PunctuationPlanner.plan(comma, tokenizer.encode(comma), predictions, PunctuationPlanner.Mode.STREAMING))
        val period = "Спасибо. пожалуйста"
        assertEquals("Спасибо. Пожалуйста", PunctuationPlanner.plan(period, tokenizer.encode(period), predictions, PunctuationPlanner.Mode.STREAMING)?.text)
        val name = "я вижу анну"
        val nameScores = listOf(PunctuationPlanner.Prediction(10, .99, .98, 0, .99),
            PunctuationPlanner.Prediction(10, .99, .98, 1, .99), PunctuationPlanner.Prediction(10, .99, .98, 0, .99))
        assertEquals("Я вижу Анну", PunctuationPlanner.plan(name, tokenizer.encode(name), nameScores, PunctuationPlanner.Mode.STREAMING)?.text)
    }
}
