package dev.kortex.graph_storage.business

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphNode
import dev.kortex.graph_core.NodeType
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique

/**
 * Storage entity representing a derived fact/assertion in the knowledge graph.
 */
@Entity
class AssertionEntity(
    @Id var id: Long = 0,
    
    @Unique
    var graphIdStr: String = "",
    
    var predicateId: Int = 0,
    
    var confidence: Float = 1.0f,
    
    var validFrom: Long = 0,
    
    var validTo: Long = 0
) : GraphNode {
    
    override val graphId: GraphId get() = GraphId.from(graphIdStr)
    
    override val nodeType: NodeType get() = NodeType.ASSERTION
    
    val predicate: AssertionPredicate? get() = AssertionPredicate.fromId(predicateId)
}
