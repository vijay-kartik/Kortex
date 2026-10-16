package dev.kortex.graph_tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.FactWriter
import dev.kortex.graph_storage.PredicateResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Allows the agent to save facts to the knowledge graph.
 *
 * Takes a *batch* of facts per call. A single message routinely carries a dozen
 * assertions (a hotel booking names the property, the stay dates, the booking
 * channel, the cancellation deadline); one-triple-per-call made the model save
 * the first fact it noticed and stop, silently discarding the rest.
 *
 * Batching is also what makes entity typing reliable — see [EntityTypeResolver].
 * Parsing lives in [FactParser] and [FactDates]; this class only writes and reports.
 */
class KnowledgeExtractionTool(
    private val writer: FactWriter,
    private val embedder: EmbeddingProvider,
    vocabulary: PredicateResolver,
    zone: ZoneId = ZoneId.systemDefault(),
) : Tool {

    private val dates = FactDates(zone)
    private val parser = FactParser(vocabulary, dates)
    private val typeResolver = EntityTypeResolver(writer::findEntityType)

    override val name = "save_knowledge"

    override val description =
        "Save derived facts about people, places, organizations and topics into the memory graph. " +
            "EXTRACT EXHAUSTIVELY: pass EVERY distinct fact present in the input as a " +
            "separate entry in the `facts` array in ONE call — do not stop after the " +
            "first fact, and do not call this tool repeatedly for one input. A booking, " +
            "an itinerary or a profile message typically yields 4-10 facts (destination, " +
            "the dates it is valid for, the booking reference, the provider, contact " +
            "details, deadlines). Anything you leave out is lost permanently. " +
            "DATES: if a date says WHEN a fact holds — a stay, a trip, a job, a deadline — " +
            "put it in validFrom/validTo and NEVER in objectName. Only a date that IS the " +
            "fact itself (a birthday, an anniversary) belongs with those predicates. " +
            "PREDICATES: use a listed predicate only if it genuinely fits; if none does, " +
            "write a short descriptive phrase of your own (BOOKED_THROUGH, HAS_REFERENCE, " +
            "CANCELLABLE_UNTIL) — it is preserved verbatim. Never force an unrelated " +
            "predicate: saying KNOWS for a company you booked with records something false."

    override val promptHint =
        "Batch every fact from the input into a single call's `facts` array; facts you omit are never recovered."

    // `params` stays flat so ToolGovernor's required-check keeps working; the LLM-facing
    // schema is the `raw` override below, which the flat list cannot express (arrays of
    // objects). Nothing is marked required here on purpose — execute() validates the
    // payload itself so the model gets an instructive error instead of a bare denial.
    override val parameters = ToolSchema(
        params = listOf(
            ToolParam("facts", "array", "The list of facts to save", required = false),
        ),
        raw = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("facts", buildJsonObject {
                    put("type", "array")
                    put("description", "Every fact found in the input. One entry per fact.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("personName", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "The SUBJECT the fact is about. Usually a person, but it may be " +
                                        "any entity — a hotel's phone number is a fact about the hotel.",
                                )
                            })
                            put("predicate", buildJsonObject {
                                put("type", "string")
                                put("description", PREDICATE_DESCRIPTION)
                            })
                            put("objectName", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "The target of the relation (the OBJECT — organization, place, " +
                                        "topic, person, or a concrete value such as a booking reference " +
                                        "or phone number). Never put a date here; dates go in " +
                                        "validFrom/validTo. Omit for BIRTHDAY_ON/ANNIVERSARY_ON, " +
                                        "where the date alone is the fact.",
                                )
                            })
                            put("validFrom", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional. When the fact starts holding, ISO-8601 " +
                                        "(\"2025-07-16T15:00\" or \"2025-07-16\"). For a hotel stay " +
                                        "this is the check-in; for a trip, the departure; for a " +
                                        "birthday, the date itself.",
                                )
                            })
                            put("validTo", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional. When the fact stops holding, ISO-8601. For a hotel " +
                                        "stay this is the check-out; for a cancellation policy, the cutoff.",
                                )
                            })
                            put("confidence", buildJsonObject {
                                put("type", "number")
                                put(
                                    "description",
                                    "Optional 0.0-1.0, default 1.0. Lower it for facts you inferred " +
                                        "rather than read directly.",
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("personName")
                            add("predicate")
                        })
                    })
                })
            })
            put("required", buildJsonArray { add("facts") })
        },
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = parser.collectFacts(args)
            ?: return ToolResult(
                false,
                "Missing facts. Pass every fact as one call: " +
                    "{\"facts\":[{\"personName\":..., \"predicate\":..., \"objectName\":..., " +
                    "\"validFrom\":..., \"validTo\":...}, ...]}",
            )
        if (raw.isEmpty()) return ToolResult(false, "The facts array was empty — nothing to save.")

        val parsed = raw.mapIndexed { index, fact -> parser.parseFact(fact, index) }
        val valid = parsed.filterIsInstance<ParsedFact.Valid>()
        val failed = parsed.filterIsInstance<ParsedFact.Invalid>().mapTo(mutableListOf()) { it.reason }

        // Decide every entity's type once, across the whole batch, before writing
        // anything — see EntityTypeResolver.
        val types = typeResolver.resolve(valid)

        val saved = mutableListOf<String>()
        valid.forEach { fact ->
            when (val outcome = save(fact, types)) {
                is SaveOutcome.Saved -> saved.add(outcome.line)
                is SaveOutcome.Failed -> failed.add(outcome.reason)
            }
        }

        val report = buildString {
            if (saved.isNotEmpty()) {
                append("Saved ${saved.size} fact(s):\n")
                saved.forEach { append("- $it\n") }
            }
            if (failed.isNotEmpty()) {
                append("Failed ${failed.size} fact(s):\n")
                failed.forEach { append("- $it\n") }
            }
        }.trimEnd()

        return ToolResult(saved.isNotEmpty(), report)
    }

    /** Writes one fact. One bad fact must not sink the rest of the batch. */
    private suspend fun save(fact: ParsedFact.Valid, types: Map<String, NodeType>): SaveOutcome = try {
        val subjectType = types[fact.personName.lowercase()] ?: NodeType.PERSON
        val subjectRef = writer.getOrCreateEntity(
            name = fact.personName,
            type = subjectType,
            embedding = embedder.embed(fact.personName),
        )

        // Null for date-valued predicates: the date lives in the validity interval
        // and an object node would be an entity labelled with a timestamp.
        val objectRef = fact.objectName?.let { objectName ->
            writer.getOrCreateEntity(
                name = objectName,
                type = types[objectName.lowercase()] ?: NodeType.TOPIC,
                embedding = embedder.embed(objectName),
            )
        }

        val assertionText = listOfNotNull(fact.personName, fact.displayPredicate, fact.objectName)
            .joinToString(" ")

        writer.assertFact(
            subject = subjectRef,
            predicate = fact.predicate,
            obj = objectRef,
            embedding = embedder.embed(assertionText),
            confidence = fact.confidence,
            validFrom = fact.validFrom,
            validTo = fact.validTo,
            rawPredicate = fact.rawPredicate,
        )

        SaveOutcome.Saved(
            buildString {
                append(assertionText)
                if (fact.predicate == AssertionPredicate.OTHER) {
                    append(" [new relation \"${fact.rawPredicate}\" — recorded for review]")
                }
                validityNote(fact)?.let { append(" $it") }
                fact.warnings.forEach { append(" [$it]") }
            }
        )
    } catch (e: Exception) {
        SaveOutcome.Failed("${fact.personName} ${fact.displayPredicate} ${fact.objectName.orEmpty()}: ${e.message}")
    }

    private fun validityNote(fact: ParsedFact.Valid): String? = when {
        fact.validFrom != 0L && fact.validTo != 0L ->
            "(valid ${dates.iso(fact.validFrom)} to ${dates.iso(fact.validTo)})"
        fact.validFrom != 0L -> "(valid from ${dates.iso(fact.validFrom)})"
        fact.validTo != 0L -> "(valid until ${dates.iso(fact.validTo)})"
        else -> null
    }

    private sealed interface SaveOutcome {
        data class Saved(val line: String) : SaveOutcome
        data class Failed(val reason: String) : SaveOutcome
    }

    private companion object {
        private const val PREDICATE_DESCRIPTION =
            "The relation, stated SUBJECT→OBJECT. Known predicates: WORKS_AT, STUDIED_AT, " +
                "LIVES_IN, TRAVELING_TO, VISITED, FAMILY_OF, COLLEAGUE_OF, FRIEND_OF, " +
                "MANAGER_OF (subject manages object), KNOWS, INTERESTED_IN, WORKING_ON, " +
                "OWNS, PREFERS, BIRTHDAY_ON, ANNIVERSARY_ON. Use one ONLY if it genuinely " +
                "fits the relation. Otherwise write your own short phrase (e.g. " +
                "BOOKED_THROUGH, HAS_REFERENCE, HAS_PHONE, CANCELLABLE_UNTIL) — it is stored " +
                "verbatim and counted, so a forced wrong predicate is strictly worse than a " +
                "new accurate one."
    }
}
