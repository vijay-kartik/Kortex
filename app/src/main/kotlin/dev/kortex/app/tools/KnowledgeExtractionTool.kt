package dev.kortex.app.tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphStorageConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Allows the agent to save facts to the knowledge graph.
 */
class KnowledgeExtractionTool(
    private val graphBuilder: GraphBuilder,
    private val embedder: EmbeddingProvider
) : Tool {

    override val name = "save_knowledge"
    override val description = "Save a derived fact or assertion about a person or topic into the memory graph."
    override val parameters = ToolSchema(
        listOf(
            ToolParam("personName", "string", "Name of the person"),
            ToolParam("predicate", "string", "The relation (WORKS_AT, LIVES_IN, INTERESTED_IN, etc)"),
            ToolParam("objectName", "string", "The target of the relation (e.g., organization or topic)"),
        )
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val personName = args["personName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing personName")
        val predicateStr = args["predicate"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing predicate")
        val objectName = args["objectName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing objectName")

        val predicate = try {
            AssertionPredicate.valueOf(predicateStr.uppercase())
        } catch (e: IllegalArgumentException) {
            AssertionPredicate.OTHER
        }

        return try {
            // Embed nodes and assertion using the real embedding provider
            val personEmbedding = embedder.embed(personName)
            val topicEmbedding = embedder.embed(objectName)
            val assertionEmbedding = embedder.embed("$personName $predicateStr $objectName")

            // 1. Create nodes
            val personRef = graphBuilder.addPerson(name = personName, notes = "", embedding = personEmbedding)
            val topicRef = graphBuilder.addTopic(label = objectName, description = "", embedding = topicEmbedding)
            
            // 2. Create assertion
            val assertionRef = graphBuilder.addAssertion(
                predicate = predicate,
                embedding = assertionEmbedding
            )
            
            // 3. Connect them via GraphBuilder (which validates via GraphSchema)
            graphBuilder.connect(assertionRef, personRef, dev.kortex.graph_core.EdgeType.SUBJECT)
            graphBuilder.connect(assertionRef, topicRef, dev.kortex.graph_core.EdgeType.OBJECT)
            
            ToolResult(true, "Successfully saved knowledge: $personName $predicateStr $objectName")
        } catch (e: Exception) {
            ToolResult(false, "Failed to save knowledge: ${e.message}")
        }
    }
}
