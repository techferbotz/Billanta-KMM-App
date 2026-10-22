package com.ferbotz.billanta.fields

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The one truthiness rule (FIELDS.md §6.2). It decides a template's `conditional` (and its
 * `negate`), every `visibleIf` on a column, a row or an input, and whether a section counts as
 * empty, so they can never disagree.
 *
 * **Falsy:** absent, `null`, `false`, `""`, a decimal string equal to zero (`"0"`, `"0.00"`), an
 * empty array, an empty object. **Truthy:** everything else. The zero rule is what makes
 * `data-if="discount_total"` hide a zero discount row.
 */
fun isTruthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        value.isString -> value.content.isNotEmpty() && !isDecimalZero(value.content)
        // Values travel as strings, so a bare boolean is the only primitive the wire carries;
        // a JSON number is read the same way its string form would be, rather than refused.
        else -> value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 } ?: true
    }
    is JsonArray -> value.isNotEmpty()
    is JsonObject -> value.isNotEmpty()
}

/** Passes while [Gate.path] is truthy in [lookup], or falsy with [Gate.negate]. */
fun Gate.passes(lookup: (String) -> JsonElement?): Boolean = isTruthy(lookup(path)) != negate

/** `"0"`, `"00"`, `"0.00"`, `".0"`: digits that are all zero, with at most one dot. */
private fun isDecimalZero(text: String): Boolean {
    val body = text.removePrefix("-").removePrefix("+")
    if (body.isEmpty() || body == ".") return false
    var dots = 0
    for (c in body) {
        when (c) {
            '0' -> Unit
            '.' -> if (++dots > 1) return false
            else -> return false
        }
    }
    return true
}
