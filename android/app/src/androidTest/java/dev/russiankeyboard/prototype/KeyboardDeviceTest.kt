package dev.russiankeyboard.prototype

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardDeviceTest {
    @Test fun imeIsDeclaredWithRequiredPermissionAndNoInternet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val service = pm.getServiceInfo(
            ComponentName(context, RussianKeyboardImeService::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals("android.permission.BIND_INPUT_METHOD", service.permission)
        val permissions = pm.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toList()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
    }

    @Test fun launcherHasAnEditableTestField() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.demo_editor)
                assertNotNull(editor)
                editor.setText("Я севодня")
                assertEquals("Я севодня", editor.text.toString())
            }
        }
    }

    @Test fun russianCorrectionWorksOnAndroidRuntime() {
        val engine = RussianCorrectionEngine()
        assertEquals("сегодня ", engine.onSpace("Я севодня")?.replacementWithSpace)
        assertEquals("думаю, что ", engine.onSpace("Я думаю что")?.replacementWithSpace)
        assertNull(engine.onSpace("ты вчера ходил"))
    }

    @Test fun packagedMillionEntryAssetLoadsWithoutHeapDictionary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LexiconLoader.warmUp(context)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (LexiconLoader.assistant == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25)
        }
        val lexicon = requireNotNull(LexiconLoader.current) { "Packaged mmap lexicon failed to load" }
        assertEquals(100_000, lexicon.wordCount)
        assertEquals(1_000_000, lexicon.typoCount)
        assertEquals("привет", lexicon.lookup("ппривет")?.word)
        val engine = RussianCorrectionEngine(morphology = { LexiconLoader.morphology }, lexicon = { lexicon })
        assertEquals("привет ", engine.onSpace("ппривет")?.replacementWithSpace)
        assertEquals("привет ", engine.suggest("прмыет")?.edit?.replacementWithSpace)
        assertNull(engine.onSpace("прмыет"))
        assertNull(engine.onSpace("привет"))
        val assistant = requireNotNull(LexiconLoader.assistant)
        assertEquals("Она работала вчера", assistant.review("Она работал вчера")?.proposed)
        assertEquals("Привет как дела", assistant.review("Ппривет как длела")?.proposed)
    }
}
