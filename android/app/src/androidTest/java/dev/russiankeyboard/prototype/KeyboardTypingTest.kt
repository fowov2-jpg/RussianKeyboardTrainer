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
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full UI input smoke: taps actual IME keys, verifies autocorrection and Undo.
 * Test only synthetic input; no user text is ever logged.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTypingTest {
    @Test fun touchKeysAndSpaceApplyOneLocalCorrection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val inputMethod = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeId = "${app.packageName}/.RussianKeyboardImeService"

        // Gradle instrumentation may reinstall the app and change the default IME.
        val available = device.executeShellCommand("ime list -a -s")
        assertTrue("Our IME is missing from Android: $available", available.contains(app.packageName))
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        device.executeShellCommand("ime enable $imeId")
        val selection = device.executeShellCommand("ime set $imeId")
        assertTrue("Selecting IME failed: $selection",
            !selection.contains("Unknown input method", ignoreCase = true))
        val selected = device.executeShellCommand("settings get secure default_input_method").trim()
        assertEquals("Selected IME was not retained", imeId, selected)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("")
                field.isFocusableInTouchMode = true
                field.requestFocus()
                activity.window.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                )
            }

            // An input method cannot always be shown in the same frame as
            // ActivityScenario's focus change. Tap the real editor in the
            // foreground, then post the IME request to the next UI-loop tick.
            val editor = device.wait(
                Until.findObject(By.res(app.packageName, "demo_editor")), 12_000)
            assertNotNull("The demo editor is not visible", editor)

            fun findKey(key: String, timeoutMs: Long = 5_000): UiObject2? =
                device.wait(Until.findObject(By.desc(key).pkg(app.packageName)), timeoutMs)
                    ?: device.wait(Until.findObject(By.text(key).pkg(app.packageName)), 1_500)

            var space: UiObject2? = null
            for (attempt in 0..2) {
                editor!!.click()
                scenario.onActivity { activity ->
                    val field = activity.findViewById<EditText>(R.id.demo_editor)
                    field.requestFocus()
                    field.post {
                        inputMethod.showSoftInput(
                            field,
                            if (attempt == 2) InputMethodManager.SHOW_FORCED
                            else InputMethodManager.SHOW_IMPLICIT
                        )
                    }
                }
                space = findKey("Пробел")
                if (space != null) break
            }
            val currentIme = device.executeShellCommand("settings get secure default_input_method").trim()
            assertNotNull(
                "IME not visible after editor taps and explicit show requests; selected=$currentIme",
                space
            )

            for (key in listOf("с", "е", "в", "о", "д", "н", "я", "Пробел")) {
                val button = findKey(key, 8_000)
                assertNotNull("Missing Russian IME key: $key", button)
                button!!.click()
                device.waitForIdle()
            }
            scenario.onActivity { activity ->
                assertEquals("сегодня ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
            val backspace = findKey("Удалить", 8_000) ?: findKey("⌫", 1_500)
            assertNotNull("Backspace key missing", backspace)
            backspace!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("севодня", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }

            // A caret inside a word must not autocorrect the preceding prefix.
            // Pressing space should only split the word at the caret.
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("севоднятест")
                field.setSelection("севодня".length)
            }
            val midWordSpace = findKey("Пробел", 8_000)
            assertNotNull("Space key missing in middle-of-word regression", midWordSpace)
            midWordSpace!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("севодня тест",
                    activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
        }
    }
}
