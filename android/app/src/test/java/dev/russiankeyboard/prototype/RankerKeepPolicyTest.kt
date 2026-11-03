package dev.russiankeyboard.prototype

import dev.russiankeyboard.prototype.RankerKeepPolicy.Candidate
import dev.russiankeyboard.prototype.RankerKeepPolicy.Kind
import org.junit.Assert.*
import org.junit.Test

class RankerKeepPolicyTest {
    private val keep = Candidate("севодня", 0.20f, isKeep = true)
    private val fix = Candidate("сегодня", 0.40f)

    private fun decide(
        items: List<Candidate> = listOf(keep, fix),
        margin: Float = 0.05f,
        requestRevision: Long = 8L,
        currentRevision: Long = 8L,
        sensitiveField: Boolean = false,
        allowGrammar: Boolean = false
    ) = RankerKeepPolicy.chooseReplacement(
        "севодня", items, margin, requestRevision, currentRevision,
        sensitiveField, allowGrammar
    )

    @Test fun spellingRequiresStrictlyBetterScoreThanKeepPlusMargin() {
        assertEquals("сегодня", decide())
        assertEquals("севодня", decide(margin = 0.20f))
        assertEquals("севодня", decide(items = listOf(keep, Candidate("сегодня", 0.10f))))
    }

    @Test fun staleOrSensitiveInputMustRemainUnchanged() {
        assertEquals("севодня", decide(requestRevision = 7L))
        assertEquals("севодня", decide(currentRevision = 9L))
        assertEquals("севодня", decide(sensitiveField = true))
    }

    @Test fun missingOrInvalidKeepBaselineFailsClosed() {
        assertEquals("севодня", decide(items = listOf(fix)))
        assertEquals("севодня", decide(items = listOf(
            Candidate("севодня", Float.NaN, isKeep = true), fix
        )))
        assertEquals("севодня", decide(items = listOf(
            Candidate("другое", 1f, isKeep = true), fix
        )))
    }

    @Test fun grammarCannotBeAppliedWithoutExplicitOptIn() {
        val grammar = Candidate("пошла", 100f, kind = Kind.GRAMMAR)
        assertEquals("севодня", decide(items = listOf(keep, grammar)))
        assertEquals("пошла", decide(items = listOf(keep, grammar), allowGrammar = true))
    }

    @Test fun excludesInvalidAndEmptyCandidates() {
        assertEquals("сегодня", decide(items = listOf(
            keep, Candidate("", 200f), Candidate("ошибка", Float.POSITIVE_INFINITY), fix
        )))
        assertEquals("севодня", decide(items = listOf(keep, Candidate("севодня", 99f))))
    }

    @Test fun picksHighestEligibleSpellingCandidate() {
        assertEquals("сегодня", decide(items = listOf(
            keep, Candidate("севодне", 0.30f), fix
        )))
    }

    @Test fun invalidKeepMarginIsRejected() {
        for (bad in listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                decide(margin = bad)
                fail("Expected invalid margin to fail: $bad")
            } catch (_: IllegalArgumentException) {
                // Never permit an uncalibrated or invalid correction threshold.
            }
        }
    }

    @Test fun emptyTypedTextRemainsUntouched() {
        assertEquals("", RankerKeepPolicy.chooseReplacement(
            "", listOf(Candidate("", 0f, isKeep = true), Candidate("текст", 1f)),
            0f, 1L, 1L
        ))
    }
}
