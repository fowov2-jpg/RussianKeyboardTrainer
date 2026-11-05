package dev.offline.smartkeyboard.ime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import dev.offline.smartkeyboard.core.ContextRanker
import dev.offline.smartkeyboard.core.BlockSearchCorrector
import dev.offline.smartkeyboard.core.FastCorrector
import dev.offline.smartkeyboard.core.GrammarHints
import dev.offline.smartkeyboard.core.CorpusTypos
import java.util.Locale
import java.util.concurrent.Executors

/** Normal touchscreen ЙЦУКЕН; hidden T9 blocks 1..9 stay inside word retrieval. */
class OfflineKeyboardService : InputMethodService() {
    @Volatile private lateinit var engine: BlockSearchCorrector
    @Volatile private var destroyed = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val predictionWorker = Executors.newSingleThreadExecutor()
    private var pendingPrediction: Runnable? = null
    private var predictionRevision = 0L
    private val ru = Locale.forLanguageTag("ru-RU")
    private val original = StringBuilder()
    private var keyboardView: KeyboardTouchView? = null
    private var suggestions: List<BlockSearchCorrector.Suggestion> = emptyList()
    private var lastPrediction: BlockSearchCorrector.Decision? = null
    private var predictedWord: String? = null
    private var autocorrectEnabled = false
    private var numeric = false
    private var shift = false
    private var sentenceStart = true
    private var capitalizeWord = false
    private var lastUndo: Undo? = null
    // Session-only. Never persisted, and reset between input fields.
    private var previousWord: String? = null

    private data class Undo(val original: String, val corrected: String, val previousBefore: String?)

