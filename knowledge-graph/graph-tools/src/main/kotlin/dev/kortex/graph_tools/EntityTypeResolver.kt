package dev.kortex.graph_tools

import dev.kortex.graph_core.NodeType

/**
 * Decides, for every entity named anywhere in a batch, whether it is a PERSON or
 * a TOPIC — once, before any node is created.
 *
 * Doing this per-fact made the answer depend on which fact happened to be
 * written first: "Crowne Plaza Okhla, Delhi" as the object of TRAVELING_TO is
 * a place, but as the subject of a fact about its phone number the old code
 * assumed a person, so the hotel was created twice and neither copy could see
 * the other's facts. Evidence is ranked, strongest first:
 *
 * 1. the type the entity already has in the graph ([existingType]) — established facts win;
 * 2. being the object of a person↔person predicate (FRIEND_OF, MANAGER_OF …);
 * 3. being the object of any other predicate, which implies a topic;
 * 4. being a subject, which only weakly suggests a person.
 *
 * So the hotel's appearance as a TRAVELING_TO object (3) beats its appearance
 * as a subject (4), and it resolves to one TOPIC for the whole batch.
 */
internal class EntityTypeResolver(
    private val existingType: (String) -> NodeType?,
) {

    /** Every entity's type, keyed by its lowercased name. */
    fun resolve(facts: List<ParsedFact.Valid>): Map<String, NodeType> {
        val inferred = mutableMapOf<String, Pair<Int, NodeType>>()

        fun consider(name: String, strength: Int, type: NodeType) {
            val key = name.lowercase()
            val current = inferred[key]
            if (current == null || strength > current.first) inferred[key] = strength to type
        }

        facts.forEach { fact ->
            consider(fact.personName, STRENGTH_SUBJECT_DEFAULT, NodeType.PERSON)
            val obj = fact.objectName ?: return@forEach
            if (fact.predicate.objectIsPerson) {
                consider(obj, STRENGTH_PREDICATE_PERSON, NodeType.PERSON)
            } else {
                consider(obj, STRENGTH_PREDICATE_TOPIC, NodeType.TOPIC)
            }
        }

        return inferred.mapValues { (key, guess) -> existingType(key) ?: guess.second }
    }

    private companion object {
        // Entity-type evidence strengths, weakest to strongest.
        private const val STRENGTH_SUBJECT_DEFAULT = 1
        private const val STRENGTH_PREDICATE_TOPIC = 2
        private const val STRENGTH_PREDICATE_PERSON = 3
    }
}
