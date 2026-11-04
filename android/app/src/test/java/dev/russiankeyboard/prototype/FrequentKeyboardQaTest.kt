package dev.russiankeyboard.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.channels.FileChannel
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FrequentKeyboardQaTest(private val case: ExtendedCase) {
    @Test fun frequencySelectedInputAndCommonIndexAblation() {
        val word = case.kind in setOf("typo", "word_keep", "protected")
        val old = if (word) beforeWords.run(case) else phrase()
        val current = if (word) afterWords.run(case) else old
        before.rows.add(old); after.rows.add(current)
        assertTrue("${case.id}: ${case.input} -> ${current["actual"]}, gold=${case.expected}", current["contract_passed"] == true)
    }
    private fun phrase(): Map<String, Any?> {
        val reference = requireNotNull(references[case.reference])
        val encoding = tokenizer.encode(case.input)
        assertArrayEquals(case.id, reference.ids, encoding.ids)
        assertEquals(reference.positions, encoding.words.map { it.firstToken })
        val start = System.nanoTime()
        val output = PunctuationPlanner.plan(case.input, encoding, reference.scores, PunctuationPlanner.Mode.FINALIZE)?.text ?: case.input
        return ExtendedPhraseQa.run(case, output, System.nanoTime() - start)
    }
    companion object {
        private fun mapped(file: File) = file.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
        private val context = File(requireNotNull(System.getProperty("rsk.context")))
        private val morphology by lazy { MorphologyDictionary(mapped(File(context, "morphology.bin"))) }
        private val spelling by lazy { SpellingIndex(mapped(File(context, "spelling.bin"))) }
        private val lexicon by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.lexicon"))))) }
        private val common by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.common")), "russian.bin"))) }
        private val beforeWords by lazy { ExtendedWordQa(RussianCorrectionEngine({ morphology }, { lexicon }), morphology, spelling) }
        private val afterWords by lazy { ExtendedWordQa(RussianCorrectionEngine({ morphology }, { lexicon }, { common }), morphology, spelling) }
        private val tokenizer by lazy { PunctuationTokenizer(File(requireNotNull(System.getProperty("rsk.punctuation")), "vocab.txt").readLines()) }
        private data class Reference(val ids: LongArray, val positions: List<Int>, val scores: List<PunctuationPlanner.Prediction>)
        private val references by lazy {
            DataInputStream(requireNotNull(javaClass.getResourceAsStream("/common_phrase_logits.bin"))).use { input ->
                val magic = ByteArray(8); input.readFully(magic); require(String(magic) == "RUPQA001")
                fun string(): String { val bytes=ByteArray(input.readInt()); input.readFully(bytes); return String(bytes, Charsets.UTF_8) }
                (0 until input.readInt()).associate {
                    val id=string(); val ids=LongArray(input.readInt()) { input.readInt().toLong() }; val positions=mutableListOf<Int>()
                    val scores=List(input.readInt()) { positions.add(input.readInt()); PunctuationPlanner.prediction(FloatArray(33) { input.readFloat() }) }
                    id to Reference(ids, positions, scores)
                }
            }
        }
        private val before=ExtendedQaReport("Frequent-dialogue proxy QA, common index disabled; same runtime otherwise. Synthetic edits are integration coverage, not human-error accuracy. Host policy time excludes ONNX inference.")
        private val after=ExtendedQaReport("Frequent-dialogue proxy QA, common index enabled. Dictionary/controller and Kotlin planner with real pinned ONNX logits. Host time excludes inference; not messenger telemetry or physical-phone latency.")
        @JvmStatic @Parameterized.Parameters(name="{index}") fun cases(): List<Array<Any>> = ExtendedCase.read(
            requireNotNull(javaClass.getResourceAsStream("/common_qa.tsv.gz"))).map { arrayOf(it as Any) }
        @JvmStatic @AfterClass fun save() {
            val out=File(requireNotNull(System.getProperty("rsk.extendedReport")));out.mkdirs()
            File(out,"common-host-before.json").writeText(before.json());File(out,"common-host-before.tsv").writeText(before.casesTsv())
            File(out,"common-host-after.json").writeText(after.json());File(out,"common-host-after.tsv").writeText(after.casesTsv())
            assertEquals(7500,after.rows.size)
        }
    }
}
