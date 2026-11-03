package dev.russiankeyboard.prototype

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

/**
 * Minimal offline Russian IME. Key events never wait for ML/network.
 * Android IME exposes only the active editor; it cannot access chat history.
 */
class RussianKeyboardImeService : InputMethodService() {
    private val engine = RussianCorrectionEngine()
    private val undo = CorrectionUndoState()
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
            "⌫" -> if (!undoLastCorrection(connection)) connection.deleteSurroundingText(1, 0)
            "Пробел" -> handleSpace()
            "↵" -> {
                undo.clear()
                connection.commitText("\n", 1)
            }
            else -> {
                undo.clear()
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
        undo.clear()
        val connection = currentInputConnection ?: return
        // Do not read password or other sensitive input at all, even locally.
        val info = currentInputEditorInfo
        if (info == null || !TextFieldPolicy.canAutocorrect(info.inputType, info.imeOptions)) {
            connection.commitText(" ", 1)
            return
        }
        if (connection.getSelectedText(0)?.isNotEmpty() == true) {
            // A selection must not be silently rewritten as a whole suffix.
            connection.commitText(" ", 1)
            return
        }
        val before = connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val edit = engine.onSpace(before)
        if (edit == null || !before.endsWith(edit.expectedSuffix)) {
            connection.commitText(" ", 1)
            return
        }

        connection.beginBatchEdit()
        try {
            val deleted = connection.deleteSurroundingText(edit.expectedSuffix.length, 0)
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
        // Revalidate the suffix; never blindly undo an old correction.
        val current = connection.getTextBeforeCursor(count, 0)?.toString()
        val previous = undo.takeIfMatching(current) ?: return false
        connection.beginBatchEdit()
        try {
            if (!connection.deleteSurroundingText(count, 0)) return false
            connection.commitText(previous, 1)
        } finally {
            connection.endBatchEdit()
        }
        return true
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        undo.clear()
        shifted = false
    }

    override fun onFinishInput() {
        undo.clear()
        super.onFinishInput()
    }
}
