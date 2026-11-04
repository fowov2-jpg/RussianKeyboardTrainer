package dev.russiankeyboard.prototype

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.app.UiAutomation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDeviceTest {
    @Test fun realAccessibilityCaptureIsBoundToActiveNonSensitiveEditor() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val preferences = app.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
        val oldServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        val service = "${app.packageName}/.ConversationAccessibilityService"
        val services = if (oldServices.isBlank() || oldServices == "null") service else "$oldServices:$service"
        fun await(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        try {
            preferences.edit().putBoolean("screen_context", true).putBoolean("automatic_writing", false).commit()
            device.executeShellCommand("settings put secure enabled_accessibility_services $services")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            await("Context accessibility service did not connect") { ConversationContext.connected }
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("ime enable ${app.packageName}/.RussianKeyboardImeService")
            device.executeShellCommand("ime set ${app.packageName}/.RussianKeyboardImeService")
            ActivityScenario.launch(ChatContextFixtureActivity::class.java).use { scenario ->
                for (attempt in 0..2) {
                    scenario.onActivity { activity ->
                        val field = activity.findViewById<EditText>(R.id.demo_editor)
                        field.requestFocus()
                        field.post { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
                    }
                    if (device.wait(Until.hasObject(By.desc("Пробел").pkg(app.packageName)), 5_000)) break
                }
                assertTrue("IME never became visible in the synthetic chat", device.hasObject(By.desc("Пробел").pkg(app.packageName)))
                await("IME did not bind a non-sensitive context session") { ConversationContext.active != null }
                ConversationAccessibilityService.refresh()
                await("Visible conversation was not captured from the active application (serviceConnected=${ConversationContext.connected})") {
                    ConversationContext.snapshot()?.let { it.title == "Анна" && "Когда пришлёшь документы?" in it.messages } == true
                }
                scenario.onActivity { it.findViewById<EditText>(R.id.sensitive_editor).requestFocus() }
                await("Sensitive editor retained conversation context") { ConversationContext.active == null && ConversationContext.snapshot() == null }
            }
        } finally {
            preferences.edit().remove("screen_context").remove("automatic_writing").commit()
            if (oldServices.isBlank() || oldServices == "null") {
                device.executeShellCommand("settings delete secure enabled_accessibility_services")
                device.executeShellCommand("settings put secure accessibility_enabled 0")
            } else device.executeShellCommand("settings put secure enabled_accessibility_services $oldServices")
            ConversationContext.clear()
        }
    }
}
