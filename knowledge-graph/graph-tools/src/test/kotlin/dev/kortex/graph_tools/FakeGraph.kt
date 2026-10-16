package dev.kortex.graph_tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphReference
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.FactWriter
import dev.kortex.graph_storage.PredicateResolver
import dev.kortex.graph_storage.PredicateVocabulary

/** The fixed vocabulary only: no learned promotions. */
internal object FixedPredicates : PredicateResolver {
    override fun resolveAndRecord(raw: String) = PredicateVocabulary.Resolution(
        predicate = AssertionPredicate.canonicalize(raw),
        normalized = AssertionPredicate.normalize(raw),
        fromLearnedAlias = false,
    )
}

internal object ZeroEmbedder : EmbeddingProvider {
    override suspend fun embed(text: String) = FloatArray(1)
}

/**
 * In-memory [FactWriter]. [existing] seeds entity types already in the graph;
 * asserting a fact whose subject is in [failingSubjects] throws.
 */
internal class FakeFactWriter(
    private val existing: Map<String, NodeType> = emptyMap(),
    private val failingSubjects: Set<String> = emptySet(),
) : FactWriter {
    val entities = mutableMapOf<String, NodeType>()
    val facts = mutableListOf<Fact>()

    data class Fact(
        val subject: String,
        val predicate: AssertionPredicate,
        val obj: String?,
        val validFrom: Long,
        val validTo: Long,
    )

    private val names = mutableMapOf<GraphId, String>()

    override fun findEntityType(name: String): NodeType? =
        existing.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun getOrCreateEntity(name: String, type: NodeType, embedding: FloatArray?): GraphReference {
        entities[name] = type
        val id = GraphId.random()
        names[id] = name
        return GraphReference(id, type)
    }

    override fun assertFact(
        subject: GraphReference,
        predicate: AssertionPredicate,
        obj: GraphReference?,
        embedding: FloatArray?,
        confidence: Float,
        validFrom: Long,
        validTo: Long,
        rawPredicate: String,
    ): GraphReference {
        val subjectName = names.getValue(subject.graphId)
        if (subjectName in failingSubjects) throw IllegalStateException("disk full")
        facts.add(Fact(subjectName, predicate, obj?.let { names.getValue(it.graphId) }, validFrom, validTo))
        return GraphReference(GraphId.random(), NodeType.ASSERTION)
    }
}
