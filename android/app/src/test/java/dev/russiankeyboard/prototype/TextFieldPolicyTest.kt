package dev.russiankeyboard.prototype

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class TextFieldPolicyTest {
    @Test fun normalRussianTextCanBeCorrected() {
        assertTrue(TextFieldPolicy.canAutocorrect(InputType.TYPE_CLASS_TEXT, 0))
        assertTrue(TextFieldPolicy.canAutocorrect(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0))
    }

    @Test fun sensitiveVariationsAreNotRead() {
        val privateVariations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
        for (variation in privateVariations) {
            assertFalse("variation=$variation", TextFieldPolicy.canAutocorrect(
                InputType.TYPE_CLASS_TEXT or variation, 0))
        }
    }

    @Test fun numbersPhonesAndNoSuggestionsAreExcluded() {
        assertFalse(TextFieldPolicy.canAutocorrect(InputType.TYPE_CLASS_PHONE, 0))
        assertFalse(TextFieldPolicy.canAutocorrect(InputType.TYPE_CLASS_NUMBER, 0))
        assertFalse(TextFieldPolicy.canAutocorrect(InputType.TYPE_NULL, 0))
        assertFalse(TextFieldPolicy.canAutocorrect(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0))
        assertFalse(TextFieldPolicy.canAutocorrect(
            InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }
}
