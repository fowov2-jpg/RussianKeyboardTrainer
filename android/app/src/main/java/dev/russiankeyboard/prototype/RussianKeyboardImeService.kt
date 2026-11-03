package dev.russiankeyboard.prototype

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

/**
 * Minimal offline Russian IME. Key events never wait for ML/network.
 * Android IME exposes only the active editor; it cannot access chat history.
 */
class RussianKeyboardImeService : InputMethodService() {
    private val engine = RussianCorrectionEngine()
    private var shifted = false
    private val letterButtons = mutableListOf<Pair<Button, String>>()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        shifted = false
        letterButtons.clear()
        val keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(6))
            setBackgroundColor(Color.rgb(218, 223, 231))
        }

        addRow(keyboard, listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х", "ъ"))
        addRow(keyboard, listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"))
        addRow(keyboard, listOf("⇧", "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", "⌫"))
        addRow(keyboard, listOf(",", "Пробел", ".", "↵"))
        return keyboard
    }

    private fun addRow(keyboard: LinearLayout, labels: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (label in labels) {
            val key = Button(this).apply {
                text = label
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
                    "↵" -> "Новая строка"
                    "Пробел" -> "Пробел"
                    else -> label
                }
                setOnClickListener { handleKey(label) }
            }
            if (label.length == 1 && label[0].isLetter()) {
                letterButtons.add(key to label)
            }
            val weight = if (label == "Пробел") 5f else 1f
            row.addView(key, LinearLayout.LayoutParams(0, dp(49), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
        }
        keyboard.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun handleKey(key: String) {
        val connection = currentInputConnection ?: return
        when (key) {
            "⇧" -> {
                shifted = !shifted
                for ((button, letter) in letterButtons) {
                    button.text = if (shifted) letter.uppercase(Locale.forLanguageTag("ru")) else letter
                }
            }
            "⌫" -> connection.deleteSurroundingText(1, 0)
            "Пробел" -> handleSpace()
            "↵" -> connection.commitText("\n", 1)
            else -> {
                val value = if (shifted) key.uppercase(Locale.forLanguageTag("ru")) else key
                connection.commitText(value, 1)
                if (shifted) {
                    shifted = false
                    for ((button, letter) in letterButtons) button.text = letter
                }
            }
        }
    }

    private fun handleSpace() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val edit = engine.onSpace(before, isSensitiveField())
        if (edit == null || !before.endsWith(edit.expectedSuffix)) {
            connection.commitText(" ", 1)
            return
        }

        connection.beginBatchEdit()
        try {
            connection.deleteSurroundingText(edit.expectedSuffix.length, 0)
            connection.commitText(edit.replacementWithSpace, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun isSensitiveField(): Boolean {
        val info = currentInputEditorInfo ?: return true
        val inputType = info.inputType
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true
        if ((inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return true
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        return false
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        shifted = false
    }
}
