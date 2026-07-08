package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlamaCppProvider(
    private val modelPath: String,
    private val logger: Logger? = null
) : LlmProvider {

    private var nativeContext: Long = 0

    init {
        System.loadLibrary("kortex-llama")
        nativeContext = loadModelNative(modelPath)
        if (nativeContext == 0L) {
            throw IllegalStateException("Failed to load Llama model natively")
        }
    }

    interface LlamaCallback {
        fun onToken(token: String)
    }

    private external fun loadModelNative(path: String): Long
    private external fun generateNative(ctx: Long, prompt: String, callback: LlamaCallback?): String
    private external fun closeModelNative(ctx: Long)

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse = withContext(Dispatchers.IO) {
        val prompt = formatPrompt(req.messages)
        val result = generateNative(nativeContext, prompt, null)
        LlmResponse(
            message = Message(Message.Role.ASSISTANT, result),
            inputTokens = 0,
            outputTokens = 0
        )
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val prompt = formatPrompt(req.messages)
        launch(Dispatchers.IO) {
            generateNative(nativeContext, prompt, object : LlamaCallback {
                override fun onToken(token: String) {
                    trySendBlocking(LlmChunk.Text(token))
                }
            })
            trySendBlocking(LlmChunk.Done)
            close()
        }
        awaitClose { }
    }

    fun close() {
        if (nativeContext != 0L) {
            closeModelNative(nativeContext)
            nativeContext = 0L
        }
    }

    private fun formatPrompt(messages: List<Message>): String {
        // For local models, strip the system prompt to save tokens — the verbose agentic
        // instructions (tool-use, web_search, etc.) don't apply to a simple local completion.
        val filtered = messages.filter { it.role != Message.Role.SYSTEM }

        val pathLower = modelPath.lowercase()
        return when {
            pathLower.contains("gemma") -> {
                // Gemma uses <start_of_turn>user / <start_of_turn>model — no system role.
                filtered.joinToString("") {
                    val role = if (it.role == Message.Role.USER) "user" else "model"
                    "<start_of_turn>$role\n${it.content}<end_of_turn>\n"
                } + "<start_of_turn>model\n"
            }
            pathLower.contains("llama-3") || pathLower.contains("llama3") -> {
                filtered.joinToString("") {
                    "<|start_header_id|>${it.role.name.lowercase()}<|end_header_id|>\n\n${it.content}<|eot_id|>"
                } + "<|start_header_id|>assistant<|end_header_id|>\n\n"
            }
            else -> { // Fallback to ChatML
                filtered.joinToString("") {
                    "<|im_start|>${it.role.name.lowercase()}\n${it.content}<|im_end|>\n"
                } + "<|im_start|>assistant\n"
            }
        }
    }
}
