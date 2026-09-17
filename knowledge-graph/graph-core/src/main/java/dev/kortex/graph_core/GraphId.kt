package dev.kortex.graph_core

import java.util.UUID

/**
 * Stable, external identity of a graph node.
 *
 * - Survives export/import, device migration, and future sync.
 * - Never used for traversal; the graph engine resolves it to an internal
 *   graphKey (Long) via the registry. See [NodeHandle].
 * - Immutable for the lifetime of the node. Never reused after deletion.
 */
@JvmInline
value class GraphId(val value: String) {

    init {
        require(value.isNotBlank()) { "GraphId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** Creates a new random UUID-v4 backed identity. */
        fun random(): GraphId = GraphId(UUID.randomUUID().toString())

        /** Wraps an existing identity string (e.g. read back from storage). */
        fun from(value: String): GraphId = GraphId(value)
    }
}
