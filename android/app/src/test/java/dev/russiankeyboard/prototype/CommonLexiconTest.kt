package dev.russiankeyboard.prototype

import java.io.File
import java.nio.channels.FileChannel
import org.junit.Assert.*
import org.junit.Test

class CommonLexiconTest {
    companion object {
        private fun mapped(file:File)=file.inputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY,0,file.length()) }
        private val common by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.common")),"russian.bin"))) }
        private val morphology by lazy { MorphologyDictionary(mapped(File(requireNotNull(System.getProperty("rsk.context")),"morphology.bin"))) }
        private val spelling by lazy { SpellingIndex(mapped(File(requireNotNull(System.getProperty("rsk.context")),"spelling.bin"))) }
        private val legacy by lazy { RussianLexicon(mapped(File(requireNotNull(System.getProperty("rsk.lexicon"))))) }
    }
    @Test fun frequentSingleErrorsHaveFastMappingsAndAmbiguityIsExplicit() {
        assertEquals(5000,common.wordCount)
        assertTrue(common.typoCount>400000)
        for((raw,word) in listOf("привнт" to "привет","сеглдня" to "сегодня","пожадуйста" to "пожалуйста","завтпа" to "завтра")) {
            val candidate=requireNotNull(common.lookup(raw));assertEquals(word,candidate.word);assertTrue(candidate.automatic)
        }
        assertFalse(requireNotNull(common.lookup("итгр")).automatic)
        assertNull(common.lookup("сегодня"))
    }
    @Test fun namesAndShortAcronymsDoNotGainAnAutomaticFrequentWordGuess() {
        val engine=RussianCorrectionEngine({morphology},common={common})
        assertEquals("Привет ",engine.onSpace("Привнт")?.replacementWithSpace)
        assertNull(engine.onSpace("контакт Привнт"))
        assertNull(engine.onSpace("@привнт"))
        assertNull(engine.onSpace("https://example.test/привнт"))
        assertNull(engine.onSpace("привнт",isSensitiveField=true))
    }
    @Test fun aNewAmbiguityVetoStopsTheLegacyGrowingTigerGuess() {
        val engine=RussianCorrectionEngine({morphology},{legacy},{common})
        val live=LiveSpellingController(engine,{true},{morphology.hasPrefix(it)||spelling.hasPrefix(it)},
            {morphology.hasPrefix(it)||spelling.hasContinuation(it)})
        var shown=""
        for(key in "итгры") {
            shown+=key
            live.appended(shown,key.toString())?.edit?.let { shown=shown.dropLast(it.expectedSuffix.length)+it.replacementWithSpace }
        }
        assertEquals("итгры",shown)
    }
}
