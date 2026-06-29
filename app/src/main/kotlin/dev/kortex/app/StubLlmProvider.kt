package dev.kortex.app

import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.state.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Placeholder so the app runs end-to-end before the real Claude client exists.
 * Phase 1 replaces this with a Ktor-based ClaudeProvider in :core-agent (or :llm-claude).
 */
class StubLlmProvider : LlmProvider {
    override suspend fun complete(req: LlmRequest): LlmResponse {
        if (req.tools.isEmpty()) return LlmResponse(Message(Message.Role.ASSISTANT, "tool_task"))
        val user = req.messages.lastOrNull { it.role == Message.Role.USER }?.content ?: ""
        return LlmResponse(
            Message(
                Message.Role.ASSISTANT,
                "[stub] I received: \"$user\". Wire up ClaudeProvider (Phase 1) for real answers.",
            )
        )
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
}
