package dev.kortex.graph_storage.business

import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphNode
import dev.kortex.graph_core.NodeType
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique

/**
 * Storage entity representing a topic in the knowledge graph.
 */
@Entity
class TopicEntity(
    @Id var id: Long = 0,
    
    @Unique
    var graphIdStr: String = "",
    
    var label: String = "",
    
    var description: String = ""
) : GraphNode {
    
    override val graphId: GraphId get() = GraphId.from(graphIdStr)
    
    override val nodeType: NodeType get() = NodeType.TOPIC
}
