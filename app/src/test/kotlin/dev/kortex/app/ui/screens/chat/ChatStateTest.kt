package dev.kortex.app.ui.screens.chat

import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chat screen derives from its state, without the ViewModel. */
class ChatStateTest {

    private val turn = ChatTurn(Message(Message.Role.USER, "hi"))
    private val file = Attachment(mimeType = "text/plain", dataBase64 = "", filename = "notes.txt")

    @Test
    fun `the empty state shows only before the first message`() {
        assertTrue(ChatState().showEmptyState)
        assertFalse(ChatState(busy = true).showEmptyState)
        assertFalse(ChatState(turns = listOf(turn)).showEmptyState)
    }

    @Test
    fun `send is offered for typed text or a staged attachment`() {
        assertFalse(ChatState().canSend(""))
        assertFalse(ChatState().canSend("   "))
        assertTrue(ChatState().canSend("hello"))
        assertTrue(ChatState(stagedAttachments = listOf(file)).canSend(""))
    }

    @Test
    fun `the busy line names the step, provider and model`() {
        assertEquals("working…", ChatState().busyLine)
        assertEquals(
            "tool usage: web_search • ollama / gemma4:31b",
            ChatState(status = "Tool usage: web_search", activeProvider = "ollama", activeModel = "gemma4:31b").busyLine,
        )
        assertEquals("thinking… • openai", ChatState(status = "thinking…", activeProvider = "openai").busyLine)
    }

    @Test
    fun `the model footer names the provider once a model is known`() {
        assertNull(ChatState(activeProvider = "ollama").modelFooter)
        assertEquals("gemma4:31b • Ollama", ChatState(activeProvider = "ollama", activeModel = "gemma4:31b").modelFooter)
        assertEquals(
            "gemma4:31b • Ollama Cloud",
            ChatState(activeProvider = "ollama-cloud", activeModel = "gemma4:31b").modelFooter,
        )
        assertEquals("gpt-4-turbo • OpenAI", ChatState(activeProvider = "openai", activeModel = "gpt-4-turbo").modelFooter)
    }
}
