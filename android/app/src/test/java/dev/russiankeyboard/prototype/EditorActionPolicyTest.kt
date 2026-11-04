package dev.russiankeyboard.prototype

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class EditorActionPolicyTest {
    @Test fun explicitNewlineAndMultilineFieldsCannotTurnEnterIntoSend() {
        assertNull(EditorActionPolicy.action(InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(EditorActionPolicy.action(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND))
    }
    @Test fun sendActionHasAVisibleSendLabel() {
        val action = requireNotNull(EditorActionPolicy.action(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        assertEquals(EditorInfo.IME_ACTION_SEND, action.code)
        assertEquals("Отправить", action.description)
        assertNotEquals("↵", action.label)
    }
}
