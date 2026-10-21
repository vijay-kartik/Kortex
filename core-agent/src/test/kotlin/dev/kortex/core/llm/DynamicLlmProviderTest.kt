package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class DynamicLlmProviderTest {
    private class Settings(@Volatile var openAiKey: String?) : LlmProviderSettings {
        override suspend fun activeProvider() = "openai"
        override suspend fun openAiApiKey() = openAiKey
        override suspend fun ollamaUrl() = ""
        override suspend fun ollamaToken(): String? = null
        override suspend fun ollamaCloudApiKey(): String? = null
    }

    /** Answers with the key it was built with, so a call reveals which client served it. */
    private class KeyEchoProvider(private val key: String) : LlmProvider {
        override suspend fun complete(req: LlmRequest, logger: Logger?) =
            LlmResponse(Message(Message.Role.ASSISTANT, key))

        override fun stream(req: LlmRequest): Flow<LlmChunk> = emptyFlow()
    }

    private val req = LlmRequest(model = "m", messages = emptyList())

    @Test
    fun `concurrent calls after a key change all use the new key and build one client`() = runBlocking<Unit> {
        val built = Collections.synchronizedList(mutableListOf<String>())
        val settings = Settings("K1")
        val provider = DynamicLlmProvider(settings, KeyEchoProvider("default"), Logger.CONSOLE) { key, _, _ ->
            built += key
            KeyEchoProvider(key)
        }
        provider.complete(req).message.content shouldBe "K1"

        repeat(50) { round ->
            val key = "K${round + 2}"
            settings.openAiKey = key
            val results = (1..16).map {
                async(Dispatchers.Default) { provider.complete(req).message.content }
            }.awaitAll()
            results.toSet() shouldBe setOf(key)
        }
        built.size shouldBe 51
        built.toSet().size shouldBe 51
    }

    @Test
    fun `unchanged key reuses the cached client`() = runBlocking<Unit> {
        val builds = AtomicInteger()
        val provider = DynamicLlmProvider(Settings("K1"), KeyEchoProvider("default"), Logger.CONSOLE) { key, _, _ ->
            builds.incrementAndGet()
            KeyEchoProvider(key)
        }
        (1..20).map { async(Dispatchers.Default) { provider.complete(req) } }.awaitAll()
        builds.get() shouldBe 1
    }
}
