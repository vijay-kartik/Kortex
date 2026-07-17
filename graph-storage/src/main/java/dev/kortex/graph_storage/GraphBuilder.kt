package dev.kortex.graph_storage

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphReference
import dev.kortex.graph_core.GraphSchema
import dev.kortex.graph_storage.business.AssertionEntity
import dev.kortex.graph_storage.business.PersonEntity
import dev.kortex.graph_storage.business.TopicEntity
import io.objectbox.BoxStore

/**
 * High-level orchestration for building the knowledge graph.
 * Ensures GraphSchema validation and coordinates the business entities,
 * registry entries, embeddings, and edges.
 */
class GraphBuilder(
    private val repository: GraphRepository,
    private val boxStore: BoxStore
) {
    private val personBox = boxStore.boxFor(PersonEntity::class.java)
    private val topicBox = boxStore.boxFor(TopicEntity::class.java)
    private val assertionBox = boxStore.boxFor(AssertionEntity::class.java)

    fun addPerson(name: String, notes: String = "", embedding: FloatArray? = null): GraphReference {
        val graphId = GraphId.random()
        val person = PersonEntity(
            graphIdStr = graphId.value,
            name = name,
            notes = notes
        )
        val businessId = personBox.put(person)
        
        val graphKey = repository.registerNode(
            graphId = graphId,
            nodeType = person.nodeType,
            entityKind = EntityKind.PERSON,
            businessEntityId = businessId
        )
        
        if (embedding != null) {
            repository.setEmbedding(graphKey, embedding)
        }
        
        return GraphReference(graphId, person.nodeType)
    }

    fun addTopic(label: String, description: String = "", embedding: FloatArray? = null): GraphReference {
        val graphId = GraphId.random()
        val topic = TopicEntity(
            graphIdStr = graphId.value,
            label = label,
            description = description
        )
        val businessId = topicBox.put(topic)
        
        val graphKey = repository.registerNode(
            graphId = graphId,
            nodeType = topic.nodeType,
            entityKind = EntityKind.TOPIC,
            businessEntityId = businessId
        )
        
        if (embedding != null) {
            repository.setEmbedding(graphKey, embedding)
        }
        
        return GraphReference(graphId, topic.nodeType)
    }

    fun addAssertion(
        predicate: AssertionPredicate,
        confidence: Float = 1.0f,
        validFrom: Long = 0,
        validTo: Long = 0,
        embedding: FloatArray? = null
    ): GraphReference {
        val graphId = GraphId.random()
        val assertion = AssertionEntity(
            graphIdStr = graphId.value,
            predicateId = predicate.id,
            confidence = confidence,
            validFrom = validFrom,
            validTo = validTo
        )
        val businessId = assertionBox.put(assertion)
        
        val graphKey = repository.registerNode(
            graphId = graphId,
            nodeType = assertion.nodeType,
            entityKind = EntityKind.ASSERTION,
            businessEntityId = businessId
        )
        
        if (embedding != null) {
            repository.setEmbedding(graphKey, embedding)
        }
        
        return GraphReference(graphId, assertion.nodeType)
    }

    /**
     * Connects two nodes after validating the relationship against GraphSchema.
     */
    fun connect(source: GraphReference, target: GraphReference, type: EdgeType) {
        GraphSchema.requireValid(source.nodeType, type, target.nodeType)
        
        val sourceKey = repository.getGraphKey(source.graphId)
            ?: throw IllegalArgumentException("Source node not found in registry: ${source.graphId}")
            
        val targetKey = repository.getGraphKey(target.graphId)
            ?: throw IllegalArgumentException("Target node not found in registry: ${target.graphId}")
            
        repository.connect(sourceKey, targetKey, type)
    }
}
