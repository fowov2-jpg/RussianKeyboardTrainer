package dev.russiankeyboard.prototype

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Non-exported debug fixture; contains synthetic messages only. */
class ChatContextFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ChatContextFixtureActivity).apply { id = R.id.conversation_title; text = "Анна"; textSize = 24f })
            addView(TextView(this@ChatContextFixtureActivity).apply { id = R.id.message_text; text = "Когда пришлёшь документы?" })
            addView(EditText(this@ChatContextFixtureActivity).apply { id = R.id.demo_editor; hint = "Сообщение"; inputType = InputType.TYPE_CLASS_TEXT })
            addView(EditText(this@ChatContextFixtureActivity).apply { id = R.id.sensitive_editor; hint = "Пароль"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        })
    }
}
