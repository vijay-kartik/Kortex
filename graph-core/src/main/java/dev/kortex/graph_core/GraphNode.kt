package dev.kortex.graph_core

/**
 * Contract implemented by every graph-enabled business entity
 * (PersonEntity, CallEntity, TopicEntity, ...).
 *
 * Interface, not abstract class: there is no shared implementation, business
 * entities may need a different supertype later, and interfaces compose.
 *
 * Deliberately minimal — the Business Entity Contract (locked):
 * - graphId is the ONLY graph field a business entity carries.
 * - No graphKey: the internal Long key never leaves the graph layer;
 *   the registry resolves it.
 * - No timestamps, labels, embeddings, or metadata here. Those belong to
 *   the entity's own domain fields or to dedicated graph storage tables.
 */
interface GraphNode {

    /** Stable external identity. Assigned once at creation, never changed. */
    val graphId: GraphId

    /** This entity's position in the ontology. Constant per class. */
    val nodeType: NodeType
}
