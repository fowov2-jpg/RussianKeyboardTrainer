package dev.russiankeyboard.prototype

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Русская клавиатура"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "Офлайн · Без передачи текста · Тестовая версия 1.0.0-rc1"
            textSize = 13f
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        })
        column.addView(Button(this).apply {
            text = "1. Включить клавиатуру"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })
        column.addView(Button(this).apply {
            text = "2. Выбрать клавиатуру"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        })
        column.addView(TextView(this).apply {
            text = "Попробуй: превет → привет после пробела. Backspace отменяет " +
                "исправление. Исправление целых предложений ещё разрабатывается."
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        })
        column.addView(EditText(this).apply {
            id = R.id.demo_editor
            hint = "Напиши фразу здесь…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = android.view.Gravity.TOP
            textSize = 18f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(column)
    }
}
