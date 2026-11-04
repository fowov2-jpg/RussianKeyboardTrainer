package dev.russiankeyboard.prototype

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
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

            // UiAutomator sees the SYSTEM foreground, not just this Activity.
            // A slow Google APIs emulator can surface a Pixel Launcher ANR
            // above our correctly started editor. Recover that unrelated
            // system dialog once, then bring THIS Activity back to RESUMED.
            // Never dismiss an ANR for our own app or skip real IME key taps.
            fun findEditor(): UiObject2? =
                device.wait(Until.findObject(By.res(app.packageName, "demo_editor")), 8_000)
            var editor = findEditor()
            if (editor == null) {
                val launcherAnr =
                    device.findObject(By.textContains("Pixel Launcher isn't responding"))
                if (launcherAnr != null) {
                    val closeLauncher = device.findObject(By.text("Close app"))
                    assertNotNull("Cannot dismiss the unrelated Pixel Launcher ANR", closeLauncher)
                    closeLauncher!!.click()
                }
                // An ActivityScenario in RESUMED state can still be occluded
                // by another task/dialog. Transition explicitly to restore
                // foreground visibility before using UiAutomator.
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                editor = findEditor()
            }
            assertNotNull("The demo editor is not visible after foreground recovery", editor)

            // The IME cannot always be shown in the same frame as a focus
            // change. Tap the real editor and post showSoftInput to the UI loop.

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
            assertNotNull("Russian letter ё must be available on the keyboard", findKey("ё"))

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

            // A pending Undo must not rewrite a prefix if the caret has moved
            // before a letter. Backspace should only delete the preceding space.
            for (key in listOf("с", "е", "в", "о", "д", "н", "я", "Пробел")) {
                val button = findKey(key, 8_000)
                assertNotNull("Missing Russian IME key: $key", button)
                button!!.click()
                device.waitForIdle()
            }
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("сегодня тест")
                field.setSelection("сегодня ".length)
            }
            val midWordUndo = findKey("Удалить", 8_000) ?: findKey("⌫", 1_500)
            assertNotNull("Backspace key missing in Undo caret regression", midWordUndo)
            midWordUndo!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("сегоднятест",
                    activity.findViewById<EditText>(R.id.demo_editor).text.toString())
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
