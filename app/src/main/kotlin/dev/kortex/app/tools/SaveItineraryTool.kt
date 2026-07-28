package dev.kortex.app.tools

import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A helper tool that strictly parses itinerary information (e.g., flight tickets, hotel bookings)
 * and formats them into the exact triples required by the `save_knowledge` tool.
 */
class SaveItineraryTool : Tool {

    override val name = "format_itinerary"

    override val description =
        "Helper tool that formats structured itinerary data (flights, hotels, cabs) into the exact triples required by `save_knowledge`. " +
            "Call this tool with the ticket/booking details, and it will return a payload that you must then pass to `save_knowledge`."

    override val parameters = ToolSchema(
        params = listOf(
            ToolParam("eventType", "string", "Type of event (e.g., 'FLIGHT', 'HOTEL_STAY')"),
            ToolParam("provider", "string", "The company providing the service (e.g., 'IndiGo', 'Marriott')"),
            ToolParam("referenceNumber", "string", "Booking reference, PNR, or confirmation number"),
            ToolParam("startTime", "string", "Start or departure time in ISO-8601 format (e.g., '2026-07-20T10:00')"),
            ToolParam("endTime", "string", "End or arrival time in ISO-8601 format", required = false),
            ToolParam("locationStart", "string", "Departure origin or check-in location", required = false),
            ToolParam("locationEnd", "string", "Arrival destination or check-out location", required = false),
            ToolParam("userName", "string", "The name of the user who booked this itinerary (default is 'User')", required = false)
        )
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val eventType = args["eventType"]?.jsonPrimitive?.contentOrNull ?: "EVENT"
        val provider = args["provider"]?.jsonPrimitive?.contentOrNull ?: "Unknown Provider"
        val referenceNumber = args["referenceNumber"]?.jsonPrimitive?.contentOrNull ?: "Unknown Ref"
        val startTime = args["startTime"]?.jsonPrimitive?.contentOrNull ?: ""
        val endTime = args["endTime"]?.jsonPrimitive?.contentOrNull ?: ""
        val locationStart = args["locationStart"]?.jsonPrimitive?.contentOrNull ?: ""
        val locationEnd = args["locationEnd"]?.jsonPrimitive?.contentOrNull ?: ""
        val userName = args["userName"]?.jsonPrimitive?.contentOrNull ?: "User"

        // We construct a specific Topic entity for this event to avoid "Topic Collision"
        val eventTopicName = "$provider $eventType ($referenceNumber)"

        val facts = buildJsonArray {
            // 1. The user has this event
            addFact(userName, "HAS_BOOKING", eventTopicName)
            
            // 2. The event is provided by the provider
            addFact(eventTopicName, "PROVIDED_BY", provider)

            // 3. The event has a reference number
            addFact(eventTopicName, "HAS_REFERENCE", referenceNumber)

            // 4. The event has a validity window (Start / End times)
            addFact(eventTopicName, "SCHEDULED_FOR", null, startTime, endTime)

            // 5. Locations if provided
            if (locationStart.isNotBlank()) {
                addFact(eventTopicName, "DEPARTS_FROM", locationStart)
            }
            if (locationEnd.isNotBlank()) {
                addFact(eventTopicName, "ARRIVES_AT", locationEnd)
            }
        }

        val resultMessage = """
            Successfully formatted itinerary. Please immediately call the `save_knowledge` tool using the following payload:
            
            {
              "facts": $facts
            }
        """.trimIndent()

        return ToolResult(true, resultMessage)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addFact(
        subject: String,
        predicate: String,
        obj: String?,
        validFrom: String = "",
        validTo: String = ""
    ) {
        add(buildJsonObject {
            put("personName", subject)
            put("predicate", predicate)
            if (obj != null) {
                put("objectName", obj)
            }
            if (validFrom.isNotBlank()) {
                put("validFrom", validFrom)
            }
            if (validTo.isNotBlank()) {
                put("validTo", validTo)
            }
        })
    }
}
