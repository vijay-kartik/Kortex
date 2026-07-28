package dev.kortex.app.tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphRepository
import dev.kortex.graph_storage.GraphStorageConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Allows the agent to query the knowledge graph memory.
 * Uses real embeddings to perform the vector search.
 */
class MemoryTool(
    private val repository: GraphRepository,
    private val graphBuilder: GraphBuilder,
    private val embedder: EmbeddingProvider
) : Tool {

    override val name = "memory_search"
    override val description = "Search the knowledge graph for past facts, persons, and topics."
    override val parameters = ToolSchema(
        listOf(
            ToolParam("query", "string", "The search query (e.g., a person's name or a topic)"),
            ToolParam("maxResults", "integer", "Maximum number of results to return", required = false)
        )
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing query")
        val maxResults = args["maxResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5

        return try {
            val queryEmbedding = embedder.embed(query)
            val handles = repository.searchSimilar(queryEmbedding, maxResults)
            
            if (handles.isEmpty()) {
                return ToolResult(true, "No memories found for query: $query")
            }

            val builder = StringBuilder("Found memories:\n")
            val seen = mutableSetOf<Long>()
            handles.forEach { handle ->
                if (!seen.add(handle.graphKey)) return@forEach
                val summary = graphBuilder.getNodeSummary(handle.graphKey) ?: return@forEach
                builder.append("- $summary\n")

                // Expand entity nodes into the facts they participate in. Vector search
                // returns bare nodes; the relationships live on separate assertion nodes
                // that may not rank on their own, so pull them in via the graph.
                if (handle.nodeType == NodeType.PERSON || handle.nodeType == NodeType.TOPIC) {
                    repository.getConnectedAssertions(handle.graphKey).forEach { assertion ->
                        if (seen.add(assertion.graphKey)) {
                            graphBuilder.getNodeSummary(assertion.graphKey)?.let {
                                builder.append("    • $it\n")
                            }
                        }
                    }
                }
            }

            ToolResult(true, builder.toString())
        } catch (e: Exception) {
            ToolResult(false, "Failed to search memory: ${e.message}")
        }
    }
}
