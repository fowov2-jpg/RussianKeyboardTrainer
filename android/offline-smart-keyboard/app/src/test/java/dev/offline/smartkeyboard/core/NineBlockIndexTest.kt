package dev.offline.smartkeyboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineBlockIndexTest {
    @Test fun nineHiddenGroupsCoverEveryRussianLetterExactlyOnce() {
        val groups = T9Predictor.KEY_LETTERS
        assertEquals(('1'..'9').toList(), groups.keys.toList())
        val letters = groups.values.flatMap(String::toList)
        assertEquals(33, letters.size)
        assertEquals(33, letters.toSet().size)
        assertTrue(letters.containsAll("абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList()))
        // Keep common code sequences backward compatible.
        assertEquals("564236", T9Predictor.encode("привет"))
        assertEquals("6325359", T9Predictor.encode("сегодня"))
        assertTrue(T9Predictor.encode("ёлка")!!.startsWith("1"))
    }

    @Test fun dictionaryIsRoutedByItsFirstHiddenBlock() {
        val entries = listOf("ёжик", "привет", "проверим", "сегодня")
            .map { FastCorrector.Entry(it, 12000) }
        val index = NineBlockIndex()
        for (entry in entries)
            index.add(entry, T9Predictor.encode(entry.word)!!, true)

        assertEquals(4, index.blockSizes().values.sum())
        assertEquals(1, index.blockSizes()['1'])
        assertEquals(2, index.blockSizes()['5'])
        assertEquals(1, index.blockSizes()['6'])
        assertTrue(index.completions("564").any { it.word == "привет" })
        assertTrue(index.completions("1").any { it.word == "ёжик" })
        assertFalse(index.completions("5642").any { it.word == "сегодня" })
    }

    @Test fun firstLetterMissAndTwoMistakesStillFindOriginalWord() {
        val original = FastCorrector.Entry("привет", 22000)
        val index = NineBlockIndex()
        index.add(original, T9Predictor.encode(original.word)!!, true)

        fun matches(input: String): List<String> {
            val collected = ArrayList<String>()
            index.collect(T9Predictor.encode(input)!!) { collected.add(it.word) }
            return collected
        }
        assertTrue(matches("привет").contains("привет"))
        assertTrue("A wrong first digit must not be a dead end",
            matches("рривет").contains("привет"))
        assertTrue("One wrong first block plus another wrong block",
            matches("рпивет").contains("привет"))
    }

    @Test fun existingCorrectionExamplesStayCorrect() {
        val entries = listOf("привет", "сегодня", "проверим", "одной", "рукой")
            .map { FastCorrector.Entry(it, 20000) }
        val engine = BlockSearchCorrector(entries)
        assertEquals("привет", engine.decide("прывет").text)
        assertEquals("сегодня", engine.decide("сгодня").text)
        assertEquals("проверим", engine.decide("прверм").text)
    }
}
