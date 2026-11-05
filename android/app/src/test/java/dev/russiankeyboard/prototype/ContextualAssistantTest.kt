package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

class ContextualAssistantTest {
    companion object {
        private val folder = File(requireNotNull(System.getProperty("rsk.context")))
        private fun mapped(file: File) = file.inputStream().use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
        private val lexicon by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.lexicon"))))) }
        private val spelling by lazy { SpellingIndex(mapped(File(folder, "spelling.bin"))) }
        private val language by lazy { ContextLanguageModel(mapped(File(folder, "language.bin"))) }
        private val morphology by lazy { MorphologyDictionary(mapped(File(folder, "morphology.bin"))) }
        private val assistant by lazy { ContextualAssistant(lexicon, spelling, language,
            ContextualAssistant.parsePredictions(File(folder, "predictions.tsv").readLines()),
            ContextualAssistant.parseGrammar(File(folder, "grammar.tsv").readLines()), morphology) }
    }

    @Test fun trieFindsUnseenSingleAndDoubleEditsWithoutAnExactPair() {
        for (typo in listOf("клавиатуоа", "клавиатурра", "клавиатуар", "клвиатур", "клавиату")) {
            assertTrue(typo, spelling.search(typo, 2, 64).any { it.word == "клавиатура" })
        }
        assertTrue(spelling.contains("как"))
        assertTrue(spelling.search("акк", 1).any { it.word == "как" })
        assertTrue(spelling.complete("клавиат").any { it.word.startsWith("клавиат") })
    }

    @Test fun reviewFixesMultipleTokensAndPreservesProtectedText() {
        assertEquals("Привет как дела", assistant.review("Ппривет как длела")?.proposed)
        assertEquals("Привет", assistant.review("Привeт")?.proposed)
        assertNull(assistant.review("@ппривет https://example.com/ппривет #ппривет"))
        assertNull(assistant.review("ПрЕвЕт"))
        assertNull(assistant.review("Я ДуМаю чТО"))
        assertNull(assistant.review("Я встретил Мегазурика"))
        assertNull(assistant.review(""))
    }

    @Test fun grammarIsAnExplicitPreviewForAnExplicitSubjectOnly() {
        assertEquals("Она работала вчера", assistant.review("Она работал вчера")?.proposed)
        assertEquals("Он работал вчера", assistant.review("Он работала вчера")?.proposed)
        assertEquals("Она вчера ходила", assistant.review("Она вчера ходил")?.proposed)
        assertEquals("Она мне очень понравилась", assistant.review("Она мне очень понравился")?.proposed)
        assertNull(assistant.review("Я работал вчера"))
        assertNull(assistant.review("Ты работала вчера"))
        assertEquals("Я знаю, что он придёт", assistant.review("Я знаю что он придёт")?.proposed)
        val choice = assistant.suggestions("Ппривет").first()
        assertFalse(choice.automatic)
    }

    @Test fun contextCanChangeWhichOfTwoWordsIsPreferred() {
        // These are ordinary Russian uses; this test exercises the real trained model.
        assertTrue(language.score(listOf("доброе"), "утро", emptyList()) >
            language.score(listOf("доброе"), "вечер", emptyList()))
        val predictions = assistant.suggestions("как ")
        assertTrue(predictions.isNotEmpty())
        assertTrue(predictions.all { it.edit.reason == RussianCorrectionEngine.Reason.PREDICTION && !it.automatic })
    }

