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
                // The extractor's own phrase when the relation didn't canonicalize:
                // "Kartik booked through MakeMyTrip" rather than the meaningless
                // "Kartik other MakeMyTrip".
                val predicate = a.displayPredicate
                val subject = repository
                    .getNeighbors(graphKey, dev.kortex.graph_core.EdgeType.SUBJECT, dev.kortex.graph_core.Direction.OUTGOING)
                    .firstOrNull()?.let { nodeLabel(it.graphKey) }
                val obj = repository
                    .getNeighbors(graphKey, dev.kortex.graph_core.EdgeType.OBJECT, dev.kortex.graph_core.Direction.OUTGOING)
                    .firstOrNull()?.let { nodeLabel(it.graphKey) }
                val core = when {
                    subject != null && obj != null -> "FACT: $subject $predicate $obj"
                    subject != null -> "FACT: $subject $predicate"
                    obj != null -> "FACT: $predicate $obj"
                    else -> "FACT: $predicate"
                }
                core + validitySuffix(a)
            }
            else -> "${type.name} (ID: ${reg.graphId})"
        }
    }

    /**
     * Renders an assertion's validity interval as " (from X)", " (until Y)" or
     * " (X - Y)", empty when both bounds are unset. Without this the interval is
     * stored but invisible to the model reading the summary, which is the whole
     * point of capturing it — "traveling to Delhi" and "traveling to Delhi
     * 16 Jul-17 Jul 2025" are very different answers to "when is my trip?".
     */
    private fun validitySuffix(assertion: AssertionEntity): String {
        val from = assertion.validFrom.takeIf { it != 0L }?.let { formatInstant(it) }
        val to = assertion.validTo.takeIf { it != 0L }?.let { formatInstant(it) }
        return when {
            from != null && to != null -> " ($from - $to)"
            from != null -> " (from $from)"
            to != null -> " (until $to)"
            else -> ""
        }
    }

    /** Epoch millis → local "16 Jul 2025 15:00", the form the model reads back. */
    private fun formatInstant(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(INSTANT_FORMAT)

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
                assertionBox.get(reg.businessEntityId)?.displayPredicate
            else -> type.name
        }
    }

    /**
     * Resolves an existing PERSON by name (case-insensitive) or creates one.
     * Prevents the duplicate/fragmented nodes that plain [addPerson] produces
     * when the same entity is asserted repeatedly. Embedding is only set on
     * creation; an existing node keeps the embedding it was created with.
     */
    /**
     * The node type an entity with this name already has in the graph, or null if
     * it is new. Lets extraction reuse an entity's established type instead of
     * guessing from the predicate: "Crowne Plaza Okhla, Delhi" first appears as
     * the OBJECT of TRAVELING_TO (a TOPIC), and must resolve to that same TOPIC
     * when it later shows up as the SUBJECT of a fact about its phone number —
     * otherwise the hotel exists twice, once as a topic and once as a person, and
     * neither copy can see the other's facts.
     *
     * PERSON is checked first: person↔person predicates deliberately create people,
     * and a name that is both would be a genuine collision worth keeping stable.
     */
    fun findEntityType(name: String): dev.kortex.graph_core.NodeType? {
        val person = personBox.query()
            .equal(PersonEntity_.name, name, StringOrder.CASE_INSENSITIVE)
            .build()
            .findFirst()
        if (person != null) return dev.kortex.graph_core.NodeType.PERSON

        val topic = topicBox.query()
            .equal(TopicEntity_.label, name, StringOrder.CASE_INSENSITIVE)
            .build()
            .findFirst()
        return if (topic != null) dev.kortex.graph_core.NodeType.TOPIC else null
    }

    /**
     * Resolve-or-create for an entity whose type the caller has already decided.
     * Anything other than PERSON is stored as a TOPIC — the only two entity
     * tables that exist today.
     */
    fun getOrCreateEntity(
        name: String,
        type: dev.kortex.graph_core.NodeType,
        embedding: FloatArray? = null,
    ): GraphReference =
        if (type == dev.kortex.graph_core.NodeType.PERSON) {
            getOrCreatePerson(name = name, embedding = embedding)
        } else {
            getOrCreateTopic(label = name, embedding = embedding)
        }

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
        embedding: FloatArray? = null,
        rawPredicate: String = "",
    ): GraphReference {
        val graphId = GraphId.random()
        val assertion = AssertionEntity(
            graphIdStr = graphId.value,
            predicateId = predicate.id,
            // Blank stays null so "never recorded" and "recognized predicate" look
            // the same on disk, matching every row written before this field existed.
            rawPredicate = rawPredicate.takeIf { it.isNotBlank() },
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
     *
     * [validFrom]/[validTo] are epoch millis bounding when the fact holds (0 =
     * unbounded), and [confidence] is how sure the extractor is. Re-saving an
     * existing fact *enriches* it rather than no-opping: a later call that
     * carries a validity interval or a higher confidence fills in what the
     * first save left empty. Existing bounds are never overwritten with 0 —
     * the caller that knows a date wins over the one that doesn't.
     *
     * [obj] is null for date-valued facts ("Kartik BIRTHDAY_ON …"), where the
     * value lives in the validity interval and an object node would just be a
     * junk entity labelled with a date.
     *
     * [rawPredicate] is the extractor's own phrase, kept when [predicate] is
     * OTHER. It participates in identity: two long-tail relations between the
     * same pair ("booked through" and "can cancel until" for one hotel) are
     * different facts, and deduping them on the bare OTHER would merge them.
     */
    fun assertFact(
        subject: GraphReference,
        predicate: AssertionPredicate,
        obj: GraphReference?,
        embedding: FloatArray? = null,
        confidence: Float = 1.0f,
        validFrom: Long = 0,
        validTo: Long = 0,
        rawPredicate: String = "",
    ): GraphReference {
        val subjectKey = repository.getGraphKey(subject.graphId)
            ?: throw IllegalArgumentException("Subject node not found in registry: ${subject.graphId}")
        val objectKey = obj?.let {
            repository.getGraphKey(it.graphId)
                ?: throw IllegalArgumentException("Object node not found in registry: ${it.graphId}")
        }

        GraphSchema.requireValid(dev.kortex.graph_core.NodeType.ASSERTION, EdgeType.SUBJECT, subject.nodeType)
        if (obj != null) {
            GraphSchema.requireValid(dev.kortex.graph_core.NodeType.ASSERTION, EdgeType.OBJECT, obj.nodeType)
        }

        findAssertion(subjectKey, objectKey, predicate, rawPredicate)?.let { existingKey ->
            enrichAssertion(existingKey, confidence, validFrom, validTo)
            return repository.getReference(existingKey)
                ?: throw IllegalStateException("Assertion $existingKey missing from registry")
        }

        val assertion = addAssertion(
            predicate = predicate,
            confidence = confidence,
            validFrom = validFrom,
            validTo = validTo,
            embedding = embedding,
            rawPredicate = rawPredicate,
        )
        connect(assertion, subject, EdgeType.SUBJECT)
        if (obj != null) connect(assertion, obj, EdgeType.OBJECT)
        return assertion
    }

    /**
     * Merges newly supplied validity/confidence into an already-stored assertion.
     * Only ever adds information: zero bounds mean "unknown" and leave the stored
     * value alone, and confidence moves up, never down.
     */
    private fun enrichAssertion(graphKey: Long, confidence: Float, validFrom: Long, validTo: Long) {
        val reg = repository.getRegistryEntity(graphKey) ?: return
        val assertion = assertionBox.get(reg.businessEntityId) ?: return

        var changed = false
        if (validFrom != 0L && assertion.validFrom == 0L) {
            assertion.validFrom = validFrom
            changed = true
        }
        if (validTo != 0L && assertion.validTo == 0L) {
            assertion.validTo = validTo
            changed = true
        }
        if (confidence > assertion.confidence) {
            assertion.confidence = confidence
            changed = true
        }
        if (changed) assertionBox.put(assertion)
    }

    /**
     * Finds the graph key of an existing assertion identical to the one being
     * asserted — same SUBJECT, OBJECT, predicate and (for OTHER) raw phrase — or
     * null. Walks the assertions on the subject (cheap: SUBJECT edges are indexed)
     * and checks the rest for each.
     *
     * A null [objectKey] matches only object-less assertions, so a date-valued
     * fact never collides with a relation between the same subject and some entity.
     */
    private fun findAssertion(
        subjectKey: Long,
        objectKey: Long?,
        predicate: AssertionPredicate,
        rawPredicate: String,
    ): Long? {
        val candidates = repository.getNeighbors(subjectKey, EdgeType.SUBJECT, dev.kortex.graph_core.Direction.INCOMING)
        for (candidate in candidates) {
            if (candidate.nodeType != dev.kortex.graph_core.NodeType.ASSERTION) continue
            val reg = repository.getRegistryEntity(candidate.graphKey) ?: continue
            val a = assertionBox.get(reg.businessEntityId) ?: continue
            if (a.predicateId != predicate.id) continue
            // OTHER is a bucket, not a relation: only the raw phrase distinguishes
            // one long-tail fact from another.
            if (predicate == AssertionPredicate.OTHER &&
                !a.rawPredicateOrEmpty.equals(rawPredicate, ignoreCase = true)
            ) continue
            val objects = repository
                .getNeighbors(candidate.graphKey, EdgeType.OBJECT, dev.kortex.graph_core.Direction.OUTGOING)
            val matchesObject =
                if (objectKey == null) objects.isEmpty() else objects.any { it.graphKey == objectKey }
            if (matchesObject) return candidate.graphKey
        }
        return null
    }

    private companion object {
        private val INSTANT_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
    }
}
