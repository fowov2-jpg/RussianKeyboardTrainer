package dev.russiankeyboard.prototype

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter

class KeyboardSettingsActivity : Activity() {
    private var accessStatus: TextView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("keyboard", MODE_PRIVATE)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this).apply { text = "Настройки клавиатуры"; textSize = 22f })
        fun toggle(key: String, label: String, default: Boolean = true) {
            column.addView(Switch(this).apply {
                text = label; textSize = 17f
                isChecked = prefs.getBoolean(key, default)
                setPadding(0, padding, 0, padding)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    if (key == "screen_context" && checked && !ConversationContext.connected)
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })
        }
        toggle("autocorrect", "Исправлять явные опечатки")
        toggle("automatic_writing", "Автоматически исправлять фразу и пунктуацию")
        toggle("suggestions", "Контекстные подсказки и продолжения слов")
        val screenContext = resources.getBoolean(R.bool.screen_context_supported)
        if (screenContext) {
            toggle("screen_context", "Учитывать видимый чат при наборе", false)
            accessStatus = TextView(this).apply { textSize = 14f }
            column.addView(accessStatus)
            column.addView(Button(this).apply {
                text = "Доступ Android к контексту"; isAllCaps = false
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        }
        column.addView(TextView(this).apply {
            text = "Пунктуация исправляется автоматически во время набора; Backspace отменяет замену. " +
                "При продолжении фразы предварительный знак вопроса снимается.\n\n" +
                if (screenContext) "Контекст доступного чата используется только при открытой клавиатуре. " +
                "Пароли и поля ввода исключены. Видимые сообщения не сохраняются и никуда не отправляются. " +
                "Если приложение скрывает переписку от Android, задайте контекст вручную."
                else "Эта версия не читает экран и сообщения собеседника. " +
                    "Исправляется текст, который вы набираете. Собеседника и заметку о разговоре можно задать вручную ниже."
            textSize = 15f
        })
        fun gender(label: String, value: String?): Spinner {
            column.addView(TextView(this).apply { text = label; textSize = 17f; setPadding(0, padding, 0, 0) })
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@KeyboardSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Не указан", "Мужской", "Женский"))
                setSelection(when (value) { "Masc" -> 1; "Fem" -> 2; else -> 0 })
                column.addView(this)
            }
        }
        val identity = intent.getStringExtra("profile_identity") ?: "${packageName}:manual"
        val title = intent.getStringExtra("profile_title").orEmpty()
        val profile = WritingProfiles.read(prefs, identity)
        val speaker = gender("Мой род в сообщениях (я)", profile.speakerGender)
        column.addView(TextView(this).apply {
            text = if (title.isNotBlank()) "Собеседник из заголовка: $title" else "Контекст, заданный вручную"
            textSize = 17f; setPadding(0, padding, 0, 0)
        })
        val name = EditText(this).apply {
            hint = "Имя / кому пишу"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(profile.recipientName.ifBlank { title }); column.addView(this)
        }
        val recipient = gender("Род собеседника (ты)", profile.recipientGender)
        val note = EditText(this).apply {
            hint = "О чём разговор и что учитывать"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; setText(profile.note); column.addView(this)
        }
        column.addView(TextView(this).apply {
            text = "Род не угадывается по имени или чужим репликам. Эти введённые вручную параметры " +
                "сохраняются на устройстве. Без доступного заголовка подтвердите ручной контекст " +
                "кнопкой «Вручную» на клавиатуре для текущего ввода."
            textSize = 14f
        })
        column.addView(Button(this).apply {
            text = "Сохранить контекст"; isAllCaps = false
            setOnClickListener {
                fun value(spinner: Spinner): String? = when (spinner.selectedItemPosition) { 1 -> "Masc"; 2 -> "Fem"; else -> null }
                val key = WritingProfiles.key(identity)
                val edit = prefs.edit()
                val mine = value(speaker); val theirs = value(recipient)
                if (mine == null) edit.remove("speaker_gender") else edit.putString("speaker_gender", mine)
                if (theirs == null) edit.remove("recipient_gender_$key") else edit.putString("recipient_gender_$key", theirs)
                edit.putString("recipient_name_$key", name.text.toString().take(100))
                edit.putString("conversation_note_$key", note.text.toString().take(800)).apply()
                finish()
            }
        })
        column.addView(Button(this).apply {
            text = "Очистить сохранённые профили"; isAllCaps = false
            setOnClickListener {
                android.app.AlertDialog.Builder(this@KeyboardSettingsActivity).setTitle("Удалить введённые профили?")
                    .setMessage("Настройки собеседников и ручной контекст будут удалены. Сами сообщения не сохранялись.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                        val edit = prefs.edit()
                        for (key in prefs.all.keys) if (key.startsWith("recipient_") || key.startsWith("conversation_note_") || key == "speaker_gender") edit.remove(key)
                        edit.apply(); recreate()
                    }.show()
            }
        })
        column.addView(Button(this).apply {
            text = "Источники словаря"; isAllCaps = false
            setOnClickListener {
                val notice = listOf("lexicon/NOTICE.txt", "common/NOTICE.txt", "context/NOTICE.txt", "context/OpenCorpora-NOTICE.txt", "punctuation/NOTICE.txt").joinToString("\n\n") { name ->
                    assets.open(name).bufferedReader().use { it.readText() }
                }
                android.app.AlertDialog.Builder(this@KeyboardSettingsActivity)
                    .setTitle("Открытые источники").setMessage(notice).setPositiveButton("Закрыть", null).show()
            }
        })
        column.addView(Button(this).apply { text = "Готово"; isAllCaps = false; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onResume() {
        super.onResume()
        accessStatus?.text = if (ConversationContext.connected) "Доступ Android: включён" else "Доступ Android: не включён"
    }
}
