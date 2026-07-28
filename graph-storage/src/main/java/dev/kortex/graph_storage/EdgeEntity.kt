package dev.kortex.graph_storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * One directed edge: (sourceKey) -[relationshipId]-> (targetKey).
 * This box IS the graph; everything else is data. (Locked design.)
 *
 * ## Contract
 * - Structural only. NO timestamps, weight, confidence, metadata,
 *   properties — ever. Event nodes own time; ASSERTION nodes own
 *   provenance/confidence. Wanting edge.timestamp is the signal to reify a
 *   node instead.
 * - Immutable: edges are created and deleted, never updated.
 * - Endpoints are registry graphKeys — never business @Ids, never UUIDs.
 *   16 bytes of keys per edge instead of 72 bytes of UUID strings.
 * - Legality of (source.nodeType, relationship, target.nodeType) is
 *   enforced by GraphSchema.requireValid BEFORE creation (GraphBuilder /
 *   repository responsibility — storage cannot see node types).
 * - Duplicate prevention (same source+target+relationship) is a repository
 *   responsibility: ObjectBox has no compound unique constraint, and adding
 *   a packed dedup field would violate the field freeze. connect() must
 *   query-before-insert (cheap: sourceKey is indexed).
 *
 * ## Query patterns the three indexes serve
 * - outgoing(x):            sourceKey == x
 * - incoming(x):            targetKey == x
 * - outgoing(x, type):      sourceKey == x && relationshipId == t
 * - all edges of one type:  relationshipId == t
 */
@Entity
class EdgeEntity(

    /** ObjectBox row id. Storage-internal; carries no graph meaning. */
    @Id
    var id: Long = 0,

    /** graphKey of the source node (GraphRegistryEntity.graphKey). */
    @Index
    var sourceKey: Long = 0,

    /** graphKey of the target node. */
    @Index
    var targetKey: Long = 0,

    /** EdgeType.id — stable persisted id, never the enum ordinal or name. */
    @Index
    var relationshipId: Int = 0,
)
