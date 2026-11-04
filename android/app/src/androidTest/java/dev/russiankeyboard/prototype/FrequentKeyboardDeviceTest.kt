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

@RunWith(AndroidJUnit4::class)
class FrequentKeyboardDeviceTest {
    @Test fun frequentWordsAndShortMessagesWithActualBundledModel() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val cases=ExtendedCase.read(InstrumentationRegistry.getInstrumentation().context.assets.open("common_qa.tsv"))
        fun mapped(name:String)=context.assets.openFd(name).use { asset -> asset.createInputStream().use {
            it.channel.map(FileChannel.MapMode.READ_ONLY,asset.startOffset,asset.declaredLength) } }
        val morphology=MorphologyDictionary(mapped("context/morphology.bin"));val spelling=SpellingIndex(mapped("context/spelling.bin"))
        val lexicon=RussianLexicon(mapped("lexicon/russian.bin"));val common=RussianLexicon(mapped("common/russian.bin"))
        val old=ExtendedWordQa(RussianCorrectionEngine({morphology},{lexicon}),morphology,spelling)
        val current=ExtendedWordQa(RussianCorrectionEngine({morphology},{lexicon},{common}),morphology,spelling)
        val before=ExtendedQaReport("Android emulator, frequent-dialogue proxy, common index disabled; component tests, no private chat data.")
        val after=ExtendedQaReport("Android emulator, frequent-dialogue proxy, common index enabled; actual bundled RUPunct and live controller; not physical-phone UI latency.")
        OnDevicePunctuator.open(context).use { model ->
            for(case in cases) {
                if(case.kind in setOf("typo","word_keep","protected")) {
                    before.rows.add(old.run(case));after.rows.add(current.run(case))
                } else {
                    val start=System.nanoTime();val output=model.predict(case.input,mode=PunctuationPlanner.Mode.FINALIZE)?.text?:case.input
                    val row=ExtendedPhraseQa.run(case,output,System.nanoTime()-start);before.rows.add(row);after.rows.add(row)
                }
            }
        }
        File(context.filesDir,"common-qa-before.json").writeText(before.json())
        File(context.filesDir,"common-qa-after.json").writeText(after.json())
        File(context.filesDir,"common-qa-before.tsv").writeText(before.casesTsv())
        File(context.filesDir,"common-qa-after.tsv").writeText(after.casesTsv())
        assertEquals(7500,after.rows.size)
        assertEquals("See common-qa-after.json for contract failures",0,after.rows.count { it["contract_passed"]!=true })
    }
}
