package dev.offline.smartkeyboard.ime

import android.text.InputType

/**
 * Policy for the offline, stateless spelling engine.
 *
 * NO_PERSONALIZED_LEARNING only restricts adaptation/storage; we never learn
 * or persist text. NO_SUGGESTIONS is frequently used by rich-text chat editors,
 * but the user has explicitly chosen this keyboard for correction.
 *
 * Prevent corrections in passwords, contact addresses, URLs, and non-prose.
 */
internal object AutocorrectPolicy {
    fun enabled(inputType: Int): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        if (kind != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation in setOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        )
    }
}
