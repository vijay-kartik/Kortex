package dev.kortex.app.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveModelsTest {

    @Test
    fun `local Ollama runs every tier on the selected model`() {
        assertEquals(ActiveModels("llama3:latest", "llama3:latest"), ActiveModels.of("ollama", "llama3:latest"))
    }

    @Test
    fun `Ollama Cloud runs every tier on the selected model`() {
        assertEquals(ActiveModels("qwen3.5:122b", "qwen3.5:122b"), ActiveModels.of("ollama-cloud", "qwen3.5:122b"))
    }

    @Test
    fun `OpenAI reasons on the selected model and routes on gpt-4o-mini`() {
        assertEquals(ActiveModels("gpt-4-turbo", "gpt-4o-mini"), ActiveModels.of("openai", "gpt-4-turbo"))
    }
}
