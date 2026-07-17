package dev.kortex.graph_core

/**
 * The executable part of the ontology: which (sourceType, edgeType,
 * targetType) triples are legal.
 *
 * Without this, "MESSAGE → WORKS_AT → TOPIC" is representable and the bug
 * only surfaces at query time. With it:
 * - GraphBuilder calls [requireValid] before creating any edge.
 * - GraphRepository can assert [isValid] in debug builds.
 *
 * Schema changes are additive, like the enums: widening a rule is safe;
 * narrowing one requires checking existing data first.
 */
object GraphSchema {

    private val EVENTS: Set<NodeType> =
        NodeType.entries.filterTo(mutableSetOf()) { it.category == NodeCategory.EVENT }

    private val ASSETS: Set<NodeType> =
        NodeType.entries.filterTo(mutableSetOf()) { it.category == NodeCategory.ASSET }

    /** Node types an ASSERTION may be about. */
    private val ASSERTION_SUBJECTS: Set<NodeType> = setOf(
        NodeType.PERSON,
        NodeType.ORGANIZATION,
        NodeType.LOCATION,
        NodeType.PROJECT,
        NodeType.TOPIC,
    )

    /** Node types an ASSERTION's value may be. */
    private val ASSERTION_OBJECTS: Set<NodeType> = setOf(
        NodeType.PERSON,
        NodeType.ORGANIZATION,
        NodeType.LOCATION,
        NodeType.PROJECT,
        NodeType.TOPIC,
        NodeType.TASK,
    )

    private val rules: Map<EdgeType, Set<Pair<NodeType, NodeType>>> = buildMap {
        // ----- Participation: PERSON → event -----
        rule(EdgeType.PARTICIPATED_IN, setOf(NodeType.PERSON), EVENTS)
        rule(
            EdgeType.SENT,
            setOf(NodeType.PERSON),
            setOf(NodeType.MESSAGE, NodeType.EMAIL, NodeType.CALL),
        )
        rule(
            EdgeType.RECEIVED,
            setOf(NodeType.PERSON),
            setOf(NodeType.MESSAGE, NodeType.EMAIL, NodeType.CALL),
        )

        // ----- Knowledge -----
        rule(EdgeType.MENTIONED, EVENTS, setOf(NodeType.TOPIC))
        rule(EdgeType.ABOUT, EVENTS + NodeType.TOPIC, setOf(NodeType.PROJECT))
        rule(EdgeType.RELATED_TO, setOf(NodeType.TOPIC), setOf(NodeType.TOPIC))
        rule(
            EdgeType.CREATED,
            EVENTS,
            setOf(NodeType.TASK, NodeType.DECISION, NodeType.COMMITMENT),
        )
        rule(
            EdgeType.AGGREGATES,
            setOf(NodeType.MEMORY),
            EVENTS + setOf(
                NodeType.TOPIC,
                NodeType.TASK,
                NodeType.DECISION,
                NodeType.COMMITMENT,
            ),
        )

        // ----- Assertion plumbing -----
        rule(EdgeType.SUBJECT, setOf(NodeType.ASSERTION), ASSERTION_SUBJECTS)
        rule(EdgeType.OBJECT, setOf(NodeType.ASSERTION), ASSERTION_OBJECTS)
        rule(EdgeType.DERIVED_FROM, setOf(NodeType.ASSERTION), EVENTS)

        // ----- Identity (system-of-record only) -----
        rule(EdgeType.HAS_PHONE, setOf(NodeType.PERSON), setOf(NodeType.PHONE_NUMBER))
        rule(EdgeType.HAS_EMAIL, setOf(NodeType.PERSON), setOf(NodeType.EMAIL_ADDRESS))
        rule(EdgeType.WORKS_AT, setOf(NodeType.PERSON), setOf(NodeType.ORGANIZATION))
        rule(EdgeType.LIVES_AT, setOf(NodeType.PERSON), setOf(NodeType.LOCATION))

        // ----- Structure / time -----
        // NEXT chains events of the same kind within one stream.
        put(
            EdgeType.NEXT,
            EVENTS.mapTo(mutableSetOf()) { it to it },
        )
        put(
            EdgeType.REPLIES_TO,
            setOf(
                NodeType.MESSAGE to NodeType.MESSAGE,
                NodeType.EMAIL to NodeType.EMAIL,
            ),
        )
        rule(EdgeType.PART_OF, setOf(NodeType.MESSAGE), setOf(NodeType.CONVERSATION))
        rule(
            EdgeType.ATTACHED_TO,
            ASSETS,
            setOf(NodeType.MESSAGE, NodeType.EMAIL, NodeType.MEETING),
        )
    }

    init {
        // Every edge type must have at least one rule: an edge type nobody
        // may legally create is a definition bug.
        val uncovered = EdgeType.entries.filter { rules[it].isNullOrEmpty() }
        check(uncovered.isEmpty()) { "EdgeTypes without schema rules: $uncovered" }
    }

    /** True if (source)-[edge]->(target) is a legal triple. */
    fun isValid(source: NodeType, edge: EdgeType, target: NodeType): Boolean =
        rules[edge]?.contains(source to target) == true

    /** Throws [IllegalArgumentException] for illegal triples. Call from GraphBuilder. */
    fun requireValid(source: NodeType, edge: EdgeType, target: NodeType) {
        require(isValid(source, edge, target)) {
            "Illegal edge: ($source)-[$edge]->($target). " +
                "Allowed for $edge: ${rules[edge].orEmpty().sortedBy { it.first.name }}"
        }
    }

    /** All legal source types for an edge type (for extraction-time filtering). */
    fun allowedSources(edge: EdgeType): Set<NodeType> =
        rules[edge].orEmpty().mapTo(mutableSetOf()) { it.first }

    /** All legal target types for an edge type. */
    fun allowedTargets(edge: EdgeType): Set<NodeType> =
        rules[edge].orEmpty().mapTo(mutableSetOf()) { it.second }

    /** The full set of legal triples (for docs, tests, and tooling). */
    fun allowedTriples(): Set<Triple<NodeType, EdgeType, NodeType>> =
        rules.flatMapTo(mutableSetOf()) { (edge, pairs) ->
            pairs.map { (source, target) -> Triple(source, edge, target) }
        }

    /** Cross-product helper: every source may connect to every target. */
    private fun MutableMap<EdgeType, Set<Pair<NodeType, NodeType>>>.rule(
        edge: EdgeType,
        sources: Set<NodeType>,
        targets: Set<NodeType>,
    ) {
        val pairs = mutableSetOf<Pair<NodeType, NodeType>>()
        for (source in sources) {
            for (target in targets) {
                pairs += source to target
            }
        }
        put(edge, pairs)
    }
}
