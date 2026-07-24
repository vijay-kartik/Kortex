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
            ToolParam("personName", "string", "Name of the person (the SUBJECT the fact is about)"),
            ToolParam(
                "predicate", "string",
                "The relation, stated SUBJECT→OBJECT. Prefer one of: WORKS_AT, STUDIED_AT, " +
                    "LIVES_IN, TRAVELING_TO, VISITED, FAMILY_OF, COLLEAGUE_OF, FRIEND_OF, " +
                    "MANAGER_OF (subject manages object), KNOWS, INTERESTED_IN, WORKING_ON, " +
                    "OWNS, PREFERS, BIRTHDAY_ON, ANNIVERSARY_ON. Use MANAGER_OF for a " +
                    "manager relationship. Free-form phrasings are canonicalized where possible.",
            ),
            ToolParam("objectName", "string", "The target of the relation (the OBJECT — e.g. organization, topic, or person)"),
        )
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val personName = args["personName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing personName")
        val predicateStr = args["predicate"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing predicate")
        val objectName = args["objectName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Missing objectName")

        val predicate = AssertionPredicate.canonicalize(predicateStr)

        return try {
            // Embed nodes and assertion using the real embedding provider
            val personEmbedding = embedder.embed(personName)
            val objectEmbedding = embedder.embed(objectName)
            val assertionEmbedding = embedder.embed("$personName $predicateStr $objectName")

            // 1. Resolve-or-create nodes (dedup by name). The object is a PERSON for
            //    person↔person predicates (e.g. MANAGER_OF), otherwise a TOPIC — so a
            //    manager fact attaches to the real person, not a stray topic node.
            val personRef = graphBuilder.getOrCreatePerson(name = personName, embedding = personEmbedding)
            val objectRef = if (predicate.objectIsPerson) {
                graphBuilder.getOrCreatePerson(name = objectName, embedding = objectEmbedding)
            } else {
                graphBuilder.getOrCreateTopic(label = objectName, embedding = objectEmbedding)
            }

            // 2. Create the fact, reusing an existing assertion if the same
            //    subject-predicate-object already exists (idempotent save).
            graphBuilder.assertFact(
                subject = personRef,
                predicate = predicate,
                obj = objectRef,
                embedding = assertionEmbedding,
            )
            
            ToolResult(true, "Successfully saved knowledge: $personName $predicateStr $objectName")
        } catch (e: Exception) {
            ToolResult(false, "Failed to save knowledge: ${e.message}")
        }
    }
}
