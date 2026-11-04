package dev.russiankeyboard.prototype

import android.content.SharedPreferences
import java.security.MessageDigest

object WritingProfiles {
    fun key(identity: String): String {
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())) {
                val value = byte.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }
    fun read(prefs: SharedPreferences, identity: String): WritingProfile {
        fun gender(key: String) = prefs.getString(key, null)?.takeIf { it in setOf("Masc", "Fem", "Neut") }
        val id = key(identity)
        return WritingProfile(gender("speaker_gender"), gender("recipient_gender_$id"),
            prefs.getString("conversation_note_$id", "").orEmpty().take(800),
            prefs.getString("recipient_name_$id", "").orEmpty().take(100))
    }
}
