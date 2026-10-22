package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.fields.FieldValues
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * How BE-021's maps and key sets sit in their TEXT columns. Every decoder is lenient: a column
 * that does not parse reads as empty, so one damaged row can never take a screen down with it.
 */

/** The ids whose value differs between [old] and [new], including ones added or removed. */
internal fun changedKeys(old: FieldValues, new: FieldValues): Set<String> =
    (old.keys + new.keys).filterTo(LinkedHashSet()) { old[it] != new[it] }

internal fun encodeKeys(keys: Set<String>): String = JsonArray(keys.sorted().map(::JsonPrimitive)).toString()

internal fun decodeKeys(json: String?): Set<String> =
    (parseOrNull(json) as? JsonArray)
        ?.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptySet()

internal fun encodeStringMap(map: Map<String, String>): String =
    JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString()

internal fun decodeStringMap(json: String?): Map<String, String> =
    (parseOrNull(json) as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
        ?.toMap()
        ?: emptyMap()

internal fun parseOrNull(json: String?): JsonElement? {
    if (json.isNullOrBlank()) return null
    return try {
        BillantaJson.parseToJsonElement(json)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
