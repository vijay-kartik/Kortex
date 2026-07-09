package dev.kortex.core.llm

import android.content.Context

import com.google.ai.edge.litertlm.*
import dev.kortex.core.log.Logger
import dev.kortex.core.log.i
import dev.kortex.core.state.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM provider using LiteRT-LM API (formerly MediaPipe LLM Inference API).
 */
class LiteRtProvider(
    private val context: Context,
    private val modelPath: String,
    private val logger: Logger?,
) : LlmProvider {

    private var engine: Engine? = null

    private suspend fun getOrCreateEngine(): Engine = withContext(Dispatchers.IO) {
        engine?.let { return@withContext it }

        if (!File(modelPath).exists()) {
            throw IllegalStateException("Model file not found at $modelPath")
        }

        // The GPU backend requires OpenCL; many devices don't ship libOpenCL.so and the
        // failure only surfaces asynchronously mid-conversation, so probe for it up front.
        val hasOpenCl = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so"
        ).any { File(it).exists() }
        logger?.i("LiteRtProvider", "OpenCL available=$hasOpenCl, using ${if (hasOpenCl) "GPU" else "CPU"} backend")

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = if (hasOpenCl) Backend.GPU() else Backend.CPU()
        )

        return@withContext Engine(engineConfig).also {
            it.initialize()
            engine = it
        }
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse = withContext(Dispatchers.IO) {
        val currentEngine = getOrCreateEngine()
        
        currentEngine.createConversation().use { conversation ->
            val prompt = formatPrompt(req.messages)
            val input = Contents.of(listOf(Content.Text(prompt)))
            
            // Collect the streamed response into a full string for complete()
            val sb = StringBuilder()
            conversation.sendMessageAsync(prompt).collect { token ->
                val text = (token.contents.contents.firstOrNull() as? Content.Text)?.text ?: ""
                sb.append(text)
            }
            
            LlmResponse(
                message = Message(Message.Role.ASSISTANT, sb.toString()),
                inputTokens = 0,
                outputTokens = 0
            )
        }
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val currentEngine = try {
            getOrCreateEngine()
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        val prompt = formatPrompt(req.messages)
        val input = Contents.of(listOf(Content.Text(prompt)))
        
        try {
            currentEngine.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { token ->
                    val text = (token.contents.contents.firstOrNull() as? Content.Text)?.text ?: ""
                    send(LlmChunk.Text(text))
                }
            }
            send(LlmChunk.Done)
            close()
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { }
    }

    private fun formatPrompt(messages: List<Message>): String {
        val sb = java.lang.StringBuilder()
        // Strip system prompt for local models as they waste context space
        val filtered = messages.filter { it.role != Message.Role.SYSTEM }
        for (msg in filtered) {
            when (msg.role) {
                Message.Role.USER -> sb.append("<start_of_turn>user\n").append(msg.content).append("<end_of_turn>\n")
                Message.Role.ASSISTANT -> sb.append("<start_of_turn>model\n").append(msg.content).append("<end_of_turn>\n")
                else -> Unit
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
