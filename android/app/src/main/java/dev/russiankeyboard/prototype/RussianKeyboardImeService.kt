package dev.russiankeyboard.prototype

import android.graphics.Color
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import java.util.Locale

/**
 * Minimal offline Russian IME. Key events never wait for ML/network.
 * Android IME exposes only the active editor; it cannot access chat history.
 */
class RussianKeyboardImeService : InputMethodService() {
    private val engine = RussianCorrectionEngine(morphology = { LexiconLoader.morphology },
        lexicon = { LexiconLoader.current })
    private val undo = CorrectionUndoState()
    private var shifted = false
    private var english = false
    private var numeric = false
    private val letterButtons = mutableListOf<Pair<Button, String>>()
    private var suggestionButton: Button? = null
    private var alternativeButton: Button? = null
    private var thirdButton: Button? = null
    private var keepButton: Button? = null
    private var shownSuggestion: RussianCorrectionEngine.Suggestion? = null
    private var shownAlternative: RussianCorrectionEngine.Suggestion? = null
    private var shownThird: RussianCorrectionEngine.Suggestion? = null
    private var keepAfterUndo = false
    private val handler = Handler(Looper.getMainLooper())
    private val requests = LatestRequestQueue { handler.post(it) }
    private var refreshTask: Runnable? = null
    private var session = 0L
    private var selectionStart = -1
    private var selectionEnd = -1
    private data class Snapshot(val text: String, val session: Long, val start: Int, val end: Int)
    private var shownAt: Snapshot? = null
    private var previewAt: Snapshot? = null
    private var preview: ContextualAssistant.Review? = null
    private var lettersPanel: LinearLayout? = null
    private var previewPanel: LinearLayout? = null
    private var previewText: TextView? = null
    private var reviewButton: Button? = null
    private var numberButton: Button? = null
    private var languageButton: Button? = null
    private val preferences by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        LexiconLoader.warmUp(this)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        shifted = false
        letterButtons.clear()
        val keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The navigation gesture inset can overlay the last IME row on some phones.
            // Reserve extra touch space instead of letting the nav bar cover keys.
            setPadding(dp(3), dp(4), dp(3), dp(30))
            setBackgroundColor(Color.rgb(218, 223, 231))
        }

        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestionButton = Button(this).apply {
            text = "Подсказки"
            isAllCaps = false
            isEnabled = false
            contentDescription = "Применить подсказку"
            textSize = 14f
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { shownSuggestion?.let(::applySuggestion) }
        }
        alternativeButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Другой вариант"
            visibility = View.GONE
            setOnClickListener { shownAlternative?.let(::applySuggestion) }
        }
        thirdButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Третий вариант"
            visibility = View.GONE
            setOnClickListener { shownThird?.let(::applySuggestion) }
        }
        keepButton = Button(this).apply {
            text = "Оставить"
            textSize = 12f
            maxLines = 2
            setPadding(dp(2), 0, dp(2), 0)
            isAllCaps = false
            isEnabled = false
            contentDescription = "Оставить как набрано"
            setOnClickListener {
                val connection = currentInputConnection ?: return@setOnClickListener
                if (readContext(connection) == null) return@setOnClickListener
                undo.clear()
                keepAfterUndo = false
                connection.commitText(" ", 1)
                refreshSuggestion()
            }
        }
        suggestions.addView(suggestionButton, LinearLayout.LayoutParams(0, dp(48), 2f))
        suggestions.addView(alternativeButton, LinearLayout.LayoutParams(0, dp(48), 2f))
        suggestions.addView(thirdButton, LinearLayout.LayoutParams(0, dp(48), 2f))
        suggestions.addView(keepButton, LinearLayout.LayoutParams(0, dp(48), 1.6f))
        keyboard.addView(suggestions)
        val tools = LinearLayout(this)
        reviewButton = Button(this).apply {
            text = "Проверить фразу"
            isAllCaps = false
            contentDescription = "Проверить фразу"
            val info = currentInputEditorInfo
            isEnabled = info != null && TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)
            setOnClickListener { requestReview() }
        }
        tools.addView(reviewButton, LinearLayout.LayoutParams(0, dp(48), 4f))
        numberButton = Button(this).apply {
            text = "123"; textSize = 12f; setPadding(0, 0, 0, 0)
            contentDescription = "Цифры и символы"
            setOnClickListener {
                numeric = !numeric; text = if (numeric) "АБВ" else "123"
                cancelPreview(); renderKeys(); refreshSuggestion()
            }
        }
        tools.addView(numberButton, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        languageButton = Button(this).apply {
            text = "EN"; textSize = 12f; setPadding(0, 0, 0, 0)
            contentDescription = "Переключить язык"
            setOnClickListener {
                english = !english; numeric = false; text = if (english) "RU" else "EN"
                cancelPreview(); renderKeys(); refreshSuggestion()
            }
        }
        tools.addView(languageButton, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        tools.addView(Button(this).apply {
            text = "⚙"
            contentDescription = "Настройки клавиатуры"
            setOnClickListener { startActivity(Intent(this@RussianKeyboardImeService,
                KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        keyboard.addView(tools)
        lettersPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val letters = requireNotNull(lettersPanel)
        renderKeys()
        keyboard.addView(letters)
        previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val panel = requireNotNull(previewPanel)
        panel.addView(TextView(this).apply { text = "Предлагаемый текст"; textSize = 13f })
        previewText = TextView(this).apply { textSize = 18f; contentDescription = "Предпросмотр исправлений" }
        panel.addView(ScrollView(this).apply { addView(previewText) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "Применить"; isAllCaps = false; contentDescription = "Применить исправления фразы"
            setOnClickListener { applyReview() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "Отмена"; isAllCaps = false; contentDescription = "Отменить просмотр"
            setOnClickListener { cancelPreview(); refreshSuggestion() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(actions)
        keyboard.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(216)))
        return keyboard
    }

    private fun renderKeys() {
        val panel = lettersPanel ?: return
        numberButton?.text = if (numeric) "АБВ" else "123"
        languageButton?.text = if (english) "RU" else "EN"
        panel.removeAllViews(); letterButtons.clear(); shifted = false
        val rows = when {
            numeric -> listOf("1234567890".map { it.toString() },
                listOf("@", "#", "₽", "_", "&", "-", "+", "(", ")", "/"),
                listOf("*", "\"", "'", ";", ":", "!", "?", "%", "=", "⌫"),
                listOf(",", "Пробел", ".", "↵"))
            english -> listOf("qwertyuiop".map { it.toString() }, "asdfghjkl".map { it.toString() },
                listOf("⇧") + "zxcvbnm".map { it.toString() } + "⌫", listOf("@", ",", "Пробел", ".", "↵"))
            else -> listOf("йцукенгшщзхъ".map { it.toString() }, "фывапролджэ".map { it.toString() },
                listOf("⇧") + "ячсмитьбю".map { it.toString() } + "⌫", listOf("ё", ",", "Пробел", ".", "↵"))
        }
        for (row in rows) addRow(panel, row)
    }

    private fun addRow(keyboard: LinearLayout, labels: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (label in labels) {
            val info = currentInputEditorInfo
            val enterAction = if (label == "↵" && info != null)
                EditorActionPolicy.action(info.inputType, info.imeOptions) else null
            val key = Button(this).apply {
                text = enterAction?.label ?: label
                isAllCaps = false
                textSize = if (label == "Пробел") 13f else 17f
                minimumWidth = 0
                minWidth = 0
                minimumHeight = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                contentDescription = when (label) {
                    "⇧" -> "Shift"
                    "⌫" -> "Удалить"
                    "↵" -> enterAction?.description ?: "Новая строка"
                    "Пробел" -> "Пробел"
                    else -> label
                }
                setOnClickListener { handleKey(label) }
            }
            if (label.length == 1 && label[0].isLetter()) {
                letterButtons.add(key to label)
            }
            val weight = if (label == "Пробел") 5f else 1f
            row.addView(key, LinearLayout.LayoutParams(0, dp(52), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        keyboard.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun handleKey(key: String) {
        cancelAnalysis()
        cancelPreview()
        val connection = currentInputConnection ?: return
        when (key) {
            "⇧" -> {
                shifted = !shifted
                for ((button, letter) in letterButtons) {
                    button.text = if (shifted) letter.uppercase(Locale.forLanguageTag("ru")) else letter
                }
            }
            "⌫" -> if (!undoLastCorrection(connection)) {
                keepAfterUndo = false
                connection.deleteSurroundingTextInCodePoints(1, 0)
            }
            "Пробел" -> handleSpace()
            "↵" -> {
                undo.clear()
                keepAfterUndo = false
                val info = currentInputEditorInfo
                val action = info?.let { EditorActionPolicy.action(it.inputType, it.imeOptions) }
                if (action != null) {
                    connection.performEditorAction(action.code)
                } else connection.commitText("\n", 1)
            }
            else -> {
                undo.clear()
                keepAfterUndo = false
                val value = if (shifted) key.uppercase(Locale.forLanguageTag("ru")) else key
                connection.commitText(value, 1)
                if (shifted) {
                    shifted = false
                    for ((button, letter) in letterButtons) button.text = letter
                }
            }
        }
        refreshSuggestion()
    }

    private fun readContext(connection: InputConnection): String? {
        // Do not read password or other sensitive input at all, even locally.
        val info = currentInputEditorInfo
        if (info == null || !TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)) {
            return null
        }
        if (connection.getSelectedText(0)?.isNotEmpty() == true) {
            // A selection must not be silently rewritten as a whole suffix.
            return null
        }
        // The prefix alone cannot establish a word boundary if the cursor
        // sits inside a word. Never rewrite a partial word before the caret.
        val after = connection.getTextAfterCursor(1, 0)?.toString()
        if (after == null || (after.isNotEmpty() && (after[0].isLetterOrDigit() ||
                    after[0] == '_' || after[0] == '-'))) {
            return null
        }
        return connection.getTextBeforeCursor(240, 0)?.toString()
    }

    private fun snapshot(): Snapshot? {
        val connection = currentInputConnection ?: return null
        val text = readContext(connection) ?: return null
        return Snapshot(text, session, selectionStart, selectionEnd)
    }

    private fun showChoices(choices: List<RussianCorrectionEngine.Suggestion>, at: Snapshot? = null) {
        shownAt = at
        shownSuggestion = choices.firstOrNull()
        shownAlternative = choices.getOrNull(1)
        shownThird = choices.getOrNull(2)
        suggestionButton?.text = shownSuggestion?.edit?.replacementWithSpace?.trimEnd() ?: "Подсказки"
        suggestionButton?.isEnabled = shownSuggestion != null
        alternativeButton?.text = shownAlternative?.edit?.replacementWithSpace?.trimEnd()
        alternativeButton?.visibility = if (shownAlternative != null) View.VISIBLE else View.GONE
        thirdButton?.text = shownThird?.edit?.replacementWithSpace?.trimEnd()
        thirdButton?.visibility = if (shownThird != null) View.VISIBLE else View.GONE
        for ((button, choice) in listOf(suggestionButton to shownSuggestion,
                alternativeButton to shownAlternative, thirdButton to shownThird)) {
            val text = choice?.edit?.replacementWithSpace?.trimEnd()
            button?.tooltipText = text
            button?.contentDescription = if (text == null) "Подсказки" else
                if (choice?.edit?.expectedSuffix?.isEmpty() == true) "Вставить $text" else "Заменить на $text"
        }
        keepButton?.isEnabled = shownSuggestion?.edit?.expectedSuffix?.isNotEmpty() == true
    }

    private fun cancelAnalysis() {
        refreshTask?.let { handler.removeCallbacks(it) }
        refreshTask = null
        requests.invalidate()
        showChoices(emptyList())
    }

    private fun refreshSuggestion() {
        cancelAnalysis()
        reviewButton?.text = "Проверить фразу"
        if (keepAfterUndo || preview != null || english || numeric || !preferences.getBoolean("suggestions", true)) return
        refreshTask = Runnable {
            val at = snapshot() ?: return@Runnable
            requests.submit({
                val advanced = LexiconLoader.assistant?.suggestions(at.text).orEmpty()
                (advanced.take(1) + engine.suggestions(at.text) + advanced.drop(1))
                    .distinctBy { it.edit.replacementWithSpace }.take(3)
            }, { choices -> if (snapshot() == at) showChoices(choices, at) })
        }.also { handler.postDelayed(it, 60) }
    }

    private fun cancelPreview() {
        preview = null; previewAt = null
        previewText?.text = ""
        previewPanel?.visibility = View.GONE
        lettersPanel?.visibility = View.VISIBLE
    }

    private fun requestReview() {
        cancelAnalysis()
        val at = snapshot() ?: return
        val assistant = LexiconLoader.assistant
        if (assistant == null) { reviewButton?.text = "Словарь загружается…"; return }
        reviewButton?.text = "Проверяю…"
        // The left edge of the bounded editor snapshot might cut a word.
        val span = if (at.text.length == 240) at.text.substringAfter(' ', "") else at.text
        requests.submit({ assistant.review(span) }, { result ->
            if (snapshot() != at) return@submit
            if (result == null) { reviewButton?.text = "Изменений нет"; return@submit }
            preview = result; previewAt = at
            previewText?.text = result.proposed
            lettersPanel?.visibility = View.GONE
            previewPanel?.visibility = View.VISIBLE
            reviewButton?.text = "Предпросмотр · ${result.changes}"
        }, { reviewButton?.text = "Не удалось проверить" })
    }

    private fun applyReview() {
        val proposal = preview ?: return
        val at = previewAt ?: return
        val now = snapshot() ?: return
        if (now != at || !now.text.endsWith(proposal.original)) { cancelPreview(); return }
        val connection = currentInputConnection ?: return
        val edit = RussianCorrectionEngine.Edit(proposal.original, proposal.proposed, RussianCorrectionEngine.Reason.PHRASE)
        cancelAnalysis(); cancelPreview(); undo.clear(); keepAfterUndo = false
        applyEdit(connection, edit)
        refreshSuggestion()
    }

    private fun applySuggestion(shown: RussianCorrectionEngine.Suggestion) {
        val connection = currentInputConnection ?: return
        val now = snapshot() ?: return
        // Re-read the actual editor: the caret or text may have changed since
        // the suggestion was shown. Never apply stale suffix coordinates.
        if (now != shownAt || shown !in listOf(shownSuggestion, shownAlternative, shownThird) ||
            !now.text.endsWith(shown.edit.expectedSuffix)) return
        cancelAnalysis()
        undo.clear()
        keepAfterUndo = false
        applyEdit(connection, shown.edit)
        refreshSuggestion()
    }

    private fun handleSpace() {
        undo.clear()
        val connection = currentInputConnection ?: return
        if (english || numeric || !preferences.getBoolean("autocorrect", true)) {
            keepAfterUndo = false
            connection.commitText(" ", 1)
            return
        }
        if (keepAfterUndo) {
            keepAfterUndo = false
            connection.commitText(" ", 1)
            return
        }
        val before = readContext(connection)
        if (before == null) {
            connection.commitText(" ", 1)
            return
        }
        val edit = engine.onSpace(before)
        if (edit == null || !before.endsWith(edit.expectedSuffix)) {
            connection.commitText(" ", 1)
            return
        }

        applyEdit(connection, edit)
    }

    private fun applyEdit(connection: InputConnection, edit: RussianCorrectionEngine.Edit) {
        connection.beginBatchEdit()
        try {
            val deleted = edit.expectedSuffix.isEmpty() || connection.deleteSurroundingText(edit.expectedSuffix.length, 0)
            if (deleted && connection.commitText(edit.replacementWithSpace, 1)) {
                undo.record(edit)
            }
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun undoLastCorrection(connection: InputConnection): Boolean {
        val count = undo.suffixLength()
        if (count == 0) return false
        val info = currentInputEditorInfo
        if (info == null || !TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)) {
            undo.clear()
            return false
        }
        if (connection.getSelectedText(0)?.isNotEmpty() == true) {
            undo.clear()
            return false
        }
        // A matching prefix is insufficient when the caret has moved inside
        // a word or identifier: undo must not rewrite a partial token.
        val after = connection.getTextAfterCursor(1, 0)?.toString()
        if (after == null || (after.isNotEmpty() && (after[0].isLetterOrDigit() ||
                    after[0] == '_' || after[0] == '-'))) {
            undo.clear()
            return false
        }
        // Revalidate the suffix; never blindly undo an old correction.
        val current = connection.getTextBeforeCursor(count, 0)?.toString()
        val previous = undo.takeIfMatching(current) ?: return false
        connection.beginBatchEdit()
        try {
            if (!connection.deleteSurroundingText(count, 0)) return false
            if (!connection.commitText(previous, 1)) return false
            keepAfterUndo = true
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session++
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        reviewButton?.isEnabled = attribute != null && TextFieldPolicy.canAutocorrect(attribute.inputType, attribute.imeOptions)
        val inputClass = (attribute?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS
        numeric = inputClass == android.text.InputType.TYPE_CLASS_NUMBER || inputClass == android.text.InputType.TYPE_CLASS_PHONE
        renderKeys()
        cancelAnalysis(); cancelPreview()
        undo.clear()
        keepAfterUndo = false
        shifted = false
        shownSuggestion = null
        shownAlternative = null
        alternativeButton?.visibility = View.GONE
        suggestionButton?.text = "Подсказки"
        suggestionButton?.isEnabled = false
        keepButton?.isEnabled = false
    }

    override fun onFinishInput() {
        session++
        cancelAnalysis(); cancelPreview()
        undo.clear()
        keepAfterUndo = false
        shownSuggestion = null
        shownAlternative = null
        alternativeButton?.visibility = View.GONE
        suggestionButton?.text = "Подсказки"
        suggestionButton?.isEnabled = false
        keepButton?.isEnabled = false
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                                   newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val changed = selectionStart != newSelStart || selectionEnd != newSelEnd
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if (changed) { cancelPreview(); refreshSuggestion() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelAnalysis(); cancelPreview()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelAnalysis(); cancelPreview()
        requests.close()
        super.onDestroy()
    }
}
