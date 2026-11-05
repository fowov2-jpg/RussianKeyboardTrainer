package dev.offline.smartkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Offline Smart Keyboard v0.14\n\nОбычная раскладка ЙЦУКЕН. Блоки T9 1–9 скрыты внутри алгоритма поиска. Проверка слов учитывает предыдущее слово. Всё работает без интернета. Это экспериментальный прототип."
            textSize = 19f
        })
        root.addView(Button(this).apply {
            text = "1. Включить клавиатуру в настройках"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })
        root.addView(Button(this).apply {
            text = "2. Выбрать активную клавиатуру"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        })
        root.addView(EditText(this).apply {
            hint = "Проверка: T9 564236 = привет, 6325359 = сегодня"
            minLines = 3
            textSize = 18f
        })
        setContentView(root)
    }
}
