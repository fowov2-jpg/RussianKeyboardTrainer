package dev.offline.smartkeyboard.ime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import dev.offline.smartkeyboard.core.ContextRanker
import dev.offline.smartkeyboard.core.BlockSearchCorrector
import dev.offline.smartkeyboard.core.FastCorrector
import java.util.Locale
import java.util.concurrent.Executors

/** Normal touchscreen QWERTY; 2..9 blocks are used ONLY inside word retrieval. */
class OfflineKeyboardService : InputMethodService() {
    private lateinit var engine: BlockSearchCorrector
    private val uiHandler = Handler(Looper.getMainLooper())
    private val predictionWorker = Executors.newSingleThreadExecutor()
    private var pendingPrediction: Runnable? = null
    private var predictionRevision = 0L
    private val ru = Locale.forLanguageTag("ru-RU")
    private val original = StringBuilder()
    private val suggestionButtons = mutableListOf<Button>()
    private var suggestions: List<BlockSearchCorrector.Suggestion> = emptyList()
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
        engine = BlockSearchCorrector(dictionary, phrases)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        reset()
        val type = attribute?.inputType ?: InputType.TYPE_NULL
        val kind = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (kind == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val ordinaryText = kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        )
        val noSuggestions = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val noLearning = attribute != null &&
            (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        autocorrectEnabled = ordinaryText && !password && !noSuggestions && !noLearning
        numeric = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE
    }

    override fun onFinishInput() {
        reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
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
        updateSuggestions()
        if (word.length < 2 || !autocorrectEnabled) return
        val task = Runnable {
            predictionWorker.execute {
                val decision = engine.decide(word, before)
                uiHandler.post {
                    if (revision == predictionRevision &&
                        original.toString() == word && previousWord == before) {
                        suggestions = decision.suggestions
                        currentInputConnection?.setComposingText(
                            capitalize(decision.text, capitalizeWord), 1
                        )
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
        lastUndo = null
        previousWord = null
        shift = false
        sentenceStart = true
        capitalizeWord = false
        numeric = false
    }

    override fun onCreateInputView(): View = makeKeyboard()

    private fun makeKeyboard(): View {
        suggestionButtons.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(4), dp(1), dp(6))
            setBackgroundColor(Color.rgb(218, 222, 231))
        }
        val suggestionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(3) { index ->
            val button = key("", 1f, 43) { choose(index) }
            suggestionButtons.add(button)
            suggestionRow.addView(button)
        }
        root.addView(suggestionRow)
        val lines = if (numeric) listOf("123", "456", "789", ".0-")
            else listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        for (line in lines) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (letter in line) {
                row.addView(key(if (shift) letter.uppercaseChar().toString() else letter.toString(), 1f, 64) {
                    press(letter)
                })
            }
            root.addView(row)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(key(if (numeric) "РУС" else "123", 1.0f, 50) {
            finishWord()
            numeric = !numeric
            redraw()
        })
        controls.addView(key("⇧", .65f, 50) { shift = !shift; redraw() })
        controls.addView(key(",", .55f, 50) { punctuation(",") })
        controls.addView(key("Пробел", 2.8f, 50) { space() })
        controls.addView(key(".", .55f, 50) { punctuation(".") })
        controls.addView(key("⌫", .75f, 50) { backspace() })
        root.addView(controls)
        val extra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        extra.addView(key("?", 1f, 40) { punctuation("?") })
        extra.addView(key("!", 1f, 40) { punctuation("!") })
        extra.addView(key("↵", 2f, 40) { enter() })
        root.addView(extra)
        updateSuggestions()
        return root
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun key(textValue: String, weight: Float, heightDp: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            textSize = if (textValue.length > 5) 12f else 17f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(26, 34, 45))
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(heightDp), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setOnClickListener { action() }
        }

    private fun redraw() = setInputView(makeKeyboard())

    private fun press(char: Char) {
        if (numeric || !autocorrectEnabled || !char.isLetter()) {
            finishWord()
            currentInputConnection?.commitText(char.toString(), 1)
            return
        }
        lastUndo = null
        if (original.isEmpty()) capitalizeWord = sentenceStart || shift
        original.append(char)
        sentenceStart = false
        // Commit the raw composing text immediately: input must not wait for the dictionary.
        currentInputConnection?.setComposingText(capitalize(original.toString(), capitalizeWord), 1)
        schedulePrediction()
        shift = false
    }

    private fun capitalize(word: String, enabled: Boolean): String =
        if (enabled) word.replaceFirstChar { it.titlecase(ru) } else word

    private fun choose(index: Int) {
        if (original.isEmpty()) return
        invalidatePrediction()
        val suggestion = suggestions.getOrNull(index) ?: return
        val before = capitalize(original.toString(), capitalizeWord)
        val after = capitalize(suggestion.word, capitalizeWord)
        currentInputConnection?.commitText("$after ", 1)
        lastUndo = if (before != after) Undo(before, after, previousWord) else null
        previousWord = suggestion.word.lowercase(ru)
        original.clear()
        suggestions = emptyList()
        updateSuggestions()
    }

    private fun updateSuggestions() {
        suggestionButtons.forEachIndexed { index, button ->
            val word = suggestions.getOrNull(index)?.word
            button.text = word?.let { capitalize(it, capitalizeWord) }.orEmpty()
            button.isEnabled = word != null
        }
    }

    private fun finishWord(): String? {
        if (original.isEmpty()) return null
        invalidatePrediction()
        val source = capitalize(original.toString(), capitalizeWord)
        val correction = engine.decide(original.toString(), previousWord)
        val finalWord = capitalize(correction.text, capitalizeWord)
        currentInputConnection?.commitText(finalWord, 1)
        original.clear()
        suggestions = emptyList()
        updateSuggestions()
        lastUndo = if (source != finalWord) Undo(source, finalWord, previousWord) else null
        previousWord = finalWord.lowercase(ru)
        return finalWord
    }

    private fun space() {
        invalidatePrediction()
        val before = if (original.isNotEmpty()) capitalize(original.toString(), capitalizeWord) else null
        val corrected = if (original.isNotEmpty()) {
            val value = engine.decide(original.toString(), previousWord).text
            capitalize(value, capitalizeWord)
        } else null
        currentInputConnection?.commitText((corrected ?: "") + " ", 1)
        original.clear()
        suggestions = emptyList()
        updateSuggestions()
        lastUndo = if (before != null && corrected != before) Undo(before, corrected!!, previousWord) else null
        if (corrected != null) previousWord = corrected.lowercase(ru)
        shift = false
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
            // Undo last replacement only if text directly before cursor still matches.
            val actual = connection.getTextBeforeCursor(undo.corrected.length + 1, 0)?.toString()
            if (actual == "${undo.corrected} " &&
                connection.deleteSurroundingTextInCodePoints(undo.corrected.codePointCount(0, undo.corrected.length) + 1, 0)) {
                original.append(undo.original.lowercase(ru))
                previousWord = undo.previousBefore
                capitalizeWord = undo.original.firstOrNull()?.isUpperCase() == true
                connection.setComposingText(undo.original, 1)
                // The corrected word was not accepted; do not use it as context.
                schedulePrediction()
                return
            }
        }
        if (original.isNotEmpty()) {
            original.deleteCharAt(original.lastIndex)
            if (original.isEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
                suggestions = emptyList()
            } else {
                connection.setComposingText(capitalize(original.toString(), capitalizeWord), 1)
                schedulePrediction()
            }
            updateSuggestions()
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
