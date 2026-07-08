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
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>").append(msg.role.name.lowercase()).append("\n")
            sb.append(msg.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
