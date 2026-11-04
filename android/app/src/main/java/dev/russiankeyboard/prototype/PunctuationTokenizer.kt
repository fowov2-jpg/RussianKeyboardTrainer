package dev.russiankeyboard.prototype

/** Bert WordPiece for the pinned case-sensitive RUPunct tokenizer. UTF-16 editor offsets. */
class PunctuationTokenizer(vocabulary: List<String>) {
    data class Word(val text: String, val start: Int, val end: Int, val firstToken: Int,
                    val protected: Boolean, val preserveCase: Boolean)
    data class Encoding(val ids: LongArray, val words: List<Word>, val cutFront: Boolean, val sentenceStart: Boolean = true)
    private val vocab = vocabulary.withIndex().associate { it.value to it.index }
    private val wordPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val protectedPattern = Regex("(?i)(?:https?://|www\\.)\\S+|[\\w.+%-]+@[\\w.-]+\\.[a-z]{2,}|[@#][\\p{L}\\p{N}_]+|[\\p{L}\\p{N}]+(?:_[\\p{L}\\p{N}]+)+")
    init { require(vocab["[CLS]"] == 2 && vocab["[SEP]"] == 3 && vocab["[UNK]"] == 1) }

    fun pieces(word: String): List<Int> {
        if (word.length > 100) return listOf(1)
        val result = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (end > start) {
                val piece = (if (start == 0) "" else "##") + word.substring(start, end)
                found = vocab[piece]
                if (found != null) break
                end--
            }
            if (found == null) return listOf(1)
            result.add(found); start = end
        }
        return result
    }

    fun encode(text: String, context: String = "", maxTokens: Int = 192, sentenceStart: Boolean = true): Encoding {
        require(maxTokens in 32..256)
        val matches = wordPattern.findAll(text).toList()
        val protected = protectedPattern.findAll(text).map { it.range }.toList()
        // RUPunct restores case from bare lowercase ASR text. Keep the model
        // view stable while retaining original spelling/case/UTF-16 offsets.
        val target = matches.map { pieces(it.value.lowercase()) }
        var first = 0
        var size = target.sumOf { it.size }
        while (size > maxTokens - 3 && first < target.size) { size -= target[first].size; first++ }
        val contextBudget = minOf(40, maxTokens - size - 3).coerceAtLeast(0)
        val history = wordPattern.findAll(context).toList().takeLast(24).flatMap { pieces(it.value.lowercase()) }.takeLast(contextBudget)
        val ids = mutableListOf(2)
        if (history.isNotEmpty()) { ids.addAll(history); ids.add(3) }
        val words = mutableListOf<Word>()
        var quote = false
        var scanned = 0
        for (i in matches.indices) {
            val match = matches[i]
            for (char in text.substring(scanned, match.range.first)) {
                if (char in "«»\"“”") quote = !quote
            }
            scanned = match.range.last + 1
            if (i < first) continue
            val start = match.range.first; val end = match.range.last + 1
            val boundary = (start > 0 && text[start - 1] in "@#_/-") ||
                (end < text.length && text[end] in "_/-")
            val shielded = quote || boundary || protected.any { start <= it.last && end > it.first }
            val conventional = match.value == match.value.lowercase() || match.value == match.value.uppercase() ||
                match.value == match.value.lowercase().replaceFirstChar { it.uppercase() }
            words.add(Word(match.value, start, end, ids.size, shielded,
                !conventional || match.value.all { it.isUpperCase() } || match.value.any { it in 'a'..'z' || it in 'A'..'Z' }))
            ids.addAll(target[i])
        }
        ids.add(3)
        return Encoding(ids.map { it.toLong() }.toLongArray(), words, first > 0, sentenceStart)
    }
}
