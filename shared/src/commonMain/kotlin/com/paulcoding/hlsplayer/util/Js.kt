package com.paulcoding.hlsplayer.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Untrusted JSON — backups, pastes, addon answers — is read the way the
 * extension reads it, so the two accept and refuse the same files. These are
 * JavaScript's coercions, spelled out.
 */

/** Writes absent optionals as absent and defaults as present, like `JSON.stringify`. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

val PrettyJson = Json(AppJson) { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * `JSON.parse`: kotlinx accepts a bare word like `nope` as a top-level value,
 * where JavaScript refuses it, and a file that is not JSON must be refused here too.
 */
fun parseJson(text: String): JsonElement {
    val element = AppJson.parseToJsonElement(text)
    if (element is JsonPrimitive && !element.isString && element !is JsonNull &&
        element.booleanOrNull == null && element.content.toDoubleOrNull() == null
    ) {
        throw IllegalArgumentException("Unexpected JSON token")
    }
    return element
}

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject?.field(name: String): JsonElement? = this?.get(name)

/** A string, or [fallback] for anything else — `typeof value === 'string'`. */
fun JsonElement?.string(fallback: String = ""): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallback

/** `Boolean(value)`. */
fun JsonElement?.truthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        booleanOrNull != null -> booleanOrNull!!
        else -> doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
    }
    else -> true
}

/** `Number(value)`; NaN where JavaScript would give NaN. */
fun JsonElement?.jsNumber(): Double = when (this) {
    null -> Double.NaN
    JsonNull -> 0.0
    is JsonPrimitive -> when {
        isString -> content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
        booleanOrNull != null -> if (booleanOrNull!!) 1.0 else 0.0
        else -> doubleOrNull ?: Double.NaN
    }
    else -> Double.NaN
}

/** `Number.isFinite(value)` — true only for an actual finite number, never a string. */
fun JsonElement?.finiteNumber(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

/** `typeof value === 'boolean'`. */
fun JsonElement?.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
