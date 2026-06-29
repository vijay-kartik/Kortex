package dev.kortex.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Typed accessors for the JSON args an LLM passes to a tool. */

fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content
        ?: error("Missing required string argument '$key'")

fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

fun JsonObject.int(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

fun JsonObject.double(key: String): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull
        ?: error("Missing required number argument '$key'")
