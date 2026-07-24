package dev.kortex.graph_storage

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphReference
import dev.kortex.graph_core.GraphSchema
import dev.kortex.graph_storage.business.AssertionEntity
import dev.kortex.graph_storage.business.PersonEntity
import dev.kortex.graph_storage.business.PersonEntity_
import dev.kortex.graph_storage.business.TopicEntity
import dev.kortex.graph_storage.business.TopicEntity_
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder

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

    fun getNodeSummary(graphKey: Long): String? {
        val reg = repository.getRegistryEntity(graphKey) ?: return null
        val type = dev.kortex.graph_core.NodeType.fromId(reg.nodeTypeId) ?: return null

        return when (type) {
            dev.kortex.graph_core.NodeType.PERSON -> {
                val p = personBox.get(reg.businessEntityId) ?: return null
                "PERSON: ${p.name}" + if (p.notes.isNotBlank()) " (Notes: ${p.notes})" else ""
            }
            dev.kortex.graph_core.NodeType.TOPIC -> {
                val t = topicBox.get(reg.businessEntityId) ?: return null
                "TOPIC: ${t.label}" + if (t.description.isNotBlank()) " (Description: ${t.description})" else ""
            }
            dev.kortex.graph_core.NodeType.ASSERTION -> {
                // A reified fact is only meaningful with its endpoints. Traverse the
                // SUBJECT/OBJECT edges so the summary reads "subject predicate object"
                // instead of the endpoint-less "ASSERTION: OTHER" that a bare predicate
                // gives — the latter is unanswerable for the model.
                val a = assertionBox.get(reg.businessEntityId) ?: return null
                val predicate = (a.predicate?.name ?: "OTHER").replace('_', ' ').lowercase()
                val subject = repository
                    .getNeighbors(graphKey, dev.kortex.graph_core.EdgeType.SUBJECT, dev.kortex.graph_core.Direction.OUTGOING)
                    .firstOrNull()?.let { nodeLabel(it.graphKey) }
                val obj = repository
                    .getNeighbors(graphKey, dev.kortex.graph_core.EdgeType.OBJECT, dev.kortex.graph_core.Direction.OUTGOING)
                    .firstOrNull()?.let { nodeLabel(it.graphKey) }
                when {
                    subject != null && obj != null -> "FACT: $subject $predicate $obj"
                    subject != null -> "FACT: $subject $predicate"
                    obj != null -> "FACT: $predicate $obj"
                    else -> "FACT: $predicate"
                }
            }
            else -> "${type.name} (ID: ${reg.graphId})"
        }
    }

    /**
     * Short human label for a node — just its name/title — for embedding inside
     * another node's summary (e.g. an ASSERTION rendering its endpoints). Does
     * not recurse into further assertions.
     */
    private fun nodeLabel(graphKey: Long): String? {
        val reg = repository.getRegistryEntity(graphKey) ?: return null
        val type = dev.kortex.graph_core.NodeType.fromId(reg.nodeTypeId) ?: return null
        return when (type) {
            dev.kortex.graph_core.NodeType.PERSON -> personBox.get(reg.businessEntityId)?.name
            dev.kortex.graph_core.NodeType.TOPIC -> topicBox.get(reg.businessEntityId)?.label
            dev.kortex.graph_core.NodeType.ASSERTION ->
                assertionBox.get(reg.businessEntityId)?.predicate?.name?.replace('_', ' ')?.lowercase()
            else -> type.name
        }
    }

    /**
     * Resolves an existing PERSON by name (case-insensitive) or creates one.
     * Prevents the duplicate/fragmented nodes that plain [addPerson] produces
     * when the same entity is asserted repeatedly. Embedding is only set on
     * creation; an existing node keeps the embedding it was created with.
     */
    fun getOrCreatePerson(name: String, notes: String = "", embedding: FloatArray? = null): GraphReference {
        val existing = personBox.query()
            .equal(PersonEntity_.name, name, StringOrder.CASE_INSENSITIVE)
            .build()
            .findFirst()
        if (existing != null) {
            return GraphReference(GraphId.from(existing.graphIdStr), dev.kortex.graph_core.NodeType.PERSON)
        }
        return addPerson(name = name, notes = notes, embedding = embedding)
    }

    /**
     * Resolves an existing TOPIC by label (case-insensitive) or creates one.
     * See [getOrCreatePerson] for the resolve-or-create rationale.
     */
    fun getOrCreateTopic(label: String, description: String = "", embedding: FloatArray? = null): GraphReference {
        val existing = topicBox.query()
            .equal(TopicEntity_.label, label, StringOrder.CASE_INSENSITIVE)
            .build()
            .findFirst()
        if (existing != null) {
            return GraphReference(GraphId.from(existing.graphIdStr), dev.kortex.graph_core.NodeType.TOPIC)
        }
        return addTopic(label = label, description = description, embedding = embedding)
    }

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

    /**
     * Creates the reified fact (subject)-[predicate]->(object), or returns the
     * existing assertion if one with the same predicate already links the same
     * subject and object. Makes fact writes idempotent: saving the same fact
     * twice reuses the assertion node instead of piling up duplicates.
     *
     * Validates the SUBJECT/OBJECT shape up front so a schema violation can't
     * leave an orphan assertion behind (unlike creating then connecting).
     */
    fun assertFact(
        subject: GraphReference,
        predicate: AssertionPredicate,
        obj: GraphReference,
        embedding: FloatArray? = null,
    ): GraphReference {
        val subjectKey = repository.getGraphKey(subject.graphId)
            ?: throw IllegalArgumentException("Subject node not found in registry: ${subject.graphId}")
        val objectKey = repository.getGraphKey(obj.graphId)
            ?: throw IllegalArgumentException("Object node not found in registry: ${obj.graphId}")

        GraphSchema.requireValid(dev.kortex.graph_core.NodeType.ASSERTION, EdgeType.SUBJECT, subject.nodeType)
        GraphSchema.requireValid(dev.kortex.graph_core.NodeType.ASSERTION, EdgeType.OBJECT, obj.nodeType)

        findAssertion(subjectKey, objectKey, predicate)?.let { return it }

        val assertion = addAssertion(predicate = predicate, embedding = embedding)
        connect(assertion, subject, EdgeType.SUBJECT)
        connect(assertion, obj, EdgeType.OBJECT)
        return assertion
    }

    /**
     * Finds an existing assertion with [predicate] whose SUBJECT is [subjectKey]
     * and OBJECT is [objectKey], or null. Walks the assertions on the subject
     * (cheap: SUBJECT edges are indexed) and checks predicate + object for each.
     */
    private fun findAssertion(
        subjectKey: Long,
        objectKey: Long,
        predicate: AssertionPredicate,
    ): GraphReference? {
        val candidates = repository.getNeighbors(subjectKey, EdgeType.SUBJECT, dev.kortex.graph_core.Direction.INCOMING)
        for (candidate in candidates) {
            if (candidate.nodeType != dev.kortex.graph_core.NodeType.ASSERTION) continue
            val reg = repository.getRegistryEntity(candidate.graphKey) ?: continue
            val a = assertionBox.get(reg.businessEntityId) ?: continue
            if (a.predicateId != predicate.id) continue
            val linksObject = repository
                .getNeighbors(candidate.graphKey, EdgeType.OBJECT, dev.kortex.graph_core.Direction.OUTGOING)
                .any { it.graphKey == objectKey }
            if (linksObject) return repository.getReference(candidate.graphKey)
        }
        return null
    }
}
