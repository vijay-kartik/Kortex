package dev.kortex.app.tools

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.PredicateVocabulary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Allows the agent to save facts to the knowledge graph.
 *
 * Takes a *batch* of facts per call. A single message routinely carries a dozen
 * assertions (a hotel booking names the property, the stay dates, the booking
 * channel, the cancellation deadline); one-triple-per-call made the model save
 * the first fact it noticed and stop, silently discarding the rest.
 *
 * Batching is also what makes entity typing reliable — see [resolveTypes].
 */
class KnowledgeExtractionTool(
    private val graphBuilder: GraphBuilder,
    private val embedder: EmbeddingProvider,
    private val vocabulary: PredicateVocabulary,
) : Tool {

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
        val raw = collectFacts(args)
            ?: return ToolResult(
                false,
                "Missing facts. Pass every fact as one call: " +
                    "{\"facts\":[{\"personName\":..., \"predicate\":..., \"objectName\":..., " +
                    "\"validFrom\":..., \"validTo\":...}, ...]}",
            )
        if (raw.isEmpty()) return ToolResult(false, "The facts array was empty — nothing to save.")

        val parsed = raw.mapIndexed { index, fact -> parseFact(fact, index) }
        val valid = parsed.filterIsInstance<ParsedFact.Valid>()
        val failed = parsed.filterIsInstance<ParsedFact.Invalid>().mapTo(mutableListOf()) { it.reason }

        // Decide every entity's type once, across the whole batch, before writing
        // anything — see resolveTypes.
        val types = resolveTypes(valid)

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

    /**
     * Decides, for every entity named anywhere in the batch, whether it is a
     * PERSON or a TOPIC — once, before any node is created.
     *
     * Doing this per-fact made the answer depend on which fact happened to be
     * written first: "Crowne Plaza Okhla, Delhi" as the object of TRAVELING_TO is
     * a place, but as the subject of a fact about its phone number the old code
     * assumed a person, so the hotel was created twice and neither copy could see
     * the other's facts. Evidence is ranked, strongest first:
     *
     * 1. the type the entity already has in the graph — established facts win;
     * 2. being the object of a person↔person predicate (FRIEND_OF, MANAGER_OF …);
     * 3. being the object of any other predicate, which implies a topic;
     * 4. being a subject, which only weakly suggests a person.
     *
     * So the hotel's appearance as a TRAVELING_TO object (3) beats its appearance
     * as a subject (4), and it resolves to one TOPIC for the whole batch.
     */
    private fun resolveTypes(facts: List<ParsedFact.Valid>): Map<String, NodeType> {
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

        return inferred.mapValues { (key, guess) -> graphBuilder.findEntityType(key) ?: guess.second }
    }

    /** Writes one fact. One bad fact must not sink the rest of the batch. */
    private suspend fun save(fact: ParsedFact.Valid, types: Map<String, NodeType>): SaveOutcome = try {
        val subjectType = types[fact.personName.lowercase()] ?: NodeType.PERSON
        val subjectRef = graphBuilder.getOrCreateEntity(
            name = fact.personName,
            type = subjectType,
            embedding = embedder.embed(fact.personName),
        )

        // Null for date-valued predicates: the date lives in the validity interval
        // and an object node would be an entity labelled with a timestamp.
        val objectRef = fact.objectName?.let { objectName ->
            graphBuilder.getOrCreateEntity(
                name = objectName,
                type = types[objectName.lowercase()] ?: NodeType.TOPIC,
                embedding = embedder.embed(objectName),
            )
        }

        val assertionText = listOfNotNull(fact.personName, fact.displayPredicate, fact.objectName)
            .joinToString(" ")

        graphBuilder.assertFact(
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
        fact.validFrom != 0L && fact.validTo != 0L -> "(valid ${iso(fact.validFrom)} to ${iso(fact.validTo)})"
        fact.validFrom != 0L -> "(valid from ${iso(fact.validFrom)})"
        fact.validTo != 0L -> "(valid until ${iso(fact.validTo)})"
        else -> null
    }

    /**
     * Pulls the fact list out of the arguments. Accepts the documented
     * `{"facts":[...]}` batch and — leniently — a single bare triple at the top
     * level, which smaller models still emit despite the schema. Returns null
     * when neither shape is present.
     */
    private fun collectFacts(args: JsonObject): List<JsonObject>? {
        (args["facts"] as? JsonArray)?.let { array ->
            return array.filterIsInstance<JsonObject>()
        }
        if (args["personName"] != null || args["objectName"] != null) return listOf(args)
        return null
    }

    private fun parseFact(raw: JsonObject, index: Int): ParsedFact {
        val position = "facts[$index]"
        val personName = raw.text("personName")
            ?: return ParsedFact.Invalid("$position: missing personName")
        val predicateRaw = raw.text("predicate")
            ?: return ParsedFact.Invalid("$position: missing predicate")

        // Resolving here (rather than at save time) records the sighting once per
        // fact and lets resolveTypes see the real predicate.
        val resolution = vocabulary.resolveAndRecord(predicateRaw)

        val warnings = mutableListOf<String>()
        var validFrom = timestamp(raw.text("validFrom"), "validFrom", warnings)
        val validTo = timestamp(raw.text("validTo"), "validTo", warnings)
        val confidence = raw["confidence"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: 1.0f
        val objectName = raw.text("objectName")

        // Which slot a date belongs in is the predicate's call, not a blanket rule:
        // for BIRTHDAY_ON the date IS the value, for everything else it can only be
        // a bound on when the fact holds.
        val resolvedObject: String?
        if (resolution.predicate.objectIsDate) {
            val fromObject = objectName?.let { parseDateLike(it) } ?: 0L
            if (validFrom == 0L && fromObject != 0L) validFrom = fromObject
            if (validFrom == 0L) {
                return ParsedFact.Invalid(
                    "$position: ${resolution.predicate.name} needs a date — put it in validFrom " +
                        "(ISO-8601, e.g. 1996-03-12)"
                )
            }
            if (fromObject == 0L && objectName != null) {
                warnings.add("ignored objectName=\"$objectName\"; ${resolution.predicate.name} takes only a date")
            }
            resolvedObject = null
        } else {
            if (objectName == null) {
                return ParsedFact.Invalid("$position: missing objectName")
            }
            parseDateLike(objectName).takeIf { it != 0L }?.let {
                return ParsedFact.Invalid(
                    "$position: objectName=\"$objectName\" is a date. A date is never an entity — " +
                        "put it in validFrom/validTo and set objectName to what the fact is about."
                )
            }
            resolvedObject = objectName
        }

        return ParsedFact.Valid(
            personName = personName,
            predicate = resolution.predicate,
            rawPredicate = resolution.normalized,
            objectName = resolvedObject,
            validFrom = validFrom,
            validTo = validTo,
            confidence = confidence,
            warnings = warnings,
        )
    }

    /**
     * Parses a value the model put in a date *field*. An unparseable value is
     * surfaced as a warning rather than swallowed — a silently dropped check-in
     * date is exactly the loss this tool exists to stop.
     */
    private fun timestamp(value: String?, field: String, warnings: MutableList<String>): Long {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        val parsed = parseExplicitTimestamp(text)
        if (parsed == 0L) warnings.add("could not parse $field=\"$text\"; use ISO-8601 e.g. 2025-07-16T15:00")
        return parsed
    }

    private sealed interface SaveOutcome {
        data class Saved(val line: String) : SaveOutcome
        data class Failed(val reason: String) : SaveOutcome
    }

    private sealed interface ParsedFact {
        data class Invalid(val reason: String) : ParsedFact
        data class Valid(
            val personName: String,
            val predicate: AssertionPredicate,
            /** Normalized phrase, kept for OTHER so the relation isn't lost. */
            val rawPredicate: String,
            /** Null for date-valued predicates, which have no object entity. */
            val objectName: String?,
            val validFrom: Long,
            val validTo: Long,
            val confidence: Float,
            val warnings: List<String>,
        ) : ParsedFact {
            /** How the fact reads back: the model's phrase for OTHER, the enum name otherwise. */
            val displayPredicate: String
                get() = if (predicate == AssertionPredicate.OTHER && rawPredicate.isNotBlank()) {
                    rawPredicate.replace('_', ' ').lowercase()
                } else {
                    predicate.name.replace('_', ' ').lowercase()
                }
        }
    }

    private companion object {
        // Entity-type evidence strengths, weakest to strongest. See resolveTypes.
        private const val STRENGTH_SUBJECT_DEFAULT = 1
        private const val STRENGTH_PREDICATE_TOPIC = 2
        private const val STRENGTH_PREDICATE_PERSON = 3

        private const val PREDICATE_DESCRIPTION =
            "The relation, stated SUBJECT→OBJECT. Known predicates: WORKS_AT, STUDIED_AT, " +
                "LIVES_IN, TRAVELING_TO, VISITED, FAMILY_OF, COLLEAGUE_OF, FRIEND_OF, " +
                "MANAGER_OF (subject manages object), KNOWS, INTERESTED_IN, WORKING_ON, " +
                "OWNS, PREFERS, BIRTHDAY_ON, ANNIVERSARY_ON. Use one ONLY if it genuinely " +
                "fits the relation. Otherwise write your own short phrase (e.g. " +
                "BOOKED_THROUGH, HAS_REFERENCE, HAS_PHONE, CANCELLABLE_UNTIL) — it is stored " +
                "verbatim and counted, so a forced wrong predicate is strictly worse than a " +
                "new accurate one."

        private val ZONE: ZoneId = ZoneId.systemDefault()

        /**
         * Plausible range for a bare number to be epoch millis: 1973 to 2100.
         * Without a floor, a phone number like "01146462000" parses as a Long and
         * would be mistaken for a timestamp.
         */
        private const val EPOCH_MILLIS_MIN = 100_000_000_000L
        private const val EPOCH_MILLIS_MAX = 4_102_444_800_000L

        private val DATE_TIME_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy, HH:mm", Locale.ENGLISH),
        )

        private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        )

        private val ISO_OUT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH)

        fun iso(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZONE).format(ISO_OUT)

        /** For values in date fields: epoch millis or any recognized date format. */
        fun parseExplicitTimestamp(text: String): Long {
            text.toLongOrNull()
                ?.takeIf { it in EPOCH_MILLIS_MIN..EPOCH_MILLIS_MAX }
                ?.let { return it }
            return parseDateLike(text)
        }

        /**
         * For inspecting values that should NOT be dates. Deliberately excludes
         * bare numbers: booking references and phone numbers are not timestamps,
         * however numeric they look.
         */
        fun parseDateLike(text: String): Long {
            runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
            runCatching { OffsetDateTime.parse(text) }.getOrNull()
                ?.let { return it.toInstant().toEpochMilli() }

            for (format in DATE_TIME_FORMATS) {
                runCatching { LocalDateTime.parse(text, format) }.getOrNull()
                    ?.let { return it.atZone(ZONE).toInstant().toEpochMilli() }
            }
            for (format in DATE_FORMATS) {
                runCatching { LocalDate.parse(text, format) }.getOrNull()
                    ?.let { return it.atStartOfDay(ZONE).toInstant().toEpochMilli() }
            }
            return 0L
        }

        fun JsonObject.text(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
