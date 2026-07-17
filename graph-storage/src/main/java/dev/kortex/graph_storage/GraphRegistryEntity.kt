package dev.kortex.graph_storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * The global node index — one row per graph node. (Locked design.)
 *
 * Exists because ObjectBox @Id values are only unique per box: PersonEntity
 * id=1 and CallEntity id=1 coexist, so raw entity IDs are ambiguous as edge
 * endpoints. The registry mints [graphKey], the ONE graph-wide unique key
 * that [EdgeEntity] and [EmbeddingEntity] reference.
 *
 * Identity translation, nothing else:
 *
 *     GraphId (UUID, external) ↔ graphKey (Long, internal) → business row
 *
 * ## Contract
 * - Insert-once, delete-once. Rows are never updated except if a business
 *   row is migrated to a different kind/table (rare, deliberate).
 * - NO timestamps, labels, metadata, or JSON — ever. Business data lives on
 *   business entities; provenance lives on ASSERTION nodes.
 * - Deleting a node = delete its edges (both directions) + its embedding +
 *   this row + the business row, atomically, in one transaction.
 *   (Implemented by GraphRepository in Step 3; documented here because the
 *   registry is the anchor of that cascade.)
 */
@Entity
class GraphRegistryEntity(

    /**
     * THE graph key. ObjectBox-assigned on insert; graph-wide unique because
     * every node — regardless of kind — gets its row from this one box.
     * Referenced by EdgeEntity.sourceKey/targetKey and
     * EmbeddingEntity.graphKey. Never exposed outside the graph layer.
     */
    @Id
    var graphKey: Long = 0,

    /**
     * Stable external identity (UUID string from GraphId). Unique constraint
     * doubles as the lookup index for GraphId → graphKey resolution.
     */
    @Unique
    var graphId: String = "",

    /** NodeType.id — the node's ontology type. Indexed for type scans. */
    @Index
    var nodeTypeId: Int = 0,

    /** EntityKind.id — which box owns the business row. Indexed for dispatch. */
    @Index
    var entityKindId: Int = 0,

    /**
     * ObjectBox @Id of the business row inside the box identified by
     * [entityKindId]. Only meaningful together with entityKindId. Indexed
     * for reverse resolution (business row → registry).
     */
    @Index
    var businessEntityId: Long = 0,
)
