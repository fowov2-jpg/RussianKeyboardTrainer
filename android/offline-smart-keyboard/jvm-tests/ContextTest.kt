import dev.offline.smartkeyboard.core.BlockSearchCorrector
import dev.offline.smartkeyboard.core.ContextRanker
import dev.offline.smartkeyboard.core.FastCorrector
import java.io.File

fun main(args: Array<String>) {
    val entries = FastCorrector.parseDictionary(File(args[0]).readLines().asSequence())
    val context = ContextRanker.parse(File(args[1]).readLines().asSequence())
    val engine = BlockSearchCorrector(entries, context)
    var passed = 0
    fun assertWord(typed: String, prior: String?, expected: String) {
        val decision = engine.decide(typed, prior).text
        check(decision == expected) { "prior=$prior, $typed -> $decision; expected $expected" }
        passed++
    }
    assertWord("прывет", null, "привет")
    assertWord("сиводня", "ты", "сегодня")
    assertWord("хачу", "я", "хочу")
    assertWord("сможеш", "ты", "сможешь")
    assertWord("чемто", "ты", "чемто") // complex punctuation is not implemented
    assertWord("привет", "нет", "привет") // existing words are always protected
    assertWord("занят", "ты", "занят")
    assertWord("АБВГДейка", "ты", "АБВГДейка") // no magic reconstruction of a sentence
    assertWord("12345", "я", "12345")

    // Same edit distance and frequency: only a specific previous word can break the tie.
    val ambiguous = listOf(FastCorrector.Entry("верю", 1000), FastCorrector.Entry("варю", 1000))
    val hint = ContextRanker.parse(sequenceOf("я верю 1000", "суп варю 1000"))
    val contextual = BlockSearchCorrector(ambiguous, hint)
    assertWord("хачу", "я", "хочу")
    check(!contextual.decide("вирю").changed)
    check(contextual.decide("вирю", "я").text == "верю")
    check(contextual.decide("вирю", "суп").text == "варю")
    check(contextual.decide("верю", "суп").text == "верю")
    passed += 4

    check(engine.dictionarySize() >= 450) { "Expected curated lexicon > 450 entries" }
    println("PASS: $passed contextual + safety checks; dictionary entries=${engine.dictionarySize()}")
}
