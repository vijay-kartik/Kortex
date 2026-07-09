package dev.kortex.core.eval

import dev.kortex.core.llm.DeepseekProvider
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Supplies the LLM completion text for one eval case. The prompt is still built by the
 * real prompt builders and parsed by the real parsing code — only this seam is swapped:
 * - [RecordedCompleter] (default, CI): fixture files under `src/test/resources/eval/`.
 * - [LiveCompleter] (opt-in via `KORTEX_EVAL_LIVE=1`): a real provider over the network.
 */
fun interface EvalCompleter {
    suspend fun complete(suite: String, caseId: String, req: LlmRequest): String
}

/**
 * RECORDED mode: returns the checked-in fixture at `/eval/<suite>/<caseId>.txt`.
 * Fixtures are plausible model responses (hand-written until first re-recorded live);
 * their job is regression detection on the prompt+parse pipeline, not model accuracy.
 */
class RecordedCompleter : EvalCompleter {
    override suspend fun complete(suite: String, caseId: String, req: LlmRequest): String {
        val path = "/eval/$suite/$caseId.txt"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing recorded fixture $path — add it, or run live with KORTEX_EVAL_LIVE=1")
        return stream.bufferedReader().use { it.readText() }
    }
}

/** LIVE mode: forwards the exact request (model, temperature, messages) to a real provider. */
class LiveCompleter(private val provider: LlmProvider) : EvalCompleter {
    override suspend fun complete(suite: String, caseId: String, req: LlmRequest): String =
        provider.complete(req).message.content
}

/**
 * Adapts an [EvalCompleter] to the [LlmProvider] seam so eval suites can run the real
 * components (RouterNode / AmbientTriage / LlmMemoryWriter) unmodified. Each eval case
 * makes exactly one completion call, so the adapter is bound to a single (suite, case).
 */
class EvalLlmProvider(
    private val completer: EvalCompleter,
    private val suite: String,
    private val caseId: String,
) : LlmProvider {
    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse =
        LlmResponse(Message(Message.Role.ASSISTANT, completer.complete(suite, caseId, req)))

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
}

/** Mode selection: recorded by default; live when `KORTEX_EVAL_LIVE=1` and an API key is set. */
object EvalMode {
    val isLive: Boolean get() = System.getenv("KORTEX_EVAL_LIVE") == "1"

    /**
     * Live provider selection mirrors the app's `KortexContainer`: OpenAI if
     * `OPENAI_API_KEY` is set, else Deepseek via `DEEPSEEK_API_KEY` (env vars here,
     * since tests don't see local.properties/BuildConfig).
     */
    fun resolveCompleter(): EvalCompleter {
        if (!isLive) return RecordedCompleter()
        val openAi = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
        if (openAi != null) return LiveCompleter(OpenAiProvider(apiKey = openAi))
        val deepseek = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
        if (deepseek != null) return LiveCompleter(DeepseekProvider(apiKey = deepseek))
        error("KORTEX_EVAL_LIVE=1 but neither OPENAI_API_KEY nor DEEPSEEK_API_KEY is set")
    }
}
