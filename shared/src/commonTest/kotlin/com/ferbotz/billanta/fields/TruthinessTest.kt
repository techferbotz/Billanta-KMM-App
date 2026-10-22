package com.ferbotz.billanta.fields

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TruthinessTest {

    @Test
    fun falsy_values_match_fields_md() {
        assertFalse(isTruthy(null))
        assertFalse(isTruthy(JsonNull))
        assertFalse(isTruthy(JsonPrimitive(false)))
        assertFalse(isTruthy(JsonPrimitive("")))
        assertFalse(isTruthy(JsonPrimitive("0")))
        assertFalse(isTruthy(JsonPrimitive("0.00")))
        assertFalse(isTruthy(JsonPrimitive("00")))
        assertFalse(isTruthy(JsonArray(emptyList())))
        assertFalse(isTruthy(JsonObject(emptyMap())))
    }

    @Test
    fun everything_else_is_truthy() {
        assertTrue(isTruthy(JsonPrimitive(true)))
        assertTrue(isTruthy(JsonPrimitive("INV-1")))
        assertTrue(isTruthy(JsonPrimitive("0.01")))
        assertTrue(isTruthy(JsonPrimitive("10")))
        // Not a decimal, so not "zero": a dot alone or two dots are ordinary text.
        assertTrue(isTruthy(JsonPrimitive(".")))
        assertTrue(isTruthy(JsonPrimitive("0.0.0")))
        assertTrue(isTruthy(JsonPrimitive(" ")))
        assertTrue(isTruthy(JsonArray(listOf(JsonPrimitive("x")))))
        assertTrue(isTruthy(JsonObject(mapOf("url" to JsonPrimitive("https://x")))))
    }

    @Test
    fun a_gate_inverts_with_negate() {
        val values = mapOf("hide_gst_column" to JsonPrimitive(true))
        assertTrue(Gate("hide_gst_column").passes { values[it] })
        assertFalse(Gate("hide_gst_column", negate = true).passes { values[it] })
        // An absent value is falsy, so a negated gate on it passes.
        assertTrue(Gate("missing", negate = true).passes { values[it] })
    }
}
