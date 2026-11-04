package dev.russiankeyboard.prototype

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the actual bundled language model, never a mocked numeric smoke model. */
@RunWith(AndroidJUnit4::class)
class PunctuationDeviceTest {
    @Test fun bundledInt8ModelRestoresRealSentencesOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val start = SystemClock.elapsedRealtime()
        OnDevicePunctuator.open(context).use { model ->
            val load = SystemClock.elapsedRealtime() - start
            val times = mutableListOf<Long>()
            for ((input, expected) in listOf(
                "привет как дела" to "Привет, как дела?",
                "Привет как дела" to "Привет, как дела?",
                "как дела у тебя" to "Как дела у тебя?",
                "я знаю как дела" to "Я знаю, как дела",
                "я думаю что завтра будет дождь" to "Я думаю, что завтра будет дождь",
                "Привет, как дела?" to "Привет, как дела?")) {
                val before = SystemClock.elapsedRealtime()
                val output = model.predict(input, mode = PunctuationPlanner.Mode.STREAMING)?.text ?: input
                times.add(SystemClock.elapsedRealtime() - before)
                assertEquals(expected, output)
            }
            assertEquals("Спасибо за помощь.", model.predict("спасибо за помощь", mode = PunctuationPlanner.Mode.FINALIZE)?.text)
            assertNull(model.predict("https://example.com/привет", mode = PunctuationPlanner.Mode.FINALIZE))
            times.sort()
            Log.i("RSK_PUNCT_BENCH", "load_ms=$load median_ms=${times[times.size / 2]} max_ms=${times.last()} scope=android_emulator_synthetic")
        }
    }
}
