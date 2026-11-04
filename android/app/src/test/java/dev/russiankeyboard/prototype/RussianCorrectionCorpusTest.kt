package dev.russiankeyboard.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every TSV row and each contextual sentence variant is a separate JUnit
 * testcase, including fail-closed KEEP cases. The contexts are deterministic
 * augmentations of 585 synthetic base inputs, not 10k different dictionary
 * words or an independent Russian-language accuracy evaluation.
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
            require(rows.size >= 585) { "Seed corpus unexpectedly short: ${rows.size}" }
            val seeds = rows.mapIndexed { index, row ->
                val parts = row.split('\t', limit = 5)
                require(parts.size == 5) { "Bad TSV row ${index + 1}" }
                val (id, input, replacement, fullText, fieldType) = parts
                require(id.isNotBlank() && fieldType in setOf("NORMAL", "SENSITIVE")) {
                    "Invalid corpus case ${index + 1}"
                }
                arrayOf<Any>(id, input, replacement, fullText, fieldType == "SENSITIVE")
            }

            // Preserve all 585 seed scenarios and run them again in 17 real
            // sentence contexts. Each parameter becomes its own JUnit result.
            // These prefixes end in a space so that token-boundary semantics
            // stay unchanged. Keep variants distinct by full input and mode.
            val contexts = listOf(
                "",
                "В учебной заметке сохранили: ",
                "Сообщение коллеги было таким: ",
                "В черновике вчера появилось: ",
                "Перед отправкой проверили: ",
                "В протоколе записано: ",
                "На экране приложения увидели: ",
                "В переписке осталась строка: ",
                "В тестовом предложении стоит: ",
                "Проверка ввода содержит: ",
                "В обучающем упражнении есть: ",
                "Во время эксперимента напечатали: ",
                "В ежедневнике указано: ",
                "В сохранённом документе есть: ",
                "В редакторе текста отобразилось: ",
                "При повторном наборе получилось: ",
                "В разговоре использовали фразу: ",
                "В локальной заметке оставили: "
            )
            val distinct = HashSet<String>()
            val expanded = ArrayList<Array<Any>>(seeds.size * contexts.size)
            for ((contextIndex, prefix) in contexts.withIndex()) {
                for (seed in seeds) {
                    val id = seed[0] as String
                    val input = prefix + (seed[1] as String)
                    val expectedReplacement = seed[2] as String
                    val expectedFullText = prefix + (seed[3] as String)
                    val sensitiveField = seed[4] as Boolean
                    val fieldMode = if (sensitiveField) "SENSITIVE" else "NORMAL"
                    require(distinct.add("$fieldMode\\u0000$input")) {
                        "Duplicate expanded corpus input and field mode: $id"
                    }
                    val variantId = if (contextIndex == 0) id
                        else "$id-context-${contextIndex.toString().padStart(2, '0')}"
                    expanded.add(arrayOf<Any>(
                        variantId, input, expectedReplacement, expectedFullText, sensitiveField
                    ))
                }
            }
            require(expanded.size >= 10_000) {
                "Expanded release corpus too short: ${expanded.size}"
            }
            return expanded
        }
    }
}
