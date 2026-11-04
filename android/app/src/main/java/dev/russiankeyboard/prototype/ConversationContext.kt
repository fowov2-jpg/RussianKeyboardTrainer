package dev.russiankeyboard.prototype

/** Active-input-only ephemeral context. No message persistence or network access. */
object ConversationContext {
    data class Editor(val packageName: String, val session: Long)
    data class Snapshot(val packageName: String, val session: Long, val title: String,
                        val messages: List<String>, val revision: Long,
                        val capturedAt: Long = System.nanoTime()) {
        val key: String get() = packageName + ":" + title
        fun modelContext(note: String = ""): String =
            (listOf(note, title) + messages.takeLast(8)).filter { it.isNotBlank() }.joinToString("\n").takeLast(1600)
    }
    @Volatile var active: Editor? = null
        private set
    @Volatile var current: Snapshot? = null
        private set
    @Volatile var connected = false
    var changed: (() -> Unit)? = null
    private var revision = 0L

    fun begin(packageName: String, session: Long, permitted: Boolean) {
        val next = if (permitted) Editor(packageName, session) else null
        if (active != next) { active = next; current = null; revision++; changed?.invoke() }
    }
    fun publish(editor: Editor, title: String, messages: List<String>) {
        if (active != editor) return
        val safeTitle = title.take(100)
        val safeMessages = messages.map { it.take(400) }.takeLast(8)
        val previous = current
        if (previous?.title == safeTitle && previous.messages == safeMessages) {
            current = previous.copy(capturedAt = System.nanoTime()); return
        }
        current = Snapshot(editor.packageName, editor.session, safeTitle, safeMessages, ++revision)
        changed?.invoke()
    }
    fun clear() {
        active = null; current = null; revision++; changed?.invoke()
    }
    fun snapshot(): Snapshot? = current?.takeIf {
        active == Editor(it.packageName, it.session) && System.nanoTime() - it.capturedAt in 0..30_000_000_000L
    }
}
