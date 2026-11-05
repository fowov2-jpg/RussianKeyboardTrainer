package dev.offline.smartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/**
 * Single-surface keyboard with per-pointer touch tracking.
 *
 * One view owns ALL keys. Each ACTION_DOWN / ACTION_POINTER_DOWN is recorded
 * by pointer ID, so two-thumb rollovers never fight separate Button widgets.
 * No dictionary lookups or InputConnection calls happen during drawing.
 */
internal class KeyboardTouchView(
    context: Context,
    private val dispatch: (String) -> Unit
) : View(context) {

    private data class Key(val label: String, val code: String, val hit: RectF)
    private val keys = ArrayList<Key>(48)
    private val downKeys = HashMap<Int, Key>()
    private val repeats = HashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 38, 49)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private var numericMode = false
    private var shifted = false
    private var suggestions: List<String> = emptyList()
    private var typed = ""
    private var safeBottom = if (Build.VERSION.SDK_INT >= 35) dp(60) else 0

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "Русская экранная клавиатура"
        setBackgroundColor(Color.rgb(215, 220, 229))
    }

    fun update(modeNumeric: Boolean, shift: Boolean, words: List<String>, current: String) {
        val layoutChanged = modeNumeric != numericMode
        numericMode = modeNumeric
        shifted = shift
        suggestions = words.take(3)
        typed = current
        if (layoutChanged) rebuild()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
            // Some devices consume the nav insets before dispatching to the IME.
            val newSafe = if (Build.VERSION.SDK_INT >= 35)
                max(dp(60), max(nav, tappable))
            else max(nav, tappable)
            if (newSafe != safeBottom) {
                safeBottom = newSafe
                requestLayout()
            }
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wantedHeight = dp(270) + safeBottom
        setMeasuredDimension(
            View.resolveSize(dp(240), widthMeasureSpec),
            View.resolveSize(wantedHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        keys.clear()

        val side = dp(3).toFloat()
        val top = dp(3).toFloat()
        val strip = dp(38).toFloat()
        val availableBottom = (height - safeBottom).toFloat()
        val rowH = (availableBottom - top - strip - dp(7)) / 4f

        fun row(labels: List<Pair<String, Float>>, y: Float, inset: Float = 0f) {
            val x0 = side + inset
            val fullWidth = width - 2f * (side + inset)
            val totalWeight = labels.sumOf { it.second.toDouble() }.toFloat()
            var x = x0
            labels.forEach { (label, weight) ->
                val cellW = fullWidth * weight / totalWeight
                val code = when (label) {
                    "⇧" -> "SHIFT"
                    "⌫" -> "DELETE"
                    "?123", "АБВ" -> "MODE"
                    "Пробел" -> "SPACE"
                    "↵" -> "ENTER"
                    "," -> "COMMA"
                    "." -> "PERIOD"
                    else -> "CHAR:$label"
                }
                keys.add(Key(label, code, RectF(x, y, x + cellW, y + rowH)))
                x += cellW
            }
        }

        // Strip is only for suggestions, and its entire tap area is usable.
        val segWidth = (width - side * 2) / 3f
        repeat(3) { i ->
            keys.add(Key("", "SUGGEST:$i",
                RectF(side + segWidth*i, top, side + segWidth*(i + 1), top + strip)))
        }

        var y = top + strip
        if (!numericMode) {
            row("йцукенгшщзхъ".map { it.toString() to 1f }, y)
            y += rowH
            row("фывапролджэ".map { it.toString() to 1f }, y, dp(10).toFloat())
            y += rowH
            row(listOf("⇧" to 1.35f) +
                "ячсмитьбю".map { it.toString() to 1f } +
                listOf("⌫" to 1.35f), y)
        } else {
            row("1234567890".map { it.toString() to 1f }, y)
            y += rowH
            row("@#%&*+-/:=".map { it.toString() to 1f }, y)
            y += rowH
            row("()!?;'".map { it.toString() to 1f } +
                listOf("⌫" to 1.6f), y)
        }
        y += rowH
        row(listOf(
            (if (numericMode) "АБВ" else "?123") to 1.3f,
            "," to 0.7f,
            "Пробел" to 4.9f,
            "." to 0.7f,
            "↵" to 1.3f
        ), y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (keys.isEmpty()) rebuild()

        // The reserved bottom area intentionally has no interactive keys.
        fill.color = Color.rgb(215, 220, 229)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val gap = dp(1).toFloat()
        for (key in keys) {
            val isSuggestion = key.code.startsWith("SUGGEST:")
            val i = if (isSuggestion) key.code.substringAfter(':').toInt() else -1
            val text = if (isSuggestion) {
                suggestions.getOrNull(i) ?: if (i == 1) (typed.ifEmpty { "RU" }) else ""
            } else if (shifted && key.code.startsWith("CHAR:") && key.label.singleOrNull()?.isLetter() == true) {
                key.label.uppercase()
            } else key.label

            val rect = RectF(key.hit).apply {
                inset(gap, if (isSuggestion) 0f else dp(2).toFloat())
            }
            val pressed = downKeys.values.any { it.code == key.code && it.hit == key.hit }
            fill.color = when {
                isSuggestion -> Color.rgb(215, 220, 229)
                pressed -> Color.rgb(165, 183, 208)
                key.code in setOf("SHIFT", "DELETE", "MODE", "ENTER", "COMMA", "PERIOD") ->
                    Color.rgb(190, 201, 219)
                else -> Color.rgb(251, 252, 254)
            }
            canvas.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), fill)

            if (text.isNotEmpty()) {
                font.color = Color.rgb(28, 37, 51)
                font.textSize = when {
                    isSuggestion -> dp(14).toFloat()
                    text == "Пробел" -> dp(13).toFloat()
                    text.length > 3 -> dp(14).toFloat()
                    else -> dp(22).toFloat()
                }
                // Trim long words so the strip never wraps over the first key row.
                val shown = if (isSuggestion && text.length > 14) text.take(13) + "…" else text
                val baseline = rect.centerY() - (font.ascent() + font.descent()) / 2f
                canvas.drawText(shown, rect.centerX(), baseline, font)
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        // No key can be activated by touching the system navigation region.
        if (y < 0f || y >= height - safeBottom) return null
        // Each logical cell includes the small visual gaps between neighboring keys.
        keys.firstOrNull { it.hit.contains(x, y) }?.let { return it }
        // Forgive a few pixels at the outside edges without creating dead zones.
        return keys.minByOrNull {
            val dx = maxOf(it.hit.left - x, 0f, x - it.hit.right)
            val dy = maxOf(it.hit.top - y, 0f, y - it.hit.bottom)
            dx * dx + dy * dy
        }?.takeIf {
            val cx = maxOf(it.hit.left - x, 0f, x - it.hit.right)
            val cy = maxOf(it.hit.top - y, 0f, y - it.hit.bottom)
            cx < dp(10) && cy < dp(10)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val key = keyAt(event.getX(index), event.getY(index))
                if (key != null) {
                    downKeys[id] = key
                    if (key.code == "DELETE") {
                        dispatch("DELETE")
                        val repeat = object : Runnable {
                            override fun run() {
                                if (downKeys[id]?.code == "DELETE") {
                                    dispatch("DELETE")
                                    handler.postDelayed(this, 65L)
                                }
                            }
                        }
                        repeats[id] = repeat
                        handler.postDelayed(repeat, 430L)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val key = downKeys.remove(id)
                repeats.remove(id)?.let { handler.removeCallbacks(it) }
                invalidate()
                if (key != null && key.code != "DELETE") {
                    // Exactly one text event for each completed finger tap.
                    dispatch(key.code)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downKeys.clear()
                repeats.values.forEach { handler.removeCallbacks(it) }
                repeats.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        repeats.values.forEach { handler.removeCallbacks(it) }
        repeats.clear()
        downKeys.clear()
        super.onDetachedFromWindow()
    }
}
