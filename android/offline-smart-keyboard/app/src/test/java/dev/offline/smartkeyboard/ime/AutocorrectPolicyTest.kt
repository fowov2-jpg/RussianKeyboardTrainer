package dev.offline.smartkeyboard.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocorrectPolicyTest {
    @Test fun chatFieldsAllowOfflineCorrectionsEvenWhenSuggestionsDisabled() {
        val normal = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val chat = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(AutocorrectPolicy.enabled(normal))
        assertTrue(AutocorrectPolicy.enabled(chat))
    }

    @Test fun passwordsContactFieldsAndNumbersAreNeverAutocorrected() {
        val sensitive = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        sensitive.forEach { assertFalse(AutocorrectPolicy.enabled(it)) }
    }
}
