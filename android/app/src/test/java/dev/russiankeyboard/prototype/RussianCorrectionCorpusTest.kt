package dev.russiankeyboard.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every TSV row is a distinct JUnit testcase, including real word/sentence
 * contexts and fail-closed KEEP cases. Data is artificial; no private text.
 * These JVM policy tests do not replace the emulator InputConnection smoke.
 */
@RunWith(Parameterized::class)
class RussianCorrectionCorpusTest(
    private val caseId: String,
    private val input: String,
    private val expectedReplacement: String,
    private val expectedFullText: String,
    private val sensitiveField: Boolean
) {
    @Test fun keepsOrCorrectsTheExpectedCompleteText() {
        val edit = RussianCorrectionEngine().onSpace(input, sensitiveField)
        val expected = if (expectedReplacement == "<KEEP>") null else "$expectedReplacement "
        assertEquals("Correction mismatch: $caseId", expected, edit?.replacementWithSpace)

        // The engine returns only a suffix replacement. Verify that applying
        // that exact suffix preserves the untouched prefix of every sentence.
        val actualFullText = if (edit == null) {
            "$input "
        } else {
            assertTrue("Unsafe suffix boundary in $caseId", input.endsWith(edit.expectedSuffix))
            input.dropLast(edit.expectedSuffix.length) + edit.replacementWithSpace
        }
        assertEquals("Complete sentence mismatch: $caseId", "$expectedFullText ", actualFullText)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun cases(): Collection<Array<Any>> {
            val stream = requireNotNull(RussianCorrectionCorpusTest::class.java.classLoader
                ?.getResourceAsStream("russian_corrections_v1.tsv")) {
                "Missing corpus: russian_corrections_v1.tsv"
            }
            val rows = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            require(rows.size >= 400) { "Release corpus unexpectedly short: ${rows.size}" }
            val distinct = HashSet<String>()
            return rows.mapIndexed { index, row ->
                val parts = row.split('\t', limit = 5)
                require(parts.size == 5) { "Bad TSV row ${index + 1}" }
                val (id, input, replacement, fullText, fieldType) = parts
                require(id.isNotBlank() && fieldType in setOf("NORMAL", "SENSITIVE")) {
                    "Invalid corpus case ${index + 1}"
                }
                require(distinct.add("$fieldType\\u0000$input")) {
                    "Duplicate corpus text and field mode: $id"
                }
                arrayOf<Any>(id, input, replacement, fullText, fieldType == "SENSITIVE")
            }
        }
    }
}
