package dev.russiankeyboard.prototype

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in: inspect only semantic messages in the app whose non-sensitive editor is active. */
class ConversationAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean()
    private val collect = Runnable {
        if (!reader.isShutdown && reading.compareAndSet(false, true)) reader.execute {
            try { capture() } catch (_: Exception) {
                val editor = ConversationContext.active
                handler.post { if (editor != null && ConversationContext.active == editor)
                    ConversationContext.publish(editor, "", emptyList()) }
            } finally { reading.set(false) }
        }
    }
    override fun onServiceConnected() {
        ConversationContext.connected = true
        instance = WeakReference(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val editor = ConversationContext.active ?: return
        if (event?.packageName?.toString() != editor.packageName) return
        handler.removeCallbacks(collect); handler.postDelayed(collect, 180)
    }
    private fun capture() {
        val editor = ConversationContext.active ?: return
        if (!getSharedPreferences("keyboard", MODE_PRIVATE).getBoolean("screen_context", false)) return
        val window = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) &&
                it.root?.packageName?.toString() == editor.packageName
        } ?: return
        val root = window.root ?: return
        if (root.packageName?.toString() != editor.packageName) return
        var remaining = 500
        var hasComposer = false
        data class Tree(val info: AccessibilityNodeInfo, val id: String, val type: String,
                        val heading: Boolean, val collectionItem: Boolean, val children: List<Tree>)
        fun scan(node: AccessibilityNodeInfo, depth: Int = 0, inCollection: Boolean = false): Tree? {
            if (--remaining < 0 || depth > 24) return null
            val editable = node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            // Inspect the field's role/hint, never its draft text. Collection fallback
            // supports chat apps exposing virtual rows rather than message resource IDs.
            if (editable && !node.isPassword && node.isVisibleToUser) {
                val hint = node.hintText?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName.orEmpty().lowercase()
                if (hint in setOf("сообщение", "написать сообщение", "введите сообщение", "message", "type a message", "message…", "сообщение…") ||
                    listOf("message_input", "message_edit", "chat_composer", "chat_edit_text").any { id.contains(it) }) hasComposer = true
            }
            if (editable || node.isPassword || !node.isVisibleToUser) return null
            val children = mutableListOf<Tree>()
            for (i in 0 until node.childCount) {
                if (remaining <= 0) break
                node.getChild(i)?.let { scan(it, depth + 1, node.collectionInfo != null || node.isScrollable)?.let(children::add) }
            }
            return Tree(node, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                Build.VERSION.SDK_INT >= 28 && node.isHeading, inCollection && node.collectionItemInfo != null, children)
        }
        val tree = scan(root) ?: return
        fun message(node: Tree): Boolean = ConversationExtractor.messageRole(node.id, node.type) || hasComposer && node.collectionItem
        fun hasMessages(node: Tree): Boolean = message(node) || node.children.any { hasMessages(it) }
        fun copy(node: Tree, actionBar: Boolean = false, bubble: Boolean = false): ConversationExtractor.Node {
            val isMessage = bubble || message(node)
            val role = isMessage || ConversationExtractor.titleRole(node.id, node.heading) ||
                actionBar && node.children.isEmpty()
            return ConversationExtractor.Node(
                text = if (role) node.info.text?.toString().orEmpty().take(2000) else "",
                description = if (role) node.info.contentDescription?.toString().orEmpty().take(2000) else "",
                id = if (isMessage) "message_text" else node.id, type = node.type, heading = node.heading,
                children = node.children.map { copy(it, actionBar || node.type.lowercase().contains("actionbar"), isMessage) })
        }
        val result = if (hasMessages(tree)) ConversationExtractor.extract(copy(tree)) else null
        handler.post { if (ConversationContext.active == editor)
            ConversationContext.publish(editor, result?.title.orEmpty(), result?.messages.orEmpty()) }
    }
    override fun onInterrupt() { ConversationContext.clear() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        reader.shutdownNow()
        instance = null; ConversationContext.connected = false; ConversationContext.clear()
        super.onDestroy()
    }
    companion object {
        private var instance: WeakReference<ConversationAccessibilityService>? = null
        fun refresh() { instance?.get()?.let { it.handler.removeCallbacks(it.collect); it.handler.post(it.collect) } }
    }
}
