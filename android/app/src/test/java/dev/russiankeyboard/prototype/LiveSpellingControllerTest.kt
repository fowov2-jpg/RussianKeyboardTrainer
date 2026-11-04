package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

class LiveSpellingControllerTest {
    companion object {
        private val directory = File(requireNotNull(System.getProperty("rsk.context")))
        private fun mapped(name: String) = File(directory, name).let { file -> file.inputStream().use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) } }
        private val morphology by lazy { MorphologyDictionary(mapped("morphology.bin")) }
        private val spelling by lazy { SpellingIndex(mapped("spelling.bin")) }
        private val lexicon by lazy { File(requireNotNull(System.getProperty("rsk.lexicon"))).let { file -> file.inputStream().use {
            RussianLexicon(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())) } } }
    }
    private fun controller() = LiveSpellingController(RussianCorrectionEngine(morphology = { morphology }, lexicon = { lexicon }),
        { true }, { morphology.hasPrefix(it) || spelling.hasPrefix(it) },
        { morphology.hasPrefix(it) || spelling.hasContinuation(it) })
    private fun type(controller: LiveSpellingController, text: String): Pair<String, LiveSpellingController.Plan?> {
        var shown = ""; var latest: LiveSpellingController.Plan? = null
        for (key in text) {
            shown += key
            val plan = controller.appended(shown, key.toString())
            if (plan != null) latest = plan
            plan?.edit?.let { assertTrue(shown.endsWith(it.expectedSuffix)); shown = shown.dropLast(it.expectedSuffix.length) + it.replacementWithSpace }
        }
        return shown to latest
    }
    @Test fun obviousTypoChangesOnTheLastLetterWithoutSpaceOrPause() {
        assertEquals("сегодня", type(controller(), "севодня").first)
        assertEquals("привет", type(controller(), "привт").first)
        assertEquals("пожалуйста", type(controller(), "пожалуста").first)
        assertEquals("ПРИВЕТИК", type(controller(), "ПРИВТИК").first)
    }
    @Test fun legitimatePrefixesKnownWordsAndStructuredTokensAreKept() {
        for (text in listOf("приветст", "работала", "елка", "@превет", "#превет", "https://x/превет", "ПрЕвЕт"))
            assertEquals(text, text, type(controller(), text).first)
        assertNull(LiveSpellingController(RussianCorrectionEngine(), { false }, { false }).appended("привт", "т"))
    }
    @Test fun correctedWordCanGrowAndStillUndoToRawKeystrokes() {
        val (shown, plan) = type(controller(), "привтик ")
        assertEquals("приветик ", shown)
        assertEquals("привтик", plan!!.checkpoint!!.expectedSuffix)
        assertEquals(shown, plan.checkpoint!!.replacementWithSpace)
    }
    @Test fun identifiersRestoreOriginalLettersAndRawContinuationCanOverrideAChange() {
        assertEquals("привт_тест", type(controller(), "привт_тест").first)
        val fake = LiveSpellingController(RussianCorrectionEngine(), { true }, { it == "привта" })
        assertEquals("привта", type(fake, "привта").first)
    }
    @Test fun modelCapitalizationKeepsRawIntentAvailableForContinuation() {
        val live = controller()
        assertEquals("привет", type(live, "привт").first)
        live.reconcile("Привет")
        val plan = live.appended("Привет_", "_")!!
        assertEquals("привт_", plan.edit!!.replacementWithSpace)
        assertNull(plan.checkpoint)
    }
    @Test fun distinctRealIndexedErrorsNeverTurnIntoAnUnrelatedThirdWordWhileGrowing() {
        val baseline = RussianCorrectionEngine(morphology = { morphology }, lexicon = { lexicon })
        val selected = HashSet<String>(); var changed = 0
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/lexicon_corpus.tsv.gz"))).bufferedReader().use { reader ->
            reader.readLine()
            while (selected.size < 500) {
                val fields = (reader.readLine() ?: break).split('\t')
                val raw = fields[0]
                if (raw.length < 5) continue
                val expected = baseline.onSpace(raw)?.takeIf { it.reason == RussianCorrectionEngine.Reason.TYPO }?.replacementWithSpace?.trimEnd() ?: continue
                if (!selected.add(expected)) continue
                val actual = type(controller(), raw).first
                assertTrue("$raw -> $actual, expected $expected or KEEP", actual == raw || actual == expected)
                if (actual == expected) changed++
            }
        }
        assertEquals(500, selected.size)
        assertTrue("Live path never changed an indexed error", changed > 0)
    }
}
