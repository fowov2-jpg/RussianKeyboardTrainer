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
class ExtendedKeyboardQaTest(private val case: ExtendedCase) {
    @Test fun distinctInputContractAndRecordedGoldComparison() {
        val row = if (case.kind in setOf("typo", "word_keep", "protected")) wordQa.run(case) else {
            val reference = requireNotNull(references[case.reference])
            val encoding = tokenizer.encode(case.input)
            assertArrayEquals(case.id, reference.ids, encoding.ids)
            assertEquals(case.id, reference.positions, encoding.words.map { it.firstToken })
            val start = System.nanoTime()
            val output = PunctuationPlanner.plan(case.input, encoding, reference.predictions, PunctuationPlanner.Mode.FINALIZE)?.text ?: case.input
            ExtendedPhraseQa.run(case, output, System.nanoTime() - start)
        }
        report.rows.add(row)
        assertTrue("${case.id}: ${row["input"]} -> ${row["actual"]}; gold=${row["gold"]}", row["contract_passed"] == true)
    }
    companion object {
        private val context = File(requireNotNull(System.getProperty("rsk.context")))
        private fun mapped(file: File) = file.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
        private val morphology by lazy { MorphologyDictionary(mapped(File(context, "morphology.bin"))) }
        private val spelling by lazy { SpellingIndex(mapped(File(context, "spelling.bin"))) }
        private val lexicon by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.lexicon"))))) }
        private val wordQa by lazy { ExtendedWordQa(RussianCorrectionEngine({ morphology }, { lexicon }), morphology, spelling) }
        private val tokenizer by lazy { PunctuationTokenizer(File(System.getProperty("rsk.punctuation"), "vocab.txt").readLines()) }
        private data class Reference(val ids: LongArray, val positions: List<Int>, val predictions: List<PunctuationPlanner.Prediction>)
        private val references: Map<String, Reference> by lazy {
            DataInputStream(requireNotNull(javaClass.getResourceAsStream("/extended_phrase_logits.bin"))).use { input ->
                val magic = ByteArray(8); input.readFully(magic); require(String(magic) == "RUPQA001")
                fun string(): String { val bytes = ByteArray(input.readInt()); input.readFully(bytes); return String(bytes, Charsets.UTF_8) }
                (0 until input.readInt()).associate {
                    val id = string(); val ids = LongArray(input.readInt()) { input.readInt().toLong() }
                    val positions = mutableListOf<Int>()
                    val predictions = List(input.readInt()) { positions.add(input.readInt()); PunctuationPlanner.prediction(FloatArray(33) { input.readFloat() }) }
                    id to Reference(ids, positions, predictions)
                }
            }
        }
        private val report = ExtendedQaReport("Host Kotlin production controller/planner; punctuation logits from real pinned Python ONNX inference. Timings exclude model inference; not phone latency.")
        @JvmStatic @Parameterized.Parameters(name = "{index}") fun cases(): List<Array<Any>> =
            ExtendedCase.read(requireNotNull(javaClass.getResourceAsStream("/extended_qa.tsv.gz"))).map { arrayOf(it as Any) }
        @JvmStatic @AfterClass fun save() {
            val dir = File(requireNotNull(System.getProperty("rsk.extendedReport"))); dir.mkdirs()
            File(dir, "extended-host-qa.json").writeText(report.json())
            File(dir, "extended-host-cases.tsv").writeText(report.casesTsv())
            assertEquals(7500, report.rows.size)
        }
    }
}
