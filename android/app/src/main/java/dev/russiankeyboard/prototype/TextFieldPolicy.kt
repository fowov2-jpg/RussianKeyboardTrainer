package dev.russiankeyboard.prototype

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Pure, locally testable privacy gate. Do not call getTextBeforeCursor unless
 * this returns true. It is intentionally conservative for structured fields.
 */
object TextFieldPolicy {
    fun canAutocorrect(inputType: Int, imeOptions: Int): Boolean {
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
                // Names, postal addresses, phonetic input and search filters
                // should never be rewritten by the tiny typo dictionary.
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PHONETIC,
                InputType.TYPE_TEXT_VARIATION_FILTER,
            )) return false
        if ((inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        return true
    }
}
