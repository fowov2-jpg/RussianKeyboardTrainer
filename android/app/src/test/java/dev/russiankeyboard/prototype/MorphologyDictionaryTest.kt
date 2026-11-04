package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

class MorphologyDictionaryTest {
    @Test fun androidReaderAgreesWithPythonMorphologyOnPositiveAndNegativeForms() {
        val file = File(requireNotNull(System.getProperty("rsk.context")), "morphology.bin")
        val dictionary = file.inputStream().use {
            MorphologyDictionary(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
        }
        val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("morphology_parity.tsv.gz"))
        var count = 0
        GZIPInputStream(source).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                val (word, expected) = line.split('\t')
                assertEquals(word, expected == "1", dictionary.contains(word))
                count++
            }
        }
        assertTrue(count >= 10_000)
        for (word in listOf("работал", "работала", "дела", "елка", "ёлка")) assertTrue(word, dictionary.contains(word))
        val engine = RussianCorrectionEngine(morphology = { dictionary })
        assertNull(engine.onSpace("работал"))
        assertEquals("сегодня ", engine.onSpace("севодня")?.replacementWithSpace)
    }
}
