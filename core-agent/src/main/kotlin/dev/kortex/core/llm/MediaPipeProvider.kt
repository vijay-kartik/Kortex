package dev.kortex.core.llm

import android.content.Context

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM provider using MediaPipe LLM Inference API (AICore / GPU / CPU).
 */
class MediaPipeProvider(
    private val context: Context,
    private val modelPath: String,
    private val logger: Logger?,
) : LlmProvider {

    private var llmInference: LlmInference? = null

    private suspend fun getOrCreateInference(): LlmInference = withContext(Dispatchers.IO) {
        llmInference?.let { return@withContext it }

        if (!File(modelPath).exists()) {
            throw IllegalStateException("Model file not found at $modelPath")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        return@withContext LlmInference.createFromOptions(context, options).also {
            llmInference = it
        }
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse = withContext(Dispatchers.IO) {
        val inference = getOrCreateInference()
        val prompt = formatPrompt(req.messages)
        val result = inference.generateResponse(prompt)
        
        LlmResponse(
            message = Message(Message.Role.ASSISTANT, result),
            inputTokens = 0,
            outputTokens = 0
        )
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val prompt = formatPrompt(req.messages)
        val inference = try {
            getOrCreateInference()
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        // MediaPipe generateResponseAsync requires a listener set during creation,
        // which makes streaming per-request tricky with a single shared instance 
        // unless we recreate the instance or use a synchronized callback.
        // For simplicity in this implementation, we'll just return the full response at once.
        try {
            val result = inference.generateResponse(prompt)
            send(LlmChunk.Text(result))
            send(LlmChunk.Done)
            close()
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { }
    }

    private fun formatPrompt(messages: List<Message>): String {
        val sb = java.lang.StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                Message.Role.SYSTEM -> sb.append(msg.content).append("\n\n")
                Message.Role.USER -> sb.append("<start_of_turn>user\n").append(msg.content).append("<end_of_turn>\n")
                Message.Role.ASSISTANT -> sb.append("<start_of_turn>model\n").append(msg.content).append("<end_of_turn>\n")
                Message.Role.TOOL -> Unit // Tools not supported out of the box in basic Gemma without special format
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
