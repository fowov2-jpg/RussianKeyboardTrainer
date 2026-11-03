package dev.russiankeyboard.prototype

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only artificial text; the CI workflow selects our IME via adb. */
@RunWith(AndroidJUnit4::class)
class KeyboardTypingTest {
    @Test fun touchKeysAndSpaceApplyOneLocalCorrection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val inputMethod = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("")
                field.requestFocus()
                inputMethod.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            assertNotNull(
                "The Russian IME must be visible after focus",
                device.wait(Until.findObject(By.text("Пробел").pkg(app.packageName)), 15_000)
            )
            for (key in listOf("с", "е", "в", "о", "д", "н", "я", "Пробел")) {
                val button = device.wait(Until.findObject(By.text(key).pkg(app.packageName)), 8_000)
                assertNotNull("Missing IME key: $key", button)
                button.click()
                device.waitForIdle()
            }
            scenario.onActivity { activity ->
                assertEquals("сегодня ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
        }
    }
}
