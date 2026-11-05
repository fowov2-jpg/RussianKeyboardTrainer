package dev.russiankeyboard.prototype

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ConversationExtractorTest {
    private fun message(text: String) = ConversationExtractor.Node(text = text, id = "app:id/message_text")
    @After fun clear() { ConversationContext.changed = null; ConversationContext.clear() }
    @Test fun onlyConversationMessagesAndTitleAreCaptured() {
        val root = ConversationExtractor.Node(children = listOf(
            ConversationExtractor.Node(text = "Анна", id = "app:id/conversation_title"),
            ConversationExtractor.Node(text = "Баланс 2000"), message("Как прошла встреча?"),
            ConversationExtractor.Node(editable = true, children = listOf(message("не отправленный черновик"))),
            ConversationExtractor.Node(password = true, children = listOf(message("секрет"))),
            ConversationExtractor.Node(visible = false, children = listOf(message("скрыто")))
        ))
        assertEquals(ConversationExtractor.Capture("Анна", listOf("Как прошла встреча?")), ConversationExtractor.extract(root))
    }
    @Test fun genericScreensAndChatListsDoNotBecomeConversations() {
        assertNull(ConversationExtractor.extract(ConversationExtractor.Node(text = "Банк", heading = true)))
        assertNull(ConversationExtractor.extract(ConversationExtractor.Node(children = listOf(
            ConversationExtractor.Node(text = "Чаты", heading = true), message("последнее сообщение")))))
    }
    @Test fun contextCannotCrossAppsSessionsOrSensitiveFields() {
        ConversationContext.begin("chat.one", 1, true)
        val old = requireNotNull(ConversationContext.active)
        ConversationContext.publish(old, "Анна", (1..20).map { "Сообщение $it" })
        assertEquals(8, ConversationContext.snapshot()!!.messages.size)
        val revision = ConversationContext.snapshot()!!.revision
        ConversationContext.publish(old, "Анна", (1..20).map { "Сообщение $it" })
        assertEquals(revision, ConversationContext.snapshot()!!.revision)
        ConversationContext.begin("chat.two", 2, true)
        ConversationContext.publish(old, "Анна", listOf("Запоздалый ответ"))
        assertNull(ConversationContext.snapshot())
        ConversationContext.begin("chat.two", 2, false)
        assertNull(ConversationContext.active)
        assertNull(ConversationContext.snapshot())
    }
}
