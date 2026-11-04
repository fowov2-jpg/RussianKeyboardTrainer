package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.Test

class RussianCorrectionEngineTest {
    private val engine = RussianCorrectionEngine()

    @Test fun fixesObviousTypoAfterSpace() {
        val edit = engine.onSpace("Я севодня")!!
        assertEquals("севодня", edit.expectedSuffix)
        assertEquals("сегодня ", edit.replacementWithSpace)
        assertEquals(RussianCorrectionEngine.Reason.TYPO, edit.reason)
    }

    @Test fun preservesInitialUppercase() {
        assertEquals("Сегодня ", engine.onSpace("Севодня")?.replacementWithSpace)
    }

    @Test fun preservesAllCaps() {
        assertEquals("СЕГОДНЯ ", engine.onSpace("СЕВОДНЯ")?.replacementWithSpace)
    }

    @Test fun supportsSpacedReplacement() {
        assertEquals("потому что ", engine.onSpace("потомучто")?.replacementWithSpace)
    }

    @Test fun insertsCommaOnlyForCuratedPatterns() {
        val edit = engine.onSpace("Я думаю что")!!
        assertEquals("думаю что", edit.expectedSuffix)
        assertEquals("думаю, что ", edit.replacementWithSpace)
        assertEquals(RussianCorrectionEngine.Reason.COMMA, edit.reason)
    }

    @Test fun doesNotInsertCommaIntoSubstring() {
        assertNull(engine.onSpace("передумаю что"))
    }

    @Test fun leavesUnknownAndAmbiguousSentencesAlone() {
        assertNull(engine.onSpace("она мне очень понравился"))
        assertNull(engine.onSpace("ты вчера ходил"))
        assertNull(engine.onSpace("я написала что"))
        assertNull(engine.onSpace(""))
    }

    @Test fun avoidsFalseCorrectionsInsideLinksHandlesAndIdentifiers() {
        for (fragment in listOf(
            "@превет", "#превет", "site.превет",
            "https://example.com/превет", "ref_превет",
            "2превет", "abcпревет", "слово-превет",
            "/превет", "префикс:превет"
        )) {
            assertNull("Must not rewrite a suffix of $fragment", engine.onSpace(fragment))
        }
    }

    @Test fun allowsObviousTyposAfterSpacesAndOpeningQuotes() {
        assertEquals("привет ", engine.onSpace("Я превет")?.replacementWithSpace)
        assertEquals("привет ", engine.onSpace("(превет")?.replacementWithSpace)
        assertEquals("ПРИВЕТ ", engine.onSpace("«ПРЕВЕТ")?.replacementWithSpace)
    }

    @Test fun punctuationDoesNotRewriteHandlesOrIdentifierFragments() {
        for (fragment in listOf("@думаю что", "#думаю что", "id_думаю что",
            "путь/думаю что", "x.думаю что", "передумаю что")) {
            assertNull("Must not insert a comma in $fragment", engine.onSpace(fragment))
        }
        assertEquals("думаю, что ", engine.onSpace("(думаю что")?.replacementWithSpace)
    }

    @Test fun preservesDeliberateMixedCaseWords() {
        for (value in listOf("ПрЕвЕт", "пРЕВЕТ", "СеВодня", "иЗвЕнИтЕ")) {
            assertNull("Do not rewrite custom casing: $value", engine.onSpace(value))
        }
    }

    @Test fun preservesDeliberateMixedCaseInCommaPatterns() {
        for (value in listOf("ДуМаю что", "дуМАЮ что", "Думаю чТО", "зНаЮ что")) {
            assertNull("Do not rewrite custom casing: $value", engine.onSpace(value))
        }
        assertEquals("Думаю, что ", engine.onSpace("Думаю что")?.replacementWithSpace)
        assertEquals("ДУМАЮ, ЧТО ", engine.onSpace("ДУМАЮ ЧТО")?.replacementWithSpace)
    }

    @Test fun prefixLookupNarrowsCandidatesWithoutChangingText() {
        assertTrue(engine.suggestForPrefix("п", limit = 8).contains("привет"))
        assertTrue(engine.suggestForPrefix("пр", limit = 8).contains("привет"))
        assertTrue(engine.suggestForPrefix("при").contains("привет"))
        assertEquals(listOf("привет"), engine.suggestForPrefix("привет"))
        assertTrue(engine.suggestForPrefix("приыкет").isEmpty())
    }

    @Test fun prefixLookupProtectsShortWordsAndInvalidInputs() {
        assertTrue(engine.suggestForPrefix("как").contains("как"))
        assertTrue(engine.suggestForPrefix("не").contains("не"))
        assertTrue(engine.suggestForPrefix("что").contains("что"))
        assertTrue(engine.suggestForPrefix("ка").isNotEmpty())
        assertTrue(engine.suggestForPrefix("https://x").isEmpty())
        assertTrue(engine.suggestForPrefix("123").isEmpty())
        assertTrue(engine.suggestForPrefix("пр", 0).isEmpty())
    }

    @Test fun neverChangesSensitiveField() {
        assertNull(engine.onSpace("Я севодня", isSensitiveField = true))
        assertNull(engine.onSpace("Я думаю что", isSensitiveField = true))
    }
}
