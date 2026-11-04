package dev.russiankeyboard.prototype

/** Semantic chat roles only: unrelated screen labels and editor contents are excluded. */
object ConversationExtractor {
    data class Node(val text: String = "", val description: String = "", val id: String = "",
                    val type: String = "", val editable: Boolean = false, val password: Boolean = false,
                    val visible: Boolean = true, val heading: Boolean = false,
                    val children: List<Node> = emptyList())
    data class Capture(val title: String, val messages: List<String>)
    fun messageRole(id: String, type: String): Boolean =
        listOf("message_text", "messagetext", "msg_text", "bubble_text", "body_text").any { id.lowercase().contains(it) } ||
            type.lowercase().contains("chatmessagecell") || type.lowercase().contains("messagebubble")
    fun titleRole(id: String, heading: Boolean): Boolean = heading ||
        listOf("conversation_contact", "conversation_title", "chat_title", "action_bar_title", "toolbar_title", "contact_name").any { id.lowercase().contains(it) }

    fun extract(root: Node): Capture? {
        var title = ""
        val messages = mutableListOf<String>()
        var visited = 0
        fun walk(node: Node, actionBar: Boolean = false) {
            if (++visited > 500 || !node.visible || node.editable || node.password || node.type.contains("EditText")) return
            val id = node.id.lowercase()
            val type = node.type.lowercase()
            val text = node.text.ifBlank { node.description }.trim()
            val header = titleRole(id, node.heading)
            val message = messageRole(id, type)
            if (title.isEmpty() && text.isNotEmpty() && (header || actionBar && node.children.isEmpty())) title = text.take(100)
            if (message && text.isNotEmpty()) {
                if (messages.lastOrNull() != text) messages.add(text.take(400))
            } else for (child in node.children) walk(child, actionBar || type.contains("actionbar"))
        }
        walk(root)
        // A generic heading alone (e.g. a banking screen) is not a conversation.
        return if (messages.isNotEmpty() && title.lowercase() !in setOf("чаты", "chats", "сообщения", "messages", "все чаты", "telegram", "whatsapp"))
            Capture(title, messages.takeLast(8)) else null
    }
}
