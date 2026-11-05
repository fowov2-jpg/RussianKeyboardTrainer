package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

class RussianLexiconTest {
    companion object {
        private val binary = File(requireNotNull(System.getProperty("rsk.lexicon")))
        private val lexicon by lazy {
            binary.inputStream().use { input ->
                RussianLexicon(input.channel.map(FileChannel.MapMode.READ_ONLY, 0, binary.length()))
            }
        }
        private val morphology by lazy {
            val file = File(requireNotNull(System.getProperty("rsk.context")), "morphology.bin")
            file.inputStream().use { MorphologyDictionary(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())) }
        }
    }

    @Test fun everyMillionCorpusRowUsesTheRealRuntimeLookup() {
        assertEquals(100_000, lexicon.wordCount)
        assertEquals(1_000_000, lexicon.typoCount)
        val engine = RussianCorrectionEngine(morphology = { morphology }, lexicon = { lexicon })
        val legacy = RussianCorrectionEngine()
        val corpus = requireNotNull(javaClass.classLoader?.getResourceAsStream("lexicon_corpus.tsv.gz"))
        var count = 0
        var automatic = 0
        var curatedOverrides = 0
        var keptKnownForms = 0
        val coveredWords = HashSet<String>()
        val start = System.nanoTime()
        GZIPInputStream(corpus).bufferedReader(Charsets.UTF_8).use { reader ->
            assertEquals("mistyped\tintended\terror_type", reader.readLine())
            reader.forEachLine { row ->
                val (typo, intended, kind) = row.split('\t')
                val found = lexicon.lookup(typo)
                assertNotNull("Missing candidate for $typo", found)
                assertEquals("Wrong runtime candidate for $typo", intended, found!!.word)
                val knownForm = morphology.contains(typo)
                if (knownForm) {
                    assertTrue("Correct wordform must be kept: $typo", engine.suggestions(typo).isEmpty())
                    keptKnownForms++
                } else assertTrue("Missing engine choice for $typo", engine.suggestions(typo)
                    .any { it.edit.replacementWithSpace == "$intended " })
                val edit = engine.onSpace(typo)
                if (knownForm) assertNull("Do not rewrite real wordforms: $typo", edit)
                if (edit != null) {
                    val curated = legacy.onSpace(typo)
                    assertEquals("Wrong automatic correction for $typo",
                        curated?.replacementWithSpace ?: "$intended ", edit.replacementWithSpace)
                    if (edit.replacementWithSpace != "$intended ") curatedOverrides++
                    assertNotEquals("Two-edit guesses must need a tap", "two_neighbor_errors", kind)
                    automatic++
                }
                coveredWords.add(intended)
                count++
            }
        }
        assertEquals(1_000_000, count)
        assertEquals(100_000, coveredWords.size)
        assertTrue("Large dictionary must really enable new automatic corrections", automatic > 100_000)
        assertTrue("Some candidates must remain manual suggestions", automatic < count)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val report = File(requireNotNull(System.getProperty("rsk.lexiconReport")))
        requireNotNull(report.parentFile).mkdirs()
        report.writeText("""{"runtime_rows_checked":$count,"distinct_words":${coveredWords.size},"automatic":$automatic,"manual":${count - automatic - keptKnownForms},"morphology_keep":$keptKnownForms,"curated_overrides":$curatedOverrides,"host_elapsed_ms":$elapsedMs,"scope":"synthetic corpus lookup and engine integration on JVM; valid OpenCorpora wordforms are KEEP; not human accuracy or phone latency"}""")
    }

    @Test fun knownWordsStayUnchangedAndBoundariesStillApply() {
        val engine = RussianCorrectionEngine { lexicon }
        for (word in listOf("привет", "клавиатура", "работа", "спасибо", "сегодня")) {
            assertTrue(lexicon.isKnown(word))
            assertNull(lexicon.lookup(word))
            assertNull(engine.onSpace(word))
        }
        val typo = "клавиаиура"
        assertEquals("клавиатура ", engine.suggest(typo)?.edit?.replacementWithSpace)
        assertEquals("Клавиатура ", engine.suggest("Клавиаиура")?.edit?.replacementWithSpace)
        assertEquals("КЛАВИАТУРА ", engine.suggest("КЛАВИАИУРА")?.edit?.replacementWithSpace)
        for (value in listOf("@$typo", "https://x.ru/$typo", "id_$typo", "слово-$typo", "КлаВиаиура")) {
            assertNull(value, engine.suggest(value))
            assertNull(value, engine.onSpace(value))
        }
        assertNull(engine.suggest(typo, isSensitiveField = true))
        assertNull(engine.onSpace(typo, isSensitiveField = true))
    }

    @Test fun malformedAssetsAreRejectedBeforeLookup() {
        for (bytes in listOf(byteArrayOf(), "RSKLEX01".toByteArray(), "RSKLEX02".toByteArray(),
                "RSKLEX02".toByteArray() + byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f))) {
            try {
                RussianLexicon(ByteBuffer.wrap(bytes))
                fail("Corrupt lexicon was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected: invalid input must never become editor replacements.
            }
        }
    }

    @Test fun curatedCorrectionAndConflictingCorpusTargetAreBothAvailable() {
        val engine = RussianCorrectionEngine { lexicon }
        // "очнь" can be a neighboring-key error in "очно" AND a missing
        // vowel in "очень". Synthetic pair identity is not human intent.
        assertEquals(setOf("очень ", "очно "),
            engine.suggestions("очнь").map { it.edit.replacementWithSpace }.toSet())
        assertEquals("очень ", engine.onSpace("очнь")?.replacementWithSpace)
    }
}
