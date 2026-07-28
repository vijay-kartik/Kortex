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

    /**
     * The relation phrase as the extractor actually stated it, normalized
     * ("BOOKED_THROUGH"). Only meaningful when [predicateId] is [OTHER] — for a
     * recognized predicate the enum already carries the meaning.
     *
     * Without this, every un-canonicalized relation collapsed to a bare OTHER
     * and the phrase was gone: "Kartik booked through MakeMyTrip" and "Kartik
     * can cancel until <date>" both read back as "Kartik other MakeMyTrip", and
     * — because assertions dedup on (subject, predicate, object) — the second
     * silently merged into the first.
     *
     * Nullable because it has to be: assertions written before this property
     * existed have nothing stored for it, and ObjectBox's generated cursor passes
     * every constructor parameter positionally — so a non-null declaration throws
     * `NullPointerException: parameter specified as non-null is null` the first
     * time an old row is read. Read it through [rawPredicateOrEmpty].
     */
    var rawPredicate: String? = null,

    var confidence: Float = 1.0f,

    var validFrom: Long = 0,

    var validTo: Long = 0
) : GraphNode {
    
    override val graphId: GraphId get() = GraphId.from(graphIdStr)
    
    override val nodeType: NodeType get() = NodeType.ASSERTION
    
    val predicate: AssertionPredicate? get() = AssertionPredicate.fromId(predicateId)

    /**
     * How this fact should read: the extractor's own phrase when the predicate
     * didn't canonicalize, the enum name otherwise. Lowercased and de-underscored
     * for prose ("booked through", "works at").
     */
    val displayPredicate: String
        get() = rawPredicate?.takeIf { it.isNotBlank() && predicate == AssertionPredicate.OTHER }
            ?.replace('_', ' ')?.lowercase()
            ?: (predicate?.name ?: AssertionPredicate.OTHER.name).replace('_', ' ').lowercase()

    /** The raw phrase as a non-null value; "" for legacy rows and recognized predicates. */
    val rawPredicateOrEmpty: String get() = rawPredicate.orEmpty()
}
