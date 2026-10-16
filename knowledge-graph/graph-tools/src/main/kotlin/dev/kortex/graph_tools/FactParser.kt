package dev.kortex.graph_tools

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_storage.PredicateResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the `save_knowledge` arguments into validated facts. Decides which slot
 * each date belongs in, and rejects facts the graph must not store, before
 * anything is written.
 */
internal class FactParser(
    private val vocabulary: PredicateResolver,
    private val dates: FactDates,
) {

    /**
     * Pulls the fact list out of the arguments. Accepts the documented
     * `{"facts":[...]}` batch and — leniently — a single bare triple at the top
     * level, which smaller models still emit despite the schema. Returns null
     * when neither shape is present.
     */
    fun collectFacts(args: JsonObject): List<JsonObject>? {
        (args["facts"] as? JsonArray)?.let { array ->
            return array.filterIsInstance<JsonObject>()
        }
        if (args["personName"] != null || args["objectName"] != null) return listOf(args)
        return null
    }

    fun parseFact(raw: JsonObject, index: Int): ParsedFact {
        val position = "facts[$index]"
        val personName = raw.text("personName")
            ?: return ParsedFact.Invalid("$position: missing personName")
        val predicateRaw = raw.text("predicate")
            ?: return ParsedFact.Invalid("$position: missing predicate")

        // Resolving here (rather than at save time) records the sighting once per
        // fact and lets EntityTypeResolver see the real predicate.
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
            val fromObject = objectName?.let { dates.parseDateLike(it) } ?: 0L
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
            dates.parseDateLike(objectName).takeIf { it != 0L }?.let {
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
        val parsed = dates.parseExplicitTimestamp(text)
        if (parsed == 0L) warnings.add("could not parse $field=\"$text\"; use ISO-8601 e.g. 2025-07-16T15:00")
        return parsed
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal sealed interface ParsedFact {
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
