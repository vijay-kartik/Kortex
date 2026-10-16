package dev.kortex.graph_storage

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.GraphReference
import dev.kortex.graph_core.NodeType

/**
 * The graph writes fact extraction needs, and nothing more. [GraphBuilder] is the
 * real implementation; the port exists so extraction's rules can be tested
 * without a BoxStore. See [GraphBuilder] for each operation's semantics.
 */
interface FactWriter {

    /** The type an entity with this name already has in the graph, or null if it is new. */
    fun findEntityType(name: String): NodeType?

    /** Resolve-or-create for an entity whose type the caller has already decided. */
    fun getOrCreateEntity(
        name: String,
        type: NodeType,
        embedding: FloatArray? = null,
    ): GraphReference

    /** Creates the reified fact, or enriches the identical one already stored. */
    fun assertFact(
        subject: GraphReference,
        predicate: AssertionPredicate,
        obj: GraphReference?,
        embedding: FloatArray? = null,
        confidence: Float = 1.0f,
        validFrom: Long = 0,
        validTo: Long = 0,
        rawPredicate: String = "",
    ): GraphReference
}
