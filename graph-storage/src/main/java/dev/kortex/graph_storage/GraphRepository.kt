package dev.kortex.graph_storage

import dev.kortex.graph_core.Direction
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.GraphId
import dev.kortex.graph_core.GraphReference
import dev.kortex.graph_core.NodeHandle
import dev.kortex.graph_core.NodeType
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * Coordinates between the pure domain graph-core and the graph-storage entities.
 * Handles the registry resolution, edge traversal, and vector search.
 */
class GraphRepository(private val boxStore: BoxStore) {
    private val registryBox: Box<GraphRegistryEntity> = boxStore.boxFor(GraphRegistryEntity::class.java)
    private val edgeBox: Box<EdgeEntity> = boxStore.boxFor(EdgeEntity::class.java)
    private val embeddingBox: Box<EmbeddingEntity> = boxStore.boxFor(EmbeddingEntity::class.java)

    /**
     * Registers a new node in the global registry and returns its internal graphKey.
     */
    fun registerNode(
        graphId: GraphId,
        nodeType: NodeType,
        entityKind: EntityKind,
        businessEntityId: Long
    ): Long {
        val entity = GraphRegistryEntity(
            graphId = graphId.value,
            nodeTypeId = nodeType.id,
            entityKindId = entityKind.id,
            businessEntityId = businessEntityId
        )
        return registryBox.put(entity)
    }

    /** Resolves a GraphId to its internal graphKey. */
    fun getGraphKey(graphId: GraphId): Long? {
        val entity = registryBox.query()
            .equal(GraphRegistryEntity_.graphId, graphId.value, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()

            .findFirst()

        return entity?.graphKey
    }

    /** Resolves a graphKey to its GraphReference. */
    fun getReference(graphKey: Long): GraphReference? {
        val entity = registryBox.get(graphKey) ?: return null
        val nodeType = NodeType.fromId(entity.nodeTypeId) ?: return null
        return GraphReference(GraphId(entity.graphId), nodeType)
    }

    /**
     * Connects two nodes with a directed edge.
     * Prevents duplicate edges of the same type between the same nodes.
     */
    fun connect(sourceKey: Long, targetKey: Long, type: EdgeType) {
        val existing = edgeBox.query()
            .equal(EdgeEntity_.sourceKey, sourceKey)
            .and()
            .equal(EdgeEntity_.targetKey, targetKey)
            .and()
            .equal(EdgeEntity_.relationshipId, type.id.toLong())
            .build()
            .findFirst()
        
        if (existing == null) {
            edgeBox.put(
                EdgeEntity(
                    sourceKey = sourceKey,
                    targetKey = targetKey,
                    relationshipId = type.id
                )
            )
        }
    }

    /**
     * Retrieves neighbors of a node.
     */
    fun getNeighbors(nodeKey: Long, type: EdgeType, direction: Direction): List<NodeHandle> {
        val results = mutableListOf<NodeHandle>()
        
        if (direction == Direction.OUTGOING || direction == Direction.BOTH) {
            val outgoingEdges = edgeBox.query()
                .equal(EdgeEntity_.sourceKey, nodeKey)
                .and()
                .equal(EdgeEntity_.relationshipId, type.id.toLong())
                .build()
                .find()
            
            for (edge in outgoingEdges) {
                val targetReg = registryBox.get(edge.targetKey)
                if (targetReg != null) {
                    val nodeType = NodeType.fromId(targetReg.nodeTypeId)
                    if (nodeType != null) {
                        results.add(NodeHandle(edge.targetKey, nodeType))
                    }
                }
            }
        }
        
        if (direction == Direction.INCOMING || direction == Direction.BOTH) {
            val incomingEdges = edgeBox.query()
                .equal(EdgeEntity_.targetKey, nodeKey)
                .and()
                .equal(EdgeEntity_.relationshipId, type.id.toLong())
                .build()
                .find()
            
            for (edge in incomingEdges) {
                val sourceReg = registryBox.get(edge.sourceKey)
                if (sourceReg != null) {
                    val nodeType = NodeType.fromId(sourceReg.nodeTypeId)
                    if (nodeType != null) {
                        results.add(NodeHandle(edge.sourceKey, nodeType))
                    }
                }
            }
        }
        
        return results
    }

    /** Sets or updates the embedding for a node. */
    fun setEmbedding(graphKey: Long, vector: FloatArray) {
        require(vector.size.toLong() == GraphStorageConfig.EMBEDDING_DIMENSIONS) {
            "Vector dimension must be ${GraphStorageConfig.EMBEDDING_DIMENSIONS}, got ${vector.size}"
        }
        
        val existing = embeddingBox.query()
            .equal(EmbeddingEntity_.graphKey, graphKey)
            .build()
            .findFirst()

        if (existing != null) {
            existing.embedding = vector
            embeddingBox.put(existing)
        } else {
            val entity = EmbeddingEntity(
                graphKey = graphKey,
                dimensions = vector.size
            )
            entity.embedding = vector
            embeddingBox.put(entity)
        }
    }

    /** Performs a vector search for similar nodes. */
    fun searchSimilar(queryVector: FloatArray, maxResults: Int): List<NodeHandle> {
        require(queryVector.size.toLong() == GraphStorageConfig.EMBEDDING_DIMENSIONS) {
            "Vector dimension must be ${GraphStorageConfig.EMBEDDING_DIMENSIONS}, got ${queryVector.size}"
        }
        
        val similarEmbeddings = embeddingBox.query()
            .nearestNeighbors(EmbeddingEntity_.embedding, queryVector, maxResults)
            .build()
            .find()
        
        return similarEmbeddings.mapNotNull { emb ->
            val reg = registryBox.get(emb.graphKey)
            if (reg != null) {
                val type = NodeType.fromId(reg.nodeTypeId)
                if (type != null) {
                    NodeHandle(emb.graphKey, type)
                } else null
            } else null
        }
    }

    /**
     * Atomically deletes a node from the registry, drops its edges and embedding.
     * Note: The caller (GraphBuilder) must also delete the associated business entity.
     */
    fun deleteNode(graphKey: Long) {
        boxStore.runInTx {
            // Delete embedding
            embeddingBox.query()
                .equal(EmbeddingEntity_.graphKey, graphKey)
                .build()
                .remove()

            // Delete outgoing edges
            edgeBox.query()
                .equal(EdgeEntity_.sourceKey, graphKey)
                .build()
                .remove()

            // Delete incoming edges
            edgeBox.query()
                .equal(EdgeEntity_.targetKey, graphKey)
                .build()
                .remove()

            // Delete from registry
            registryBox.remove(graphKey)
        }
    }
}
