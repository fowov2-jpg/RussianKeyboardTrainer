import dev.offline.smartkeyboard.core.BlockSearchCorrector
import dev.offline.smartkeyboard.core.FastCorrector
import dev.offline.smartkeyboard.core.T9Predictor
import java.io.File
import kotlin.system.measureNanoTime

fun main(args: Array<String>) {
    val entries = FastCorrector.parseDictionary(File(args[0]).readLines().asSequence())
    val engine = BlockSearchCorrector(entries)
    val checks = linkedMapOf(
        "прывет" to "привет",
        "превет" to "привет",
        "сиводня" to "сегодня",
        "сможеш" to "сможешь",
        "чимто" to "чем-то",
        "привет" to "привет",
        "сегодня" to "сегодня",
        "АБВГДейка" to "АБВГДейка",
        "1234" to "1234"
    )
    checks.forEach { (raw, expected) ->
        val result = engine.decide(raw).text
        check(result == expected) { "$raw -> $result; expected $expected" }
    }
    // Same 2..9 block sequence can represent different real words.
    val ambiguous = BlockSearchCorrector(listOf(
        FastCorrector.Entry("кот", 10), FastCorrector.Entry("лот", 200),
        FastCorrector.Entry("кит", 15)
    ))
    check(T9Predictor.encode("кот") == T9Predictor.encode("лот"))
    check(ambiguous.suggestions("кот").first().word == "кот")
    check(ambiguous.suggestions("кот").any { it.word == "лот" })
    check(ambiguous.decide("кот").text == "кот")
    val suggestions = engine.suggestions("прывет")
    check(suggestions.any { it.word == "привет" }) { "No 'привет': $suggestions" }
    repeat(1000) { engine.decide("прывет") }
    val samples = LongArray(5000) {
        measureNanoTime { engine.decide("прывет") }
    }.sorted()
    println("PASS: ${checks.size} correction checks + ambiguous block-index ranking checks")
    println("Local desktop JVM lookup only, p50=${samples[2500]/1000.0} µs, p95=${samples[4750]/1000.0} µs")
    println("NOT an Android latency measurement and NOT a validated 20 ms bound")
}
