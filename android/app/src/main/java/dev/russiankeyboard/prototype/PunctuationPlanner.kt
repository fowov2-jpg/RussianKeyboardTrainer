package dev.russiankeyboard.prototype

import kotlin.math.exp

/** Insertion-only punctuation policy: existing punctuation and lexical text are retained. */
object PunctuationPlanner {
    enum class Mode { STREAMING, FINALIZE }
    data class Prediction(val punctuation: Int, val probability: Double, val margin: Double,
                          val casing: Int, val casingProbability: Double)
    data class Result(val text: String, val provisionalTerminal: String?, val finalizedText: String? = null)
    private val marks = listOf(".", ",", "?", " —", "!", ":", ";", "-", "?!", "…", "")
    private val questionWords = setOf("как", "кто", "что", "где", "куда", "откуда", "когда", "почему", "зачем", "сколько", "какой", "какая", "какие", "чей")
    private val unfinished = setOf("и", "но", "а", "или", "что", "если", "чтобы", "потому", "на", "в", "у", "к", "с", "из", "для", "от", "до", "про", "по", "без", "при")
    private val greetingWords = setOf("привет", "здравствуй", "здравствуйте", "добрый", "доброе", "день", "утро", "вечер")

    fun prediction(logits: FloatArray): Prediction {
        require(logits.size == 33 && logits.all { it.isFinite() })
        val maximum = logits.max().toDouble()
        val scores = logits.map { exp(it - maximum) }
        val total = scores.sum()
        val punctuation = DoubleArray(11)
        val casing = DoubleArray(3)
        for (i in scores.indices) { punctuation[i / 3] += scores[i] / total; casing[i % 3] += scores[i] / total }
        val order = punctuation.indices.sortedByDescending { punctuation[it] }
        val case = casing.indices.maxBy { casing[it] }
        return Prediction(order[0], punctuation[order[0]], punctuation[order[0]] - punctuation[order[1]], case, casing[case])
    }

    private fun directQuestion(words: List<PunctuationTokenizer.Word>, predictions: List<Prediction>): Boolean {
        if (words.size < 2 || words.last().text.lowercase() in unfinished) return false
        var start = 0
        while (start < words.size - 1 && words[start].text.lowercase() in greetingWords) start++
        // A vocative followed by a model-predicted comma can precede a question.
        if (start < words.size - 1 && predictions[start].punctuation == 1 && predictions[start].probability >= .5) start++
        return words.getOrNull(start)?.text?.lowercase() in questionWords
    }

    fun plan(original: String, encoding: PunctuationTokenizer.Encoding,
             predictions: List<Prediction>, mode: Mode): Result? {
        require(encoding.words.size == predictions.size)
        val words = encoding.words
        if (words.isEmpty() || words.none { it.text.any { c -> c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё' } }) return null
        val question = directQuestion(words, predictions)
        var cursor = 0
        var provisional: String? = null
        val out = StringBuilder()
        for ((index, word) in words.withIndex()) {
            out.append(original, cursor, word.start)
            val prediction = predictions[index]
            var value = word.text
            val previous = words.getOrNull(index - 1)
            val predictedBoundary = index > 0 && predictions[index - 1].punctuation in setOf(0, 2, 3, 4, 8, 9)
            val actualBoundary = previous == null || original.substring(previous.end, word.start).any { it in ".!?…\n" }
            // Do not apply casing for a sentence break that was NOT inserted.
            // In particular, keep lower-case words after a user's existing comma.
            val consistentCase = !predictedBoundary || actualBoundary
            if (!word.protected && !word.preserveCase && !((encoding.cutFront || !encoding.sentenceStart) && index == 0)) {
                if (consistentCase && prediction.casing == 0 && prediction.casingProbability >= .7) value = value.replaceFirstChar { it.uppercase() }
                if (prediction.casing == 2 && prediction.casingProbability >= .95 && value.length <= 5) value = value.uppercase()
            }
            out.append(value)
            val last = index == words.lastIndex
            val gapEnd = if (last) original.length else words[index + 1].start
            val gap = original.substring(word.end, gapEnd)
            if (!word.protected && gap.all { it.isWhitespace() } && prediction.punctuation != 10) {
                val allowed = when {
                    !last && prediction.punctuation == 1 -> prediction.probability >= .55 && prediction.margin >= .12
                    !last -> false
                    mode == Mode.FINALIZE -> prediction.probability >= .52 && prediction.margin >= .08 ||
                        (question && prediction.punctuation in setOf(2, 8) && prediction.probability >= .30 && prediction.margin >= .04)
                    else -> question && prediction.punctuation in setOf(2, 8) && prediction.probability >= .30 && prediction.margin >= .04
                }
                if (allowed) {
                    val mark = marks[prediction.punctuation]
                    out.append(mark)
                    if (last && mode == Mode.STREAMING) provisional = mark
                }
            }
            cursor = word.end
        }
        out.append(original, cursor, original.length)
        val updated = out.toString()
        return if (updated == original) null else Result(updated, provisional)
    }
}