    override fun onCreate() {
        super.onCreate()
        val dictionary = assets.open("ru_words.txt").bufferedReader().use {
            FastCorrector.parseDictionary(it.lineSequence())
        }
        val phrases = assets.open("ru_bigrams.txt").bufferedReader().use {
            ContextRanker.parse(it.lineSequence())
        }
        val grammarHints = assets.open("ru_verb_forms.txt").bufferedReader().use {
            GrammarHints.parse(it.lineSequence())
        }
        // Keep the proven small dictionary active immediately.
        engine = BlockSearchCorrector(dictionary, phrases, grammarHints)
        // The much larger dictionary is prepared off the input/UI thread,
        // while taps and spelling correction with the base lexicon continue.
        predictionWorker.execute {
            try {
                val expanded = assets.open("ru_words_leeds_10k.txt").bufferedReader().use {
                    FastCorrector.parseDictionary(it.lineSequence())
                }
                // Corpus training supplies aggregate counts and curated
                // spelling candidates, not a large neural network.
                // Every resource is bundled; no runtime network requests.
                val learnedForms = assets.open("ru_gec_wordforms.txt").bufferedReader().use {
                    FastCorrector.parseDictionary(it.lineSequence())
                }
                val learnedPhrases = assets.open("ru_gec_bigrams.txt").bufferedReader().use {
                    it.readLines()
                }
                val basePhrases = assets.open("ru_bigrams.txt").bufferedReader().use {
                    it.readLines()
                }
                // Handwritten conversational rules override generic corpus pairs.
                val combinedContext = ContextRanker.parse(
                    (learnedPhrases + basePhrases).asSequence()
                )
                val learnedTypos = assets.open("ru_rlc_repeated_typos.txt").bufferedReader().use {
                    CorpusTypos.parse(it.lineSequence())
                }
                val originalWords = dictionary.map { it.word }.toSet()
                val upgraded = BlockSearchCorrector(
                    dictionary + expanded + learnedForms,
                    combinedContext, grammarHints, originalWords, learnedTypos
                )
                if (!destroyed) {
                    engine = upgraded
                    uiHandler.post {
                        if (!destroyed && original.isNotEmpty()) schedulePrediction()
                    }
                }
            } catch (_: Exception) {
                // The base dictionary remains available if an asset cannot load.
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        reset()
        val type = attribute?.inputType ?: InputType.TYPE_NULL
        val kind = type and InputType.TYPE_MASK_CLASS
        // Chat editors may disallow learning, but still permit local correction.
        // No personalization is ever stored by this keyboard.
        autocorrectEnabled = AutocorrectPolicy.enabled(type)
        numeric = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Input fields can change between text and numeric modes.
        redraw()
    }

    override fun onFinishInput() {
        reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        invalidatePrediction()
        predictionWorker.shutdownNow()
        super.onDestroy()
    }

    private fun invalidatePrediction() {
        ++predictionRevision
        pendingPrediction?.let { uiHandler.removeCallbacks(it) }
        pendingPrediction = null
    }

    /**
     * Never search the dictionary synchronously for each character.
     * Only run the heavy lookup after a short idle window.
     */
    private fun schedulePrediction() {
        invalidatePrediction()
        val revision = predictionRevision
        val word = original.toString()
        val before = previousWord
        suggestions = emptyList()
        lastPrediction = null
        predictedWord = null
        updateSuggestions()
        if (word.length < 2 || !autocorrectEnabled) return
        val task = Runnable {
            predictionWorker.execute {
                // All dictionary work is off the UI thread. The completion
                // cache is only used for visible hints, NEVER as an autocorrect.
                val activeIndex = engine
                val decision = activeIndex.decide(word, before)
                val displaySuggestions = (
                    decision.suggestions + activeIndex.completions(word)
                ).distinctBy { it.word }.take(3)
                uiHandler.post {
                    if (revision == predictionRevision &&
                        original.toString() == word && previousWord == before) {
                        suggestions = displaySuggestions
                        lastPrediction = decision
                        predictedWord = word
                        // Suggestions only; never rewrite the editor during typing.
                        updateSuggestions()
                    }
                }
            }
        }
        pendingPrediction = task
        uiHandler.postDelayed(task, 85L)
    }

    private fun reset() {
        invalidatePrediction()
        original.clear()
        suggestions = emptyList()
        lastPrediction = null
        predictedWord = null
        lastUndo = null
        previousWord = null
        shift = false
        sentenceStart = true
        capitalizeWord = false
        numeric = false
    }

    override fun onCreateInputView(): View {
        return KeyboardTouchView(this, ::onKeyboardAction).also {
            keyboardView = it
            updateSuggestions()
        }
    }

    private fun onKeyboardAction(code: String) {
        when {
            code.startsWith("CHAR:") -> code.removePrefix("CHAR:").singleOrNull()?.let(::press)
            code.startsWith("SUGGEST:") ->
                code.removePrefix("SUGGEST:").toIntOrNull()?.let(::choose)
            code == "DELETE" -> backspace()
            code == "SHIFT" -> {
                shift = !shift
                updateShiftLabels()
            }
            code == "SPACE" -> space()
            code == "COMMA" -> punctuation(",")
            code == "PERIOD" -> punctuation(".")
            code == "ENTER" -> enter()
            code == "MODE" -> {
                finishWord()
                numeric = !numeric
                redraw()
            }
        }
    }

    private fun redraw() {
        keyboardView?.update(
            modeNumeric = numeric,
            shift = shift,
            words = suggestions.map { capitalize(it.word, capitalizeWord) },
            current = if (original.isEmpty()) "" else capitalize(original.toString(), capitalizeWord),
            correctionEnabled = autocorrectEnabled
        )
    }

    private fun updateShiftLabels() = redraw()

    private fun press(char: Char) {
        if (numeric || !char.isLetter()) {
            finishWord()
            currentInputConnection?.commitText(char.toString(), 1)
            return
        }
        val first = original.isEmpty()
        val capital = shift || (first && sentenceStart)
        val visible = if (capital) char.uppercaseChar() else char
        lastUndo = null
        currentInputConnection?.commitText(visible.toString(), 1)
        sentenceStart = false
        if (autocorrectEnabled) {
            if (first) capitalizeWord = capital
            original.append(char.lowercaseChar())
            schedulePrediction()
        }
        shift = false
        updateShiftLabels()
    }

    private fun capitalize(word: String, enabled: Boolean): String =
        if (enabled) word.replaceFirstChar { it.titlecase(ru) } else word

    /**
     * Replace only our word at the cursor. Never delete unrelated editor text
     * when the user or host application moved the selection.
     */
    private fun replaceCurrentWord(source: String, corrected: String): Boolean {
        if (source == corrected) return true
        val connection = currentInputConnection ?: return false
        if (connection.getTextBeforeCursor(source.length, 0)?.toString() != source) return false
        connection.beginBatchEdit()
        try {
            if (!connection.deleteSurroundingTextInCodePoints(
                    source.codePointCount(0, source.length), 0
                )) return false
            connection.commitText(corrected, 1)
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    private fun choose(index: Int) {
        if (original.isEmpty()) return
        val suggestion = suggestions.getOrNull(index) ?: return
        val source = capitalize(original.toString(), capitalizeWord)
        val replacement = capitalize(suggestion.word, capitalizeWord)
        if (!replaceCurrentWord(source, replacement)) return
        currentInputConnection?.commitText(" ", 1)
        lastUndo = if (source != replacement) Undo(source, replacement, previousWord) else null
        previousWord = suggestion.word.lowercase(ru)
        original.clear()
        invalidatePrediction()
        suggestions = emptyList()
        updateSuggestions()
    }

    private fun updateSuggestions() = redraw()

    private fun finishWord(): String? {
        if (original.isEmpty()) return null
        val source = capitalize(original.toString(), capitalizeWord)
        // A fast typist may press space before the delayed suggestion completes.
        // Always run the cheap dictionary lookup at the word boundary.
        val decision = if (!autocorrectEnabled) null
            else if (predictedWord == original.toString()) lastPrediction
            else engine.decide(original.toString(), previousWord)
        var corrected = capitalize(decision?.text ?: original.toString(), capitalizeWord)
        if (!replaceCurrentWord(source, corrected)) corrected = source
        invalidatePrediction()
        original.clear()
        suggestions = emptyList()
        lastPrediction = null
        predictedWord = null
        updateSuggestions()
        lastUndo = if (source != corrected) Undo(source, corrected, previousWord) else null
        previousWord = corrected.lowercase(ru)
        return corrected
    }

    private fun space() {
        finishWord()
        currentInputConnection?.commitText(" ", 1)
        shift = false
        updateShiftLabels()
    }

    private fun punctuation(mark: String) {
        finishWord()
        currentInputConnection?.commitText(mark, 1)
        lastUndo = null
        if (mark in setOf(".", "!", "?")) {
            sentenceStart = true
            previousWord = null
        }
    }

    private fun backspace() {
        invalidatePrediction()
        val connection = currentInputConnection ?: return
        val undo = lastUndo
        lastUndo = null
        if (undo != null && original.isEmpty()) {
            val textBeforeCursor = connection.getTextBeforeCursor(undo.corrected.length + 1, 0)
                ?.toString()
            if (textBeforeCursor == "${undo.corrected} ") {
                connection.beginBatchEdit()
                try {
                    if (connection.deleteSurroundingTextInCodePoints(
                            undo.corrected.codePointCount(0, undo.corrected.length) + 1, 0
                        )) {
                        connection.commitText(undo.original, 1)
                        original.append(undo.original.lowercase(ru))
                        previousWord = undo.previousBefore
                        capitalizeWord = undo.original.firstOrNull()?.isUpperCase() == true
                        schedulePrediction()
                        return
                    }
                } finally {
                    connection.endBatchEdit()
                }
            }
        }
        if (original.isNotEmpty()) {
            original.deleteCharAt(original.lastIndex)
            if (!connection.deleteSurroundingTextInCodePoints(1, 0))
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            if (original.isEmpty()) {
                suggestions = emptyList()
                updateSuggestions()
            } else schedulePrediction()
        } else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun enter() {
        finishWord()
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (action in setOf(EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_NEXT)) {
            currentInputConnection?.performEditorAction(action)
        } else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        lastUndo = null
        sentenceStart = true
        previousWord = null
    }
}
