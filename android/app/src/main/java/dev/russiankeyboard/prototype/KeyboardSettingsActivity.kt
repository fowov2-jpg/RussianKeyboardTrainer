package dev.russiankeyboard.prototype

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class KeyboardSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("keyboard", MODE_PRIVATE)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this).apply { text = "Настройки клавиатуры"; textSize = 22f })
        fun toggle(key: String, label: String) {
            column.addView(Switch(this).apply {
                text = label; textSize = 17f
                isChecked = prefs.getBoolean(key, true)
                setPadding(0, padding, 0, padding)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            })
        }
        toggle("autocorrect", "Исправлять явные опечатки по пробелу")
        toggle("suggestions", "Контекстные подсказки и продолжения слов")
        column.addView(TextView(this).apply {
            text = "«Проверить фразу» показывает предложенный текст перед заменой. " +
                "Вы решаете, применять его или оставить исходный. Backspace отменяет замену.\n\n" +
                "Всё работает офлайн. Введённый текст не сохраняется. " +
                "В паролях и адресах анализ отключён."
            textSize = 15f
        })
        column.addView(Button(this).apply {
            text = "Источники словаря"; isAllCaps = false
            setOnClickListener {
                val notice = listOf("lexicon/NOTICE.txt", "context/NOTICE.txt", "context/OpenCorpora-NOTICE.txt").joinToString("\n\n") { name ->
                    assets.open(name).bufferedReader().use { it.readText() }
                }
                android.app.AlertDialog.Builder(this@KeyboardSettingsActivity)
                    .setTitle("Открытые источники").setMessage(notice).setPositiveButton("Закрыть", null).show()
            }
        })
        column.addView(Button(this).apply { text = "Готово"; isAllCaps = false; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
