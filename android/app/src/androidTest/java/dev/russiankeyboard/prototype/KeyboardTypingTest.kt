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
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/**
 * Full UI input smoke: taps actual IME keys, verifies autocorrection and Undo.
 * Test only synthetic input; no user text is ever logged.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTypingTest {
    @Before fun isolateLegacyCases() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .edit().putBoolean("automatic_writing", false).commit()
    }
    @After fun restoreAutomaticDefault() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .edit().remove("automatic_writing").commit()
    }
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
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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

            // onActivity may already have made the IME visible. Never tap an
            // editor's stale bounds when the keyboard is on-screen: on a small
            // emulator that tap can land on a character (observed stray "г").
            var space: UiObject2? = findKey("Пробел", 5_000)
            if (space == null) {
                for (attempt in 0..2) {
                    if (attempt == 0) {
                        device.waitForIdle()
                        val currentEditor = device.wait(
                            Until.findObject(By.res(app.packageName, "demo_editor")), 5_000)
                        assertNotNull("Editor vanished before initial focus tap", currentEditor)
                        currentEditor!!.click()
                    }
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
            }
            val currentIme = device.executeShellCommand("settings get secure default_input_method").trim()
            assertNotNull(
                "IME not visible after editor taps and explicit show requests; selected=$currentIme",
                space
            )
            assertNotNull("Russian letter ё must be available on the keyboard", findKey("ё"))
            scenario.onActivity { activity ->
                assertEquals(
                    "Editor unexpectedly received a character before deliberate key taps",
                    "", activity.findViewById<EditText>(R.id.demo_editor).text.toString()
                )
            }

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

            // A two-error dictionary candidate needs an explicit suggestion tap.
            val lexiconDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (LexiconLoader.current == null && android.os.SystemClock.elapsedRealtime() < lexiconDeadline) {
                Thread.sleep(25)
            }
            assertNotNull("Large offline dictionary did not load", LexiconLoader.current)
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("прмыет")
                field.setSelection(field.length())
            }
            findKey("Shift", 8_000)!!.click() // refresh candidates without typing a character
            device.waitForIdle()
            val suggestion = device.wait(Until.findObject(By.text("привет").pkg(app.packageName).enabled(true)), 8_000)
            assertNotNull("Dictionary suggestion is missing", suggestion)
            assertTrue("Dictionary suggestion is disabled", suggestion!!.isEnabled)
            assertEquals("привет", suggestion.text)
            suggestion.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("привет ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
            findKey("Удалить", 8_000)!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("прмыет", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
            findKey("Пробел", 8_000)!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("прмыет ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }

            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("ппривет")
                field.setSelection(field.length())
            }
            findKey("Shift", 8_000)!!.click()
            device.waitForIdle()
            val keep = findKey("Оставить как набрано", 8_000)
            assertNotNull("Keep-original action missing", keep)
            assertTrue(keep!!.isEnabled)
            keep.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("ппривет ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }

            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("Она работал вчера")
                field.setSelection(field.length())
            }
            device.waitForIdle()
            findKey("Проверить фразу", 8_000)!!.click()
            val apply = findKey("Применить исправления фразы", 8_000)
            assertNotNull("Phrase preview did not appear", apply)
            val proposed = findKey("Предпросмотр исправлений", 8_000)
            assertEquals("Она работала вчера.", proposed!!.text)
            apply!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Она работала вчера.", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
            findKey("Удалить", 8_000)!!.click()
            device.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals("Она работал вчера", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }

            // Real idle analysis, no manual Review/Apply: the user's exact example.
            app.getSharedPreferences("keyboard", Context.MODE_PRIVATE).edit().putBoolean("automatic_writing", true).commit()
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText(""); field.setSelection(0)
            }
            // Last letter changes the word immediately: no Space, Review or pause.
            for (key in listOf("с", "е", "в", "о", "д", "н", "я")) findKey(key, 8_000)!!.click()
            scenario.onActivity { activity ->
                assertEquals("сегодня", activity.findViewById<EditText>(R.id.demo_editor).text.toString().lowercase())
            }
            findKey("Удалить", 8_000)!!.click()
            scenario.onActivity { activity ->
                assertEquals("севодня", activity.findViewById<EditText>(R.id.demo_editor).text.toString().lowercase())
            }
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("привет как дела"); field.setSelection(field.length())
            }
            findKey("Пробел", 8_000)!!.click()
            assertTrue("Automatic neural punctuation never reached the active editor", device.wait(
                Until.hasObject(By.res(app.packageName, "demo_editor").text("Привет, как дела? ")), 20_000))
            findKey("у", 8_000)!!.click()
            assertTrue("Continuing a question left a stale provisional question mark", device.wait(
                Until.hasObject(By.res(app.packageName, "demo_editor").text("Привет, как дела у")), 8_000))
            findKey("Удалить", 8_000)!!.click()
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("Как дела у тебя"); field.setSelection(field.length())
            }
            findKey("Пробел", 8_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.res(app.packageName, "demo_editor").text("Как дела у тебя? ")), 20_000))
            findKey("Удалить", 8_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.res(app.packageName, "demo_editor").text("Как дела у тебя ")), 5_000))
            // A cancelled correction must stay cancelled through the next idle pass.
            android.os.SystemClock.sleep(1_600)
            scenario.onActivity { activity ->
                assertEquals("Как дела у тебя ", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
            scenario.onActivity { activity ->
                val field = activity.findViewById<EditText>(R.id.demo_editor)
                field.setText("Спасибо за помощь"); field.setSelection(field.length())
            }
            findKey("Shift", 8_000)!!.click()
            device.waitForIdle()
            findKey("Новая строка", 8_000)!!.click()
            scenario.onActivity { activity ->
                assertEquals("Спасибо за помощь.\n", activity.findViewById<EditText>(R.id.demo_editor).text.toString())
            }
        }
    }
}
