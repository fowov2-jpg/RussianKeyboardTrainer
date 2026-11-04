package dev.russiankeyboard.prototype

import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.security.MessageDigest

/** Test-only corpus and reporting. Never reads user input or enters the product APK. */
data class ExtendedCase(val id: String, val kind: String, val input: String, val expected: String,
                        val reference: String, val provenance: String) {
    companion object {
        fun read(source: InputStream): List<ExtendedCase> {
            val buffered = source.buffered()
            buffered.mark(2)
            val compressed = buffered.read() == 31 && buffered.read() == 139
            buffered.reset()
            // aapt transparently expands .gz assets and strips their extension.
            return (if (compressed) GZIPInputStream(buffered) else buffered).bufferedReader().use { reader ->
                reader.readLine()
                reader.lineSequence().map { line ->
                    val f = line.split('\t')
                    fun text(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
                    ExtendedCase(f[0], f[1], text(f[2]), text(f[3]), f[4], f[5])
                }.toList().also {
                    require(it.size == 7500 && it.map { case -> case.input }.toSet().size == 7500)
                }
            }
        }
    }
}

class ExtendedWordQa(private val engine: RussianCorrectionEngine, private val morphology: MorphologyDictionary,
                     private val spelling: SpellingIndex) {
    fun run(case: ExtendedCase): Map<String, Any?> {
        val controller = LiveSpellingController(engine, { true },
            { morphology.hasPrefix(it) || spelling.hasPrefix(it) },
            { morphology.hasPrefix(it) || spelling.hasContinuation(it) })
        val undo = CorrectionUndoState()
        var shown = ""
        var brokenEdit = false
        val start = System.nanoTime()
        for (key in case.input) {
            undo.clear(); shown += key
            val plan = controller.appended(shown, key.toString())
            plan?.edit?.let {
                if (!shown.endsWith(it.expectedSuffix)) brokenEdit = true
                else shown = shown.dropLast(it.expectedSuffix.length) + it.replacementWithSpace
            }
            plan?.checkpoint?.let { undo.record(it) }
        }
        val elapsed = System.nanoTime() - start
        var restored = shown
        val count = undo.suffixLength()
        if (count > 0) undo.takeIfMatching(shown.takeLast(count))?.let { restored = shown.dropLast(count) + it }
        val baseline = engine.onSpace(case.input)?.replacementWithSpace?.trimEnd() ?: case.input
        val boundary = engine.onSpace(shown)?.replacementWithSpace?.trimEnd() ?: shown
        val validInput = morphology.contains(case.input.lowercase())
        val literal = case.kind != "typo"
        val unrelated = shown !in setOf(case.input, case.expected, baseline)
        val contract = !brokenEdit && restored == case.input &&
            (if (literal) shown == case.input && boundary == case.input else !unrelated)
        return linkedMapOf("id" to case.id, "kind" to case.kind, "input" to case.input, "gold" to case.expected,
            "actual" to shown, "boundary" to boundary, "baseline" to baseline,
            "valid_morphology_input" to validInput, "undo_restores_raw" to (restored == case.input),
            "contract_passed" to contract, "exact_gold" to (shown == case.expected),
            "boundary_exact_gold" to (boundary == case.expected), "kept" to (shown == case.input),
            "elapsed_ns" to elapsed, "provenance" to case.provenance)
    }
}

object ExtendedPhraseQa {
    private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")
    fun run(case: ExtendedCase, output: String, elapsed: Long): Map<String, Any?> {
        fun tokens(text: String) = words.findAll(text).map { it.value.lowercase() }.toList()
        val sourcePunctuation = case.input.filter { !it.isLetterOrDigit() && !it.isWhitespace() }
        val outputPunctuation = output.filter { !it.isLetterOrDigit() && !it.isWhitespace() }
        var at = 0
        for (mark in outputPunctuation) if (at < sourcePunctuation.length && sourcePunctuation[at] == mark) at++
        fun commas(text: String): Set<Int> {
            val result = mutableSetOf<Int>()
            val matches = words.findAll(text).toList()
            for ((i, word) in matches.withIndex()) {
                val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
                if (',' in text.substring(word.range.last + 1, end)) result.add(i)
            }
            return result
        }
        val actualCommas = commas(output); val goldCommas = commas(case.expected)
        val contract = tokens(case.input) == tokens(output) && at == sourcePunctuation.length
        return linkedMapOf("id" to case.id, "kind" to case.kind, "input" to case.input, "gold" to case.expected,
            "actual" to output, "contract_passed" to contract, "exact_gold" to (output == case.expected),
            "kept" to (output == case.input), "gold_question" to case.expected.trimEnd().endsWith('?'),
            "actual_question" to output.trimEnd().endsWith('?'),
            "comma_tp" to actualCommas.intersect(goldCommas).size,
            "comma_fp" to (actualCommas - goldCommas).size, "comma_fn" to (goldCommas - actualCommas).size,
            "elapsed_ns" to elapsed, "provenance" to case.provenance, "tatoeba_id" to case.reference)
    }
}

class ExtendedQaReport(private val scope: String) {
    val rows = mutableListOf<Map<String, Any?>>()
    fun summary(): Map<String, Any?> {
        val groups = rows.groupBy { it["kind"] as String }.mapValues { (_, cases) ->
            linkedMapOf("cases" to cases.size, "contract_failures" to cases.count { it["contract_passed"] != true },
                "exact_gold" to cases.count { it["exact_gold"] == true }, "kept" to cases.count { it["kept"] == true },
                "boundary_exact_gold" to cases.count { it["boundary_exact_gold"] == true },
                "valid_morphology_inputs" to cases.count { it["valid_morphology_input"] == true })
        }
        val restored = rows.filter { it["kind"] == "restore" }
        fun sum(key: String) = restored.sumOf { (it[key] as? Int) ?: 0 }
        val times = restored.map { (it["elapsed_ns"] as Long) / 1e6 }.sorted()
        val tp = sum("comma_tp"); val fp = sum("comma_fp"); val fn = sum("comma_fn")
        return linkedMapOf("scope" to scope, "cases" to rows.size,
            "cases_sha256" to MessageDigest.getInstance("SHA-256").digest(casesTsv().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
            "unique_inputs" to rows.map { it["input"] }.toSet().size,
            "contract_failures" to rows.count { it["contract_passed"] != true }, "by_kind" to groups,
            "comma_restore" to mapOf("tp" to tp, "fp" to fp, "fn" to fn,
                "precision" to if (tp + fp == 0) null else tp.toDouble() / (tp + fp),
                "recall" to if (tp + fn == 0) null else tp.toDouble() / (tp + fn)),
            "question_restore" to mapOf("tp" to restored.count { it["gold_question"] == true && it["actual_question"] == true },
                "fp" to restored.count { it["gold_question"] != true && it["actual_question"] == true },
                "fn" to restored.count { it["gold_question"] == true && it["actual_question"] != true }),
            "restore_latency_ms" to if (times.isEmpty()) null else mapOf("p50" to times[times.size / 2], "p95" to times[times.size * 95 / 100], "max" to times.last()),
            "sample_annotation_differences" to rows.filter { it["kind"] == "restore" && it["exact_gold"] != true }.take(12),
            "sample_changed_annotated_phrases" to rows.filter { it["kind"] == "phrase_keep" && it["kept"] != true }.take(12),
            "sample_contract_failures" to rows.filter { it["contract_passed"] != true }.take(12),
            "limitations" to "Public corpus/synthetic evaluation, not human-error accuracy; annotation differences are NOT counted as successful restoration; pretrained model training overlap unknown; component tests, not 7500 UI touch sequences or physical-phone latency")
    }
    fun json(): String = jsonValue(summary())
    fun casesTsv(): String {
        val fields = listOf("id", "kind", "input", "gold", "actual", "boundary", "contract_passed", "exact_gold", "kept", "provenance")
        fun safe(value: Any?) = value?.toString().orEmpty().replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
        return fields.joinToString("\t") + "\n" + rows.joinToString("\n") { row -> fields.joinToString("\t") { safe(row[it]) } } + "\n"
    }
    companion object {
        fun jsonValue(value: Any?): String = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { jsonValue(it.key.toString()) + ":" + jsonValue(it.value) }
            is Iterable<*> -> value.joinToString(",", "[", "]") { jsonValue(it) }
            else -> "\"" + value.toString().map { when (it) {
                '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
                else -> if (it.code < 32) "\\u" + it.code.toString(16).padStart(4, '0') else it.toString()
            } }.joinToString("") + "\""
        }
    }
}
