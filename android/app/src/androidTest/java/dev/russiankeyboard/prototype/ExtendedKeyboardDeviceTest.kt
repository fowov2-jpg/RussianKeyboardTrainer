package dev.russiankeyboard.prototype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.channels.FileChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 7500 unique synthetic/public inputs. Actual bundled model, no mocked predictions. */
@RunWith(AndroidJUnit4::class)
class ExtendedKeyboardDeviceTest {
    @Test fun diverseWordsAndNaturalPhrasesOnActualAndroidRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = ExtendedCase.read(InstrumentationRegistry.getInstrumentation().context.assets.open("extended_qa.tsv"))
        fun mapped(name: String) = context.assets.openFd(name).use { asset -> asset.createInputStream().use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) } }
        val morphology = MorphologyDictionary(mapped("context/morphology.bin"))
        val spelling = SpellingIndex(mapped("context/spelling.bin"))
        val lexicon = RussianLexicon(mapped("lexicon/russian.bin"))
        val wordQa = ExtendedWordQa(RussianCorrectionEngine({ morphology }, { lexicon }), morphology, spelling)
        val report = ExtendedQaReport("Android 15 3GB emulator; production live controller and actual bundled RUPunct INT8. Synthetic/public corpus, component/inference tests; not physical-phone UI latency.")
        OnDevicePunctuator.open(context).use { model ->
            for (case in cases) {
                val row = if (case.kind in setOf("typo", "word_keep", "protected")) wordQa.run(case) else {
                    val start = System.nanoTime()
                    val output = model.predict(case.input, mode = PunctuationPlanner.Mode.FINALIZE)?.text ?: case.input
                    ExtendedPhraseQa.run(case, output, System.nanoTime() - start)
                }
                report.rows.add(row)
            }
        }
        File(context.filesDir, "extended-qa-summary.json").writeText(report.json())
        File(context.filesDir, "extended-qa-cases.tsv").writeText(report.casesTsv())
        assertEquals(7500, report.rows.size)
        assertEquals("Contract failures; see extended-qa-summary.json (annotation differences are separately recorded)",
            0, report.rows.count { it["contract_passed"] != true })
    }
}
