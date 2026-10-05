package dev.kortex.app.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A Choice answer's pick and its probability; null when [id] isn't a readable Choice answer. */
internal fun JsonObject.choice(id: String): Pair<String, Double>? {
    val answer = this[id] as? JsonObject ?: return null
    val choice = answer["choice"]?.jsonPrimitive?.content ?: return null
    val probability = answer["probabilities"]?.jsonObject?.get(choice)?.jsonPrimitive?.doubleOrNull ?: return null
    return choice to probability
}

/** A Boolean answer's P(true); null when [id] isn't a readable Boolean answer. */
internal fun JsonObject.probability(id: String): Double? =
    (this[id] as? JsonObject)?.get("probability")?.jsonPrimitive?.doubleOrNull
