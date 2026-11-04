import dev.offline.smartkeyboard.core.FastCorrector
import java.io.File
import kotlin.system.measureNanoTime

fun main(args: Array<String>) {
    val source = File(args.firstOrNull() ?: "app/src/main/assets/ru_words.txt")
    val engine = FastCorrector(FastCorrector.parseDictionary(source.readLines().asSequence()))
    val expected = mapOf(
        "прывет" to "привет", "сиводня" to "сегодня",
        "сможеш" to "сможешь", "чимто" to "чем-то",
        "привет" to "привет", "сегодня" to "сегодня",
        "АБВГДейка" to "АБВГДейка", "ты" to "ты",
        "1234" to "1234"
    )
    for ((input, output) in expected) {
        val actual = engine.guess(input).text
        check(actual == output) { "For '$input': got '$actual', expected '$output'" }
    }
    val corpus = listOf(
        "прывет", "превет", "сиводня", "сможеш", "чимто", "привет",
        "сегодня", "встреча", "пожалуйста", "клавиатура", "неизвестное"
    )
    repeat(500) { corpus.forEach(engine::guess) } // JVM warmup
    val iterations = 10000
    val samples = LongArray(iterations)
    repeat(iterations) { n ->
        samples[n] = measureNanoTime { engine.guess(corpus[n % corpus.size]) }
    }
    samples.sort()
    fun us(q: Double) = "%.2f".format(samples[(iterations * q).toInt().coerceAtMost(iterations - 1)] / 1000.0)
    println("PASS: ${expected.size} correctness checks")
    println("Desktop JVM ONLY (spell lookup, no UI/Android IPC), p50=${us(.50)} µs, p95=${us(.95)} µs, p99=${us(.99)} µs")
    println("These figures do NOT establish 20 ms on a phone.")
    t9Tests(source.path)
}
