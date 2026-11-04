package dev.russiankeyboard.prototype

import android.content.Context
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/** Shared, background-loaded mapping. The IME never parses a million rows. */
object LexiconLoader {
    @Volatile private var loaded: RussianLexicon? = null
    @Volatile private var contextAssistant: ContextualAssistant? = null
    @Volatile private var knownForms: MorphologyDictionary? = null
    @Volatile private var prefixIndex: SpellingIndex? = null
    @Volatile private var started = false
    private val worker = Executors.newSingleThreadExecutor()
    val current: RussianLexicon? get() = loaded.takeIf { knownForms != null }
    val morphology: MorphologyDictionary? get() = knownForms
    val assistant: ContextualAssistant? get() = contextAssistant
    val spelling: SpellingIndex? get() = prefixIndex

    @Synchronized fun warmUp(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.execute {
            try {
                app.assets.openFd("lexicon/russian.bin").use { asset ->
                    FileInputStream(asset.fileDescriptor).use { stream ->
                        val mapping = stream.channel.map(FileChannel.MapMode.READ_ONLY,
                            asset.startOffset, asset.declaredLength)
                        loaded = RussianLexicon(mapping)
                    }
                }
                fun mapped(name: String): java.nio.ByteBuffer = app.assets.openFd("context/$name").use { asset ->
                    asset.createInputStream().use { stream -> stream.channel.map(
                        FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) }
                }
                fun lines(name: String) = app.assets.open("context/$name").bufferedReader().use { it.readLines() }
                knownForms = MorphologyDictionary(mapped("morphology.bin"))
                prefixIndex = SpellingIndex(mapped("spelling.bin"))
                contextAssistant = ContextualAssistant(requireNotNull(loaded),
                    requireNotNull(prefixIndex), ContextLanguageModel(mapped("language.bin")),
                    ContextualAssistant.parsePredictions(lines("predictions.tsv")),
                    ContextualAssistant.parseGrammar(lines("grammar.tsv")), knownForms)
            } catch (_: java.io.IOException) {
                // Keep the small built-in fallback usable if an asset is missing.
            } catch (_: IllegalArgumentException) {
                // Reject malformed data; do not risk corrupting editor contents.
            }
        }
    }
}
