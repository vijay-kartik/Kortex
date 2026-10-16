package dev.kortex.graph_tools

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.NodeType
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeExtractionToolTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun tool(writer: FakeFactWriter) = KnowledgeExtractionTool(writer, ZeroEmbedder, FixedPredicates, zone)

    private fun fact(vararg fields: Pair<String, String>): JsonObject =
        buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }

    private fun batch(vararg facts: JsonObject) = buildJsonObject {
        put("facts", buildJsonArray { facts.forEach { add(it) } })
    }

    @Test
    fun `one failing write does not stop the batch and both outcomes are reported`() = runTest {
        val writer = FakeFactWriter(failingSubjects = setOf("Asha"))

        val result = tool(writer).execute(
            batch(
                fact("personName" to "Asha", "predicate" to "WORKS_AT", "objectName" to "Acme"),
                fact("personName" to "Kartik", "predicate" to "LIVES_IN", "objectName" to "Delhi"),
                fact("personName" to "Kartik", "predicate" to "VISITED", "objectName" to "2025-07-16"),
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf("Kartik"), writer.facts.map { it.subject })
        assertTrue(result.content.startsWith("Saved 1 fact(s):\n- Kartik lives in Delhi\nFailed 2 fact(s):\n"))
        assertTrue(result.content.contains("- facts[2]: objectName=\"2025-07-16\" is a date."))
        assertTrue(result.content.contains("- Asha works at Acme: disk full"))
    }

    @Test
    fun `a bare top-level triple is accepted`() = runTest {
        val writer = FakeFactWriter()

        val result = tool(writer).execute(
            fact("personName" to "Kartik", "predicate" to "LIVES_IN", "objectName" to "Delhi"),
        )

        assertTrue(result.ok)
        assertEquals("Saved 1 fact(s):\n- Kartik lives in Delhi", result.content)
        assertEquals(
            listOf(FakeFactWriter.Fact("Kartik", AssertionPredicate.LIVES_IN, "Delhi", 0L, 0L)),
            writer.facts,
        )
    }

    @Test
    fun `the hotel is one topic and the stay dates are kept on the fact`() = runTest {
        val writer = FakeFactWriter()
        val hotel = "Crowne Plaza Okhla, Delhi"

        val result = tool(writer).execute(
            batch(
                fact("personName" to hotel, "predicate" to "HAS_PHONE", "objectName" to "01146462000"),
                fact(
                    "personName" to "Kartik",
                    "predicate" to "TRAVELING_TO",
                    "objectName" to hotel,
                    "validFrom" to "16-Jul-2025 15:00",
                    "validTo" to "2025-07-17T12:00",
                ),
            ),
        )

        assertEquals(NodeType.TOPIC, writer.entities[hotel])
        val stay = writer.facts.single { it.predicate == AssertionPredicate.TRAVELING_TO }
        assertEquals(LocalDateTime.of(2025, 7, 16, 15, 0).atZone(zone).toInstant().toEpochMilli(), stay.validFrom)
        assertTrue(
            result.content.contains(
                "- Kartik traveling to $hotel (valid 2025-07-16T15:00 to 2025-07-17T12:00)",
            ),
        )
        assertTrue(
            result.content.contains(
                "- $hotel has phone 01146462000 [new relation \"HAS_PHONE\" — recorded for review]",
            ),
        )
    }

    @Test
    fun `missing and empty fact lists are refused`() = runTest {
        val writer = FakeFactWriter()

        val missing = tool(writer).execute(buildJsonObject {})
        val empty = tool(writer).execute(batch())

        assertFalse(missing.ok)
        assertTrue(missing.content.startsWith("Missing facts."))
        assertEquals("The facts array was empty — nothing to save.", empty.content)
        assertTrue(writer.facts.isEmpty())
    }
}
