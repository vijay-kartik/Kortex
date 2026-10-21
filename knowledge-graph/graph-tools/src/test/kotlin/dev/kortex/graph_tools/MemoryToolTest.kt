package dev.kortex.graph_tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.tool.ToolResult
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolTest {

    /** The embedder is cancelled before the repository or builder is touched, so neither is ever used. */
    private inline fun <reified T : Any> unused(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    @Test
    fun `cancelling while the embedder is suspended does not return a failed result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val embedder = object : EmbeddingProvider {
            override suspend fun embed(text: String): FloatArray {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val tool = MemoryTool(unused<GraphRepository>(), unused<GraphBuilder>(), embedder)

        val call = async<ToolResult>(start = CoroutineStart.UNDISPATCHED) {
            tool.execute(buildJsonObject { put("query", "Asha") })
        }
        started.await()
        call.cancel()

        val thrown = runCatching { call.await() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `an embedder failure is still reported as a failed result`() = runTest {
        val embedder = object : EmbeddingProvider {
            override suspend fun embed(text: String): FloatArray = throw IllegalStateException("offline")
        }
        val tool = MemoryTool(unused<GraphRepository>(), unused<GraphBuilder>(), embedder)

        val result = tool.execute(buildJsonObject { put("query", "Asha") })

        assertEquals(false, result.ok)
        assertEquals("Failed to search memory: offline", result.content)
    }
}
