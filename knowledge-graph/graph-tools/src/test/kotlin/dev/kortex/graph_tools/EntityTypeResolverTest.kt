package dev.kortex.graph_tools

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.NodeType
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityTypeResolverTest {

    private fun fact(subject: String, predicate: AssertionPredicate, obj: String?) = ParsedFact.Valid(
        personName = subject,
        predicate = predicate,
        rawPredicate = predicate.name,
        objectName = obj,
        validFrom = 0L,
        validTo = 0L,
        confidence = 1f,
        warnings = emptyList(),
    )

    private val hotelFacts = listOf(
        fact("Crowne Plaza Okhla, Delhi", AssertionPredicate.OTHER, "01146462000"),
        fact("Kartik", AssertionPredicate.TRAVELING_TO, "Crowne Plaza Okhla, Delhi"),
    )

    @Test
    fun `a TRAVELING_TO object outranks being a subject`() {
        val types = EntityTypeResolver { null }.resolve(hotelFacts)

        assertEquals(NodeType.TOPIC, types["crowne plaza okhla, delhi"])
        assertEquals(NodeType.PERSON, types["kartik"])
        assertEquals(NodeType.TOPIC, types["01146462000"])
    }

    @Test
    fun `the type already in the graph overrides the batch evidence`() {
        val existing = mapOf("crowne plaza okhla, delhi" to NodeType.PERSON)
        val types = EntityTypeResolver { existing[it] }.resolve(hotelFacts)

        assertEquals(NodeType.PERSON, types["crowne plaza okhla, delhi"])
    }

    @Test
    fun `a person-to-person object outranks a topic object`() {
        val types = EntityTypeResolver { null }.resolve(
            listOf(
                fact("Kartik", AssertionPredicate.INTERESTED_IN, "Asha"),
                fact("Kartik", AssertionPredicate.FRIEND_OF, "Asha"),
            ),
        )

        assertEquals(NodeType.PERSON, types["asha"])
    }

    @Test
    fun `date-valued facts contribute only their subject`() {
        val types = EntityTypeResolver { null }.resolve(
            listOf(fact("Kartik", AssertionPredicate.BIRTHDAY_ON, null)),
        )

        assertEquals(mapOf("kartik" to NodeType.PERSON), types)
    }
}
