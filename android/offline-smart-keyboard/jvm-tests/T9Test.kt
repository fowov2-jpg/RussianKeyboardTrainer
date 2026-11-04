import dev.offline.smartkeyboard.core.FastCorrector
import dev.offline.smartkeyboard.core.T9Predictor
import java.io.File
import kotlin.system.measureNanoTime

fun t9Tests(dictionaryFile: String) {
    val entries = FastCorrector.parseDictionary(File(dictionaryFile).readLines().asSequence())
    val predictor = T9Predictor(entries)
    check(T9Predictor.encode("привет") == "564236")
    check(T9Predictor.encode("сегодня") == "6325359")
    check(T9Predictor.encode("чем-то") == null)
    check(T9Predictor.encode("QWERTY") == null)
    check(predictor.commit("564236") == "привет")
    check(predictor.commit("6325359") == "сегодня")
    check(predictor.commit("111") == null)
    check(predictor.preview("564236") == "привет")
    check(predictor.candidates("564").isNotEmpty())
    check(predictor.candidates("123").isEmpty())
    check(predictor.candidates("").isEmpty())
    check(predictor.commit("99999999999999999999999999999999") == null)
    val keys = listOf("564236", "6325359", "56", "6", "999", "523", "123")
    repeat(1000) { keys.forEach(predictor::candidates) }
    val n = 10000
    val observations = LongArray(n)
    repeat(n) { i -> observations[i] = measureNanoTime { predictor.candidates(keys[i % keys.size]) } }
    observations.sort()
    fun us(frac: Double) = "%.2f".format(observations[(n * frac).toInt()] / 1000.0)
    println("PASS: 12 T9 correctness checks")
    println("Desktop JVM ONLY: T9 candidate lookup p50=${us(.50)} us, p95=${us(.95)} us, p99=${us(.99)} us")
    println("These figures DO NOT establish end-to-end 20 ms on Android.")
}
