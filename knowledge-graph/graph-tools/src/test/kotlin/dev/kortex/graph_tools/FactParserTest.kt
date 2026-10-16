package dev.kortex.graph_tools

import dev.kortex.graph_core.AssertionPredicate
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val parser = FactParser(FixedPredicates, FactDates(zone))

    private fun fact(vararg fields: Pair<String, String>): JsonObject =
        buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }

    private fun day(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `BIRTHDAY_ON moves a date in objectName to validFrom`() {
        val parsed = parser.parseFact(
            fact("personName" to "Kartik", "predicate" to "BIRTHDAY_ON", "objectName" to "1996-03-12"),
            0,
        ) as ParsedFact.Valid

        assertEquals(AssertionPredicate.BIRTHDAY_ON, parsed.predicate)
        assertNull(parsed.objectName)
        assertEquals(day(1996, 3, 12), parsed.validFrom)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `BIRTHDAY_ON without any date is rejected`() {
        val parsed = parser.parseFact(fact("personName" to "Kartik", "predicate" to "BIRTHDAY_ON"), 2)

        assertEquals(
            ParsedFact.Invalid("facts[2]: BIRTHDAY_ON needs a date — put it in validFrom (ISO-8601, e.g. 1996-03-12)"),
            parsed,
        )
    }

    @Test
    fun `BIRTHDAY_ON keeps validFrom and warns about a non-date objectName`() {
        val parsed = parser.parseFact(
            fact(
                "personName" to "Kartik",
                "predicate" to "BIRTHDAY_ON",
                "objectName" to "cake",
                "validFrom" to "1996-03-12",
            ),
            0,
        ) as ParsedFact.Valid

        assertNull(parsed.objectName)
        assertEquals(day(1996, 3, 12), parsed.validFrom)
        assertEquals(listOf("ignored objectName=\"cake\"; BIRTHDAY_ON takes only a date"), parsed.warnings)
    }

    @Test
    fun `a non-date predicate whose objectName is a date is rejected`() {
        val parsed = parser.parseFact(
            fact("personName" to "Kartik", "predicate" to "TRAVELING_TO", "objectName" to "16-Jul-2025"),
            1,
        )

        assertTrue(parsed is ParsedFact.Invalid)
        assertTrue((parsed as ParsedFact.Invalid).reason.startsWith("facts[1]: objectName=\"16-Jul-2025\" is a date."))
    }

    @Test
    fun `a numeric booking reference stays the object`() {
        val parsed = parser.parseFact(
            fact("personName" to "Crowne Plaza", "predicate" to "HAS_PHONE", "objectName" to "01146462000"),
            0,
        ) as ParsedFact.Valid

        assertEquals("01146462000", parsed.objectName)
        assertEquals(AssertionPredicate.OTHER, parsed.predicate)
        assertEquals("HAS_PHONE", parsed.rawPredicate)
    }

    @Test
    fun `an unparseable date field is kept as a warning`() {
        val parsed = parser.parseFact(
            fact(
                "personName" to "Kartik",
                "predicate" to "TRAVELING_TO",
                "objectName" to "Delhi",
                "validFrom" to "next Tuesday",
            ),
            0,
        ) as ParsedFact.Valid

        assertEquals(0L, parsed.validFrom)
        assertEquals(
            listOf("could not parse validFrom=\"next Tuesday\"; use ISO-8601 e.g. 2025-07-16T15:00"),
            parsed.warnings,
        )
    }

    @Test
    fun `missing fields are reported by position`() {
        assertEquals(
            ParsedFact.Invalid("facts[0]: missing personName"),
            parser.parseFact(fact("predicate" to "KNOWS", "objectName" to "Asha"), 0),
        )
        assertEquals(
            ParsedFact.Invalid("facts[3]: missing objectName"),
            parser.parseFact(fact("personName" to "Kartik", "predicate" to "KNOWS"), 3),
        )
    }

    @Test
    fun `collectFacts accepts a batch and a bare top-level triple`() {
        val batch = buildJsonObject {
            put("facts", buildJsonArray { add(fact("personName" to "Kartik")) })
        }
        assertEquals(1, parser.collectFacts(batch)?.size)

        val bare = fact("personName" to "Kartik", "predicate" to "LIVES_IN", "objectName" to "Delhi")
        assertEquals(listOf(bare), parser.collectFacts(bare))

        assertNull(parser.collectFacts(fact("predicate" to "LIVES_IN")))
    }
}
