package dev.offline.smartkeyboard.core

import java.util.Locale

/**
 * Explicit, offline Russian verb-agreement hints for a small set of pronouns.
 * Not a full morphological analyser. Never uploads, logs or learns user text.
 *
 * Format in ru_verb_forms.txt: family pronoun wordform
 * The target is offered as a suggestion; exact dictionary words are NOT
 * silently replaced simply because a rule prefers a different verb form.
 */
class GrammarHints private constructor(
    private val preferred: Map<String, Map<String, String>>,
    private val familyForForm: Map<String, String>
) {
    private val ru = Locale.forLanguageTag("ru-RU")

    fun suggestion(previous: String?, typedWord: String): String? {
        if (previous.isNullOrBlank()) return null
        val family = familyForForm[typedWord.lowercase(ru)] ?: return null
        val recommended = preferred[family]?.get(previous.lowercase(ru)) ?: return null
        return recommended.takeUnless { it == typedWord.lowercase(ru) }
    }

    companion object {
        val EMPTY = GrammarHints(emptyMap(), emptyMap())

        fun parse(lines: Sequence<String>): GrammarHints {
            val preferred = LinkedHashMap<String, MutableMap<String, String>>()
            val familyForForm = HashMap<String, String>()
            val ru = Locale.forLanguageTag("ru-RU")
            val allowedPronouns = setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они")
            for (line in lines) {
                val trimmed = line.trim().lowercase(ru)
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size != 3 || parts[1] !in allowedPronouns) continue
                val (family, pronoun, form) = parts
                if (family.length > 32 || form.length > 32 ||
                    !family.all(Char::isLetter) || !form.all(Char::isLetter)) continue
                preferred.getOrPut(family) { LinkedHashMap() }[pronoun] = form
                familyForForm[form] = family
            }
            return GrammarHints(preferred, familyForForm)
        }
    }
}
