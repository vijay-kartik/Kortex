package dev.kortex.app.domain.agent

import kotlinx.coroutines.flow.Flow

/** The user's chosen LLM provider id and model, as set in Settings. */
interface ModelSelection {
    val activeProvider: Flow<String>
    val activeModel: Flow<String>
}

/**
 * The models an agent run uses: [reasoning] for answers, [fast] for routing and other cheap
 * FAST-tier calls. [of] is the one place that decides them from the user's selection.
 */
data class ActiveModels(val reasoning: String, val fast: String) {
    companion object {
        const val OLLAMA = "ollama"
        const val OLLAMA_CLOUD = "ollama-cloud"
        private const val OPENAI_FAST = "gpt-4o-mini"

        /** Ollama hosts (local or cloud) don't serve OpenAI's mini model, so FAST runs on the selected model there too. */
        fun of(provider: String, selectedModel: String): ActiveModels = ActiveModels(
            reasoning = selectedModel,
            fast = if (provider == OLLAMA || provider == OLLAMA_CLOUD) selectedModel else OPENAI_FAST,
        )
    }
}
