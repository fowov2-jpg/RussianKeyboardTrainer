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
        val pad = (20 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Русская клавиатура — ранний офлайн-прототип.\n\n" +
                "Есть русская раскладка, пробел, удаление, смена регистра, " +
                "несколько очевидных автозамен и демонстрационное правило запятой.\n\n" +
                "Нейросетевые модели и контекст собеседника пока не подключены. " +
                "Вводимый текст никуда не отправляется."
            textSize = 18f
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
        column.addView(EditText(this).apply {
            id = R.id.demo_editor
            hint = "Набери: Я севодня"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            textSize = 17f
        })
        setContentView(column)
    }
}
