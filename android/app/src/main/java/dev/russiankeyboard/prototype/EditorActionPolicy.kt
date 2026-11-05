package dev.russiankeyboard.prototype

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** The visible Enter key and its effect always use the same editor contract. */
object EditorActionPolicy {
    data class Action(val code: Int, val label: String, val description: String)
    fun action(inputType: Int, imeOptions: Int): Action? {
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
            imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val code = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> Action(code, "➤", "Отправить")
            EditorInfo.IME_ACTION_DONE -> Action(code, "✓", "Готово")
            EditorInfo.IME_ACTION_SEARCH -> Action(code, "⌕", "Поиск")
            EditorInfo.IME_ACTION_GO -> Action(code, "↗", "Перейти")
            EditorInfo.IME_ACTION_NEXT -> Action(code, "Далее", "Следующее поле")
            EditorInfo.IME_ACTION_PREVIOUS -> Action(code, "Назад", "Предыдущее поле")
            else -> null
        }
    }
}
