import dev.offline.smartkeyboard.core.BlockSearchCorrector
import dev.offline.smartkeyboard.core.FastCorrector
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Synthetic, deterministic stress test of index growth; not a Russian correctness benchmark. */
fun main(args: Array<String>) {
    val count = args.firstOrNull()?.toIntOrNull()?.coerceIn(1000, 20000) ?: 10000
    val chars = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    val rng = Random(42)
    val words = LinkedHashMap<String, FastCorrector.Entry>()
    while (words.size < count) {
        val word = buildString {
            repeat(rng.nextInt(5, 10)) { append(chars[rng.nextInt(chars.length)]) }
        }
        words.putIfAbsent(word, FastCorrector.Entry(word, rng.nextInt(100, 15000)))
    }
    val list = words.values.toList()
    val before = usedMB()
    lateinit var engine: BlockSearchCorrector
    val buildMs = measureNanoTime { engine = BlockSearchCorrector(list) } / 1e6
    val after = usedMB()
    // Warm-up JVM JIT before measuring; query both exact and one substituted letter.
    val queries = List(1500) { i ->
        val base = list[(i * 23) % count].word
        if (i % 2 == 0) base else base.replaceRange(2, 3, "о")
    }
    repeat(250) { engine.decide(queries[it]) }
    val ns = LongArray(queries.size) { i -> measureNanoTime { engine.decide(queries[i]) } }
    ns.sort()
    fun percentile(p: Double) = ns[((ns.size - 1) * p).toInt()] / 1000.0
    println("syntheticEntries=$count, startup=${"%.1f".format(buildMs)}ms, approxDeltaHeap=${after - before}MiB")
    println("JVM p50=${"%.2f".format(percentile(0.5))}µs, p95=${"%.2f".format(percentile(0.95))}µs, p99=${"%.2f".format(percentile(0.99))}µs")
    println("NOT real Russian word list, NOT Android, NOT IME end-to-end response time")
}
private fun usedMB(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
}