    @Test fun personalGenderRequiresExplicitConfigurationAndNeverChangesQuotedSpeech() {
        assertEquals("Я работал", assistant.automaticGrammar("Я работал", WritingProfile()))
        assertEquals("Я работала", assistant.automaticGrammar("Я работал", WritingProfile(speakerGender = "Fem")))
        assertEquals("Ты работал", assistant.automaticGrammar("Ты работал", WritingProfile(speakerGender = "Fem")))
        assertEquals("Ты работала", assistant.automaticGrammar("Ты работал", WritingProfile(recipientGender = "Fem")))
        assertEquals("«Я работал»", assistant.automaticGrammar("«Я работал»", WritingProfile(speakerGender = "Fem")))
    }
    @Test fun streamingAgreementDoesNotConsumeTheNextLetterOfAnUnfinishedVerb() {
        val engine=AutomaticWritingEngine(RussianCorrectionEngine(),assistant,{_,_,_,_->null})
        val profile=WritingProfile(speakerGender="Fem")
        val pending=engine.analyze("Я работал","",profile,PunctuationPlanner.Mode.STREAMING)!!
        assertEquals("Я работал",pending.replacement)
        assertEquals("Я работала",pending.finalizedReplacement)
        assertNull(engine.analyze("Я работала","",profile,PunctuationPlanner.Mode.STREAMING))
        assertEquals("Я работала ",engine.analyze("Я работал ","",profile,PunctuationPlanner.Mode.STREAMING)?.replacement)
    }

    @Test fun evaluateOnSentenceHoldoutAndPreviouslyUnseenErrors() {
        val engine = RussianCorrectionEngine(morphology = { morphology }, lexicon = { lexicon })
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("context_holdout.tsv.gz"))
        val tokens = Regex("[А-Яа-яЁё]+")
        var sentences = 0; var cleanTokens = 0; var automaticEdits = 0
        var noisyCases = 0; var top3 = 0
        val used = HashSet<String>()
        val times = mutableListOf<Long>()
        GZIPInputStream(source).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine()
            reader.forEachLine { line ->
                val fields = line.split('\t', limit = 3)
                if (fields[0] == "test") {
                    sentences++
                    for (match in tokens.findAll(fields[2])) {
                        val word = match.value.lowercase()
                        cleanTokens++
                        val edit = engine.onSpace(match.value)
                        if (edit != null && edit.replacementWithSpace.trimEnd() != match.value) automaticEdits++
                        if (noisyCases >= 160 || word.length !in 5..16 || !spelling.contains(word) || !used.add(word)) continue
                        val position = word.length / 2
                        val typo = word.substring(0, position) + (if (word[position] == 'ж') 'ф' else 'ж') + word.substring(position + 1)
                        if (lexicon.isKnown(typo) || spelling.contains(typo)) continue
                        val before = fields[2].substring(0, match.range.first) + typo
                        val start = System.nanoTime()
                        val choices = assistant.suggestions(before)
                        times.add(System.nanoTime() - start)
                        if (choices.any { it.edit.replacementWithSpace.trimEnd().lowercase() == word }) top3++
                        noisyCases++
                    }
                }
            }
        }
        val expectedSentences = Regex("\"test\"\\s*:\\s*(\\d+)")
            .find(File(folder, "manifest.json").readText())!!.groupValues[1].toInt()
        assertEquals(expectedSentences, sentences)
        assertEquals(160, noisyCases)
        assertTrue(cleanTokens > 10_000)
        assertTrue("Source clean auto-edit rate exceeds target", automaticEdits.toDouble() / cleanTokens <= .005)
        assertTrue("General candidate lookup is ineffective", top3 > noisyCases / 2)
        times.sort()
        val out = File(requireNotNull(System.getProperty("rsk.contextReport")))
        requireNotNull(out.parentFile).mkdirs()
        out.writeText("""{"test_sentences":$sentences,"clean_tokens":$cleanTokens,"clean_source_automatic_edits":$automaticEdits,"synthetic_unseen_errors":$noisyCases,"target_in_top3":$top3,"host_suggestion_p50_ms":${times[times.size/2]/1e6},"host_suggestion_p95_ms":${times[times.size*95/100]/1e6},"scope":"Sentence-disjoint licensed Tatoeba holdout; generated errors, not real human typo accuracy; JVM timings, not phone latency"}""")
    }
}
