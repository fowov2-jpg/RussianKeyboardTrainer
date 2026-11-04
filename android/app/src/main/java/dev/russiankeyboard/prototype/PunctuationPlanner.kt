package dev.russiankeyboard.prototype

import kotlin.math.exp

/** Insertion-only punctuation policy: existing punctuation and lexical text are retained. */
object PunctuationPlanner {
    enum class Mode { STREAMING, FINALIZE, REVIEW }
    data class Prediction(val punctuation: Int, val probability: Double, val margin: Double,
                          val casing: Int, val casingProbability: Double)
    data class Result(val text: String, val provisionalTerminal: String?, val finalizedText: String? = null)
    private val marks = listOf(".", ",", "?", " —", "!", ":", ";", "-", "?!", "…", "")
    private val questionWords = setOf("как", "кто", "что", "где", "куда", "откуда", "когда", "почему", "зачем", "сколько", "какой", "какая", "какие", "чей")
    private val unfinished = setOf("и", "но", "а", "или", "что", "если", "чтобы", "потому", "на", "в", "у", "к", "с", "из", "для", "от", "до", "про", "по", "без", "при")
    private val greetingWords = setOf("привет", "здравствуй", "здравствуйте", "добрый", "доброе", "день", "утро", "вечер")
    private val subjects = setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они")
    private val copulas = setOf("быть", "стать", "становиться", "остаться", "оставаться")
    // Closed grammatical classes, not names learned from the evaluation corpus.
    private val lowercaseInsideSentence = subjects + setOf(
        "меня", "мне", "мной", "тебя", "тебе", "тобой", "его", "ему", "им", "него", "нему",
        "её", "ее", "ей", "неё", "нее", "ней", "нас", "нам", "нами", "вас", "вам", "вами",
        "их", "ими", "них", "ним", "себя", "себе", "собой", "это", "этот", "эта", "эти",
        "этого", "этому", "этой", "этим", "этих", "этом", "то", "та", "те", "того", "тому",
        "такой", "такая", "такие", "кто", "что", "чем", "чего", "кому", "кого", "который",
        "которая", "которые", "которое", "как", "где", "куда", "откуда", "когда", "почему",
        "зачем", "сколько", "чей", "чья", "чьи", "и", "а", "но", "или", "либо", "если",
        "чтобы", "поскольку", "хотя", "ведь", "же", "ли", "бы", "не", "ни", "даже", "тоже",
        "также", "только", "уже", "ещё", "еще", "в", "во", "на", "к", "ко", "с", "со",
        "у", "о", "об", "обо", "от", "до", "по", "за", "из", "для", "без", "при", "про"
    )

    private fun ambiguousComma(index: Int, words: List<PunctuationTokenizer.Word>, original: String): Boolean {
        val left = words[index].text.lowercase()
        val next = words[index + 1].text.lowercase()
        // A final two-word clause can be subject + nominal predicate ("вы друзья").
        // A comma would turn it into a vocative. Without syntax evidence, abstain.
        val afterNext = words.getOrNull(index + 2)
        val finalInClause = afterNext == null ||
            original.substring(words[index + 1].end, afterNext.start).any { it in ".!?…;\n" }
        if (left in subjects && finalInClause) return true
        // "хочу быть как ты": the comparison supplies the predicate itself.
        return left in copulas && next == "как"
    }

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
             predictions: List<Prediction>, mode: Mode, ordinaryWords: Set<String> = emptySet()): Result? {
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
            val currentInternalWord = mode == Mode.STREAMING && index > 0 && !actualBoundary && word.end == original.length
            // A pronoun/function word is not a proper name merely because the
            // model predicts capitalisation. Real boundaries remain eligible;
            // other words (names, places, acronyms) retain the existing policy.
            val lower = word.text.lowercase()
            val caseBoundary = actualBoundary || mode == Mode.REVIEW ||
                lower !in lowercaseInsideSentence && lower !in ordinaryWords
            if (caseBoundary && !currentInternalWord && !word.protected && !word.preserveCase && !((encoding.cutFront || !encoding.sentenceStart) && index == 0)) {
                if (consistentCase && prediction.casing == 0 && prediction.casingProbability >= .7) value = value.replaceFirstChar { it.uppercase() }
                if (prediction.casing == 2 && prediction.casingProbability >= .95 && value.length <= 5) value = value.uppercase()
            }
            out.append(value)
            val last = index == words.lastIndex
            val gapEnd = if (last) original.length else words[index + 1].start
            val gap = original.substring(word.end, gapEnd)
            if (!word.protected && gap.all { it.isWhitespace() } && prediction.punctuation != 10) {
                val allowed = when {
                    !last && prediction.punctuation == 1 -> prediction.probability >= .55 && prediction.margin >= .12 &&
                        (mode == Mode.REVIEW || !ambiguousComma(index, words, original))
                    !last -> false
                    mode != Mode.STREAMING -> prediction.probability >= .52 && prediction.margin >= .08 ||
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
