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
 * Optional accessibility context is separate from the active editor and opt-in.
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
    private val suggestionRequests = LatestRequestQueue { handler.post(it) }
    private val liveSpelling = LiveSpellingController(engine,
        { LexiconLoader.morphology != null && LexiconLoader.spelling != null },
        { LexiconLoader.morphology?.hasPrefix(it) == true || LexiconLoader.spelling?.hasPrefix(it) == true },
        { LexiconLoader.morphology?.hasPrefix(it) == true || LexiconLoader.spelling?.hasContinuation(it) == true })
    private var refreshTask: Runnable? = null
    private var session = 0L
    private var selectionStart = -1
    private var selectionEnd = -1
    private data class Snapshot(val text: String, val session: Long, val start: Int, val end: Int, val contextRevision: Long?)
    private var shownAt: Snapshot? = null
    private var previewAt: Snapshot? = null
    private var preview: ContextualAssistant.Review? = null
    private var lettersPanel: LinearLayout? = null
    private var previewPanel: LinearLayout? = null
    private var previewText: TextView? = null
    private var reviewButton: Button? = null
    private var numberButton: Button? = null
    private var languageButton: Button? = null
    private var contextButton: Button? = null
    private var contextPanel: LinearLayout? = null
    private var contextText: TextView? = null
    private var automaticTask: Runnable? = null
    private var punctuator: OnDevicePunctuator? = null // analysis worker owns this session
    @Volatile private var punctuationState = "загрузка при наборе"
    private var suppressedAutomatic: String? = null
    private var lastSubmitted: Snapshot? = null
    private var warmedPunctuation = false
    private data class Finalized(val text: String, val replacement: String, val session: Long, val revision: Long?)
    private var finalized: Finalized? = null
    private var manualContextSession: Long? = null
    private data class Provisional(val suffix: String, val terminal: String, val end: Int)
    private var provisional: Provisional? = null
    private val preferences by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        LexiconLoader.warmUp(this)
        ConversationContext.changed = { updateContextLabel(); scheduleAutomatic() }
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
            text = "Проверить"
            textSize = 12f
            setPadding(dp(4), 0, dp(4), 0)
            isAllCaps = false
            contentDescription = "Проверить фразу"
            val info = currentInputEditorInfo
            isEnabled = info != null && TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)
            setOnClickListener { requestReview() }
        }
        tools.addView(reviewButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        contextButton = Button(this).apply {
            text = "Контекст"; textSize = 12f; isAllCaps = false; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(3), 0, dp(3), 0)
            contentDescription = "Контекст разговора"
            setOnClickListener { showConversation() }
        }
        tools.addView(contextButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        numberButton = Button(this).apply {
            text = "123"; textSize = 12f; setPadding(0, 0, 0, 0)
            contentDescription = "Цифры и символы"
            setOnClickListener {
                numeric = !numeric; text = if (numeric) "АБВ" else "123"
                cancelPreview(); renderKeys(); refreshSuggestion()
            }
        }
        tools.addView(numberButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        languageButton = Button(this).apply {
            text = "EN"; textSize = 12f; setPadding(0, 0, 0, 0)
            contentDescription = "Переключить язык"
            setOnClickListener {
                english = !english; numeric = false; text = if (english) "RU" else "EN"
                cancelPreview(); renderKeys(); refreshSuggestion()
            }
        }
        tools.addView(languageButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        tools.addView(Button(this).apply {
            text = "⚙"
            contentDescription = "Настройки клавиатуры"
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        contextPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        contextText = TextView(this).apply { textSize = 14f; contentDescription = "Доступный контекст" }
        contextPanel!!.addView(ScrollView(this).apply { addView(contextText) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val contextActions = LinearLayout(this)
        contextActions.addView(Button(this).apply {
            text = "Настроить"; isAllCaps = false; setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        contextActions.addView(Button(this).apply {
            text = "Вручную"; isAllCaps = false
            setOnClickListener { manualContextSession = session; cancelPreview(); updateContextLabel(); refreshSuggestion() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        contextActions.addView(Button(this).apply {
            text = "Назад"; isAllCaps = false; setOnClickListener { cancelPreview(); refreshSuggestion() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        contextPanel!!.addView(contextActions)
        keyboard.addView(contextPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(216)))
        updateContextLabel()
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
        val enterResult = if (key == "↵") finalized else null
        cancelAnalysis(cancelModel = key == "⌫" || key == "↵")
        if (key == "↵") finalized = enterResult
        cancelPreview()
        val connection = currentInputConnection ?: return
        var appendedKey = if (key == "Пробел") " " else key
        if (key != "⌫" && key != "⇧" && key != "↵") reopenProvisional(connection)
        when (key) {
            "⇧" -> {
                shifted = !shifted
                for ((button, letter) in letterButtons) {
                    button.text = if (shifted) letter.uppercase(Locale.forLanguageTag("ru")) else letter
                }
            }
            "⌫" -> if (!undoLastCorrection(connection)) {
                liveSpelling.clear()
                keepAfterUndo = false
                connection.deleteSurroundingTextInCodePoints(1, 0)
            }
            "Пробел" -> handleSpace()
            "↵" -> {
                undo.clear()
                keepAfterUndo = false
                val info = currentInputEditorInfo
                val action = info?.let { EditorActionPolicy.action(it.inputType, it.imeOptions) }
                finishWithAutomatic {
                    if (action != null) connection.performEditorAction(action.code)
                    else connection.commitText("\n", 1)
                }
                return
            }
            else -> {
                undo.clear()
                keepAfterUndo = false
                val value = if (shifted) key.uppercase(Locale.forLanguageTag("ru")) else key
                appendedKey = value
                connection.commitText(value, 1)
                if (shifted) {
                    shifted = false
                    for ((button, letter) in letterButtons) button.text = letter
                }
            }
        }
        if (key != "⇧" && key != "⌫") liveCorrection(connection, appendedKey)
        refreshSuggestion()
    }

    private fun liveCorrection(connection: InputConnection, key: String) {
        val info = currentInputEditorInfo
        if (info == null || !TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)) { liveSpelling.clear(); return }
        if ((english || numeric) && !liveSpelling.active || !preferences.getBoolean("autocorrect", true) ||
            !preferences.getBoolean("automatic_writing", true) || keepAfterUndo ||
            connection.getTextAfterCursor(1, 0)?.isEmpty() != true) { liveSpelling.clear(); return }
        val before = readContext(connection) ?: run { liveSpelling.clear(); return }
        if (before.trim() == suppressedAutomatic) { liveSpelling.clear(); return }
        val plan = liveSpelling.appended(before, key) ?: return
        plan.edit?.let { applyEdit(connection, it) }
        if (plan.checkpoint != null) undo.record(plan.checkpoint) else undo.clear()
    }

    private fun readContext(connection: InputConnection, limit: Int = 240): String? {
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
        return connection.getTextBeforeCursor(limit, 0)?.toString()
    }

    private fun snapshot(limit: Int = 240): Snapshot? {
        val connection = currentInputConnection ?: return null
        val text = readContext(connection, limit) ?: return null
        return Snapshot(text, session, selectionStart, selectionEnd, ConversationContext.snapshot()?.revision)
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

    private fun cancelAnalysis(cancelModel: Boolean = true) {
        refreshTask?.let { handler.removeCallbacks(it) }
        refreshTask = null
        if (cancelModel) {
            automaticTask?.let { handler.removeCallbacks(it) }; automaticTask = null
            requests.invalidate(); lastSubmitted = null; finalized = null
        }
        suggestionRequests.invalidate()
        showChoices(emptyList())
    }

    private fun refreshSuggestion() {
        cancelAnalysis(cancelModel = false)
        reviewButton?.text = "Проверить"
        bindConversation()
        scheduleAutomatic()
        if (keepAfterUndo || preview != null || english || numeric || !preferences.getBoolean("suggestions", true)) return
        refreshTask = Runnable {
            val at = snapshot() ?: return@Runnable
            suggestionRequests.submit({
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
        contextPanel?.visibility = View.GONE
        contextText?.text = ""
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
        val profile = writingProfile()
        val conversation = conversationText(profile)
        requests.submit({
            val base = assistant.review(span, conversation, profile)
            val text = base?.proposed ?: span
            val punctuated = punctuation().predict(text, conversation, PunctuationPlanner.Mode.FINALIZE)
            val proposed = punctuated?.text ?: text
            if (proposed == span) null else ContextualAssistant.Review(span, proposed, (base?.changes ?: 0) + if (punctuated != null) 1 else 0)
        }, { result ->
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

    private fun applyEdit(connection: InputConnection, edit: RussianCorrectionEngine.Edit, combineUndo: Boolean = false) {
        connection.beginBatchEdit()
        try {
            val deleted = edit.expectedSuffix.isEmpty() || connection.deleteSurroundingText(edit.expectedSuffix.length, 0)
            if (deleted && connection.commitText(edit.replacementWithSpace, 1)) {
                undo.record(edit, combineUndo)
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
            liveSpelling.clear()
            provisional = null
            suppressedAutomatic = readContext(connection, 640)?.trim()
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session++
        liveSpelling.clear()
        suppressedAutomatic = null; provisional = null; manualContextSession = null
        ConversationContext.clear()
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
        liveSpelling.clear()
        ConversationContext.clear(); provisional = null; suppressedAutomatic = null
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
        if (provisional != null && newSelEnd != provisional!!.end) provisional = null
        if (changed) { cancelPreview(); refreshSuggestion() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        ConversationContext.clear()
        cancelAnalysis(); cancelPreview()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelAnalysis(); cancelPreview()
        ConversationContext.changed = null; ConversationContext.clear()
        requests.close { punctuator?.close(); punctuator = null }
        suggestionRequests.close()
        super.onDestroy()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // InputMethodService updates its shown-state after this callback. Bind
        // on the next UI turn, including an empty editor before the first key.
        handler.post { if (isInputViewShown) refreshSuggestion() }
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val started = session
        handler.post {
            if (session == started && isInputViewShown) {
                if (!warmedPunctuation && TextFieldPolicy.canAutocorrect(info?.inputType ?: 0, info?.imeOptions ?: 0)) {
                    warmedPunctuation = true
                    requests.submit({ punctuation(); Unit }, {}, coalesce = true)
                }
                refreshSuggestion()
            }
        }
    }

    private fun punctuation(): OnDevicePunctuator {
        punctuator?.let { return it }
        return OnDevicePunctuator.open(applicationContext).also {
            punctuator = it; punctuationState = "готово"
        }
    }
    private fun automaticSnapshot(): Snapshot? {
        val connection = currentInputConnection ?: return null
        val info = currentInputEditorInfo ?: return null
        if (!TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)) return null
        // A suffix before text later in the field is not a complete utterance.
        if (connection.getTextAfterCursor(1, 0)?.isEmpty() != true) return null
        return snapshot(640)
    }
    private fun normalizedAutomatic(text: String) = text.trim()
    private fun scheduleAutomatic() {
        if (!isInputViewShown || preview != null || contextPanel?.visibility == View.VISIBLE ||
            !preferences.getBoolean("automatic_writing", true)) return
        if (automaticTask != null) return
        automaticTask = Runnable {
            automaticTask = null
            val at = automaticSnapshot() ?: return@Runnable
            if (at.text.isBlank() || at.text.none { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' } ||
                normalizedAutomatic(at.text) == suppressedAutomatic) return@Runnable
            if (at == lastSubmitted) return@Runnable
            lastSubmitted = at
            val profile = writingProfile(); val context = conversationText(profile)
            val revision = ConversationContext.snapshot()?.revision
            requests.submit({ analyzeAutomatically(at.text, context, profile, PunctuationPlanner.Mode.STREAMING) }, { result ->
                if (ConversationContext.snapshot()?.revision != revision || result == null) return@submit
                applyAutomatic(at, result)
            }, { punctuationState = "ошибка загрузки или анализа"; updateContextLabel() }, coalesce = true)
        }.also { handler.postDelayed(it, 160) }
    }
    private fun analyzeAutomatically(text: String, context: String, profile: WritingProfile,
                                     mode: PunctuationPlanner.Mode): AutomaticWritingEngine.Result? {
        val bounded = if (text.length == 640) text.substringAfter(' ', "") else text
        val span = StreamingTextBoundary.split(bounded,
            { LexiconLoader.morphology?.contains(it) == true || LexiconLoader.spelling?.contains(it) == true },
            { LexiconLoader.morphology?.hasPrefix(it) == true || LexiconLoader.spelling?.hasContinuation(it) == true })
        val result = AutomaticWritingEngine(engine, LexiconLoader.assistant, { value, history, pass, first ->
            punctuation().predict(value, history, pass, first)
        }, preferences.getBoolean("autocorrect", true)).analyze(span.input, context, profile, mode, text.length < 640) ?: return null
        if (span.tail.isEmpty()) return result
        val joined = StreamingEditRebaser.rebase(result.original, result.replacement, result.provisionalTerminal,
            span.input.length, bounded, bounded.length) ?: return null
        return result.copy(original = joined.original, replacement = joined.replacement,
            provisionalTerminal = null, finalizedReplacement = null)
    }
    private fun applyAutomatic(at: Snapshot, result: AutomaticWritingEngine.Result) {
        if (preview != null || contextPanel?.visibility == View.VISIBLE) return
        val now = automaticSnapshot() ?: return
        if (now.session != at.session || now.contextRevision != at.contextRevision ||
            now.start != now.end || now.text.trim() == suppressedAutomatic) return
        val edit = StreamingEditRebaser.rebase(result.original, result.replacement, result.provisionalTerminal,
            at.end, now.text, now.end) ?: return
        val connection = currentInputConnection ?: return
        if (edit.original != edit.replacement) {
            applyEdit(connection, RussianCorrectionEngine.Edit(edit.original, edit.replacement, RussianCorrectionEngine.Reason.PHRASE), combineUndo = true)
            liveSpelling.reconcile(edit.replacement)
        }
        if (edit.tail.isBlank() && result.finalizedReplacement != null) {
            val prefix = now.text.dropLast(edit.original.length)
            val shown = prefix + edit.replacement
            finalized = Finalized(shown.takeLast(640),
                (prefix + result.finalizedReplacement + edit.tail).drop((shown.length - 640).coerceAtLeast(0)), now.session, now.contextRevision)
        }
        if (edit.provisionalTerminal != null) {
            val end = now.end + edit.replacement.length - edit.original.length
            provisional = Provisional(edit.replacement.takeLast(120), edit.provisionalTerminal, end)
        }
    }
    private fun reopenProvisional(connection: InputConnection) {
        val record = provisional ?: return
        provisional = null
        val before = readContext(connection, 640) ?: return
        if (!before.endsWith(record.suffix) || selectionEnd != record.end) return
        val spaces = record.suffix.takeLastWhile { it.isWhitespace() }
        val suffix = record.terminal + spaces
        if (!before.endsWith(suffix)) return
        connection.beginBatchEdit()
        try {
            if (connection.deleteSurroundingText(suffix.length, 0) && spaces.isNotEmpty()) connection.commitText(spaces, 1)
        } finally { connection.endBatchEdit() }
    }
    private fun finishWithAutomatic(finish: () -> Unit) {
        val at = automaticSnapshot()
        if (!preferences.getBoolean("automatic_writing", true) || at == null || at.text.isBlank() ||
            normalizedAutomatic(at.text) == suppressedAutomatic) { finish(); return }
        val cached = finalized
        if (cached != null && cached.session == at.session && cached.revision == at.contextRevision &&
            cached.text.trimEnd() == at.text.trimEnd()) {
            val replacement = cached.replacement.trimEnd() + at.text.takeLastWhile { it.isWhitespace() }
            if (replacement != at.text) currentInputConnection?.let {
                applyEdit(it, RussianCorrectionEngine.Edit(at.text, replacement, RussianCorrectionEngine.Reason.PHRASE))
            }
        }
        // Never hold the user's Send/Next/newline action behind native inference.
        finish()
    }
    private fun bindConversation() {
        val info = currentInputEditorInfo
        val permitted = isInputViewShown && info != null &&
            TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions) &&
            info.imeOptions and EditorInfo.IME_MASK_ACTION != EditorInfo.IME_ACTION_SEARCH &&
            preferences.getBoolean("screen_context", false)
        ConversationContext.begin(info?.packageName.orEmpty(), session, permitted)
        if (permitted) ConversationAccessibilityService.refresh()
        updateContextLabel()
    }
    private fun profileIdentity(): String = ConversationContext.snapshot()?.takeIf { it.title.isNotBlank() }?.key
        ?: currentInputEditorInfo?.packageName.orEmpty() + ":manual"
    private fun writingProfile(): WritingProfile {
        val profile = WritingProfiles.read(preferences, profileIdentity())
        return if (ConversationContext.snapshot()?.title?.isNotBlank() == true || manualContextSession == session) profile
            else profile.copy(recipientGender = null, note = "", recipientName = "")
    }
    private fun conversationText(profile: WritingProfile): String =
        ConversationContext.snapshot()?.modelContext(profile.note) ?: profile.note
    private fun updateContextLabel() {
        val context = ConversationContext.snapshot()
        val manual = writingProfile()
        contextButton?.text = when {
            context?.title?.isNotBlank() == true -> "Кому: ${context.title}"
            manualContextSession == session -> "Вручную"
            else -> "Контекст"
        }
        contextButton?.tooltipText = context?.title?.takeIf { it.isNotBlank() } ?: manual.recipientName
    }
    private fun showConversation() {
        cancelAnalysis(); cancelPreview()
        val context = ConversationContext.snapshot()
        val profile = WritingProfiles.read(preferences, profileIdentity())
        contextText?.text = buildString {
            append("Автоматический анализ: $punctuationState\n\n")
            if (context != null && context.messages.isNotEmpty()) {
                append("Видимый разговор: ${context.title.ifBlank { "имя недоступно" }}\n\n")
                append(context.messages.joinToString("\n\n"))
            } else append("История разговора сейчас недоступна. В настройках можно включить доступ к видимому чату или задать контекст вручную.")
            if (profile.note.isNotBlank()) append("\n\nЗадано вручную:\n${profile.note}\nНажмите «Вручную», чтобы использовать это для текущего ввода.")
        }
        lettersPanel?.visibility = View.GONE; contextPanel?.visibility = View.VISIBLE
    }
    private fun openSettings() {
        val title = ConversationContext.snapshot()?.title.orEmpty()
        startActivity(Intent(this, KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("profile_identity", profileIdentity()).putExtra("profile_title", title))
    }
}
