package dev.kortex.graph_storage.business

import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphNode
import dev.kortex.graph_core.NodeType
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * Storage entity representing a person in the knowledge graph.
 */
@Entity
class PersonEntity(
    @Id var id: Long = 0,
    
    @Unique
    var graphIdStr: String = "",

    /** Indexed for resolve-or-create lookups by name (entity dedup). */
    @Index
    var name: String = "",

    var notes: String = ""
) : GraphNode {
    
    override val graphId: GraphId get() = GraphId.from(graphIdStr)
    
    override val nodeType: NodeType get() = NodeType.PERSON
}
