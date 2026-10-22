package com.ferbotz.billanta.fields

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldValuesTest {

    @Test
    fun clearing_removes_the_key_instead_of_storing_a_placeholder() {
        val values = FieldValues.EMPTY
            .withString("po_number", "PO-1")
            .with("company_logo", ImageValue("https://x/logo.webp", "https://x/t.webp").toJson())

        for (empty in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive("  "), JsonObject(emptyMap()), JsonArray(emptyList()))) {
            assertFalse("po_number" in values.with("po_number", empty), "$empty should clear the key")
        }
        assertEquals(setOf("company_logo"), values.withString("po_number", null).keys)
    }

    @Test
    fun of_drops_empty_values_whatever_the_source() {
        val parsed = FieldValues.parse("""{"a":"x","b":"","c":null,"d":{},"e":[]}""")
        assertEquals(setOf("a"), parsed.keys)
    }

    @Test
    fun unknown_shapes_survive_a_round_trip_untouched() {
        val text = """{"future_field":{"kind":"stars","count":"4"},"notes":"Thanks"}"""
        assertEquals(FieldValues.parse(text), FieldValues.parse(FieldValues.parse(text).toJsonString()))
    }

    @Test
    fun parse_never_throws() {
        assertTrue(FieldValues.parse("not json").isEmpty())
        assertTrue(FieldValues.parse("[1,2]").isEmpty())
        assertTrue(FieldValues.parse(null).isEmpty())
    }

    @Test
    fun typed_reads() {
        val values = FieldValues.parse(
            """{"quantity":"2.5","hide_gst_column":true,"invoice_number":"INV-1",
               "company_logo":{"url":"https://x/l.webp","thumbnailUrl":"https://x/t.webp"},
               "discount":{"kind":"percent","value":"10"}}""",
        )
        assertEquals("2.5", values.decimal("quantity").toString())
        assertEquals(true, values.bool("hide_gst_column"))
        assertNull(values.bool("invoice_number"))
        assertEquals("https://x/t.webp", values.image("company_logo")?.previewUrl)
        // beforeTax is absent, so it defaults to true, as MONEY.md's inputs table says.
        assertEquals(DiscountValue(DiscountValue.Kind.Percent, "10", beforeTax = true), values.discount())
    }

    @Test
    fun a_remembered_value_must_fit_the_fields_current_encoding() {
        assertTrue(ValueEncoding.Decimal.fits(JsonPrimitive("2.5")))
        assertFalse(ValueEncoding.Decimal.fits(JsonPrimitive("two")))
        assertTrue(ValueEncoding.Paise.fits(JsonPrimitive("150000")))
        assertFalse(ValueEncoding.Paise.fits(JsonPrimitive("1500.00")))
        assertTrue(ValueEncoding.Bool.fits(JsonPrimitive(false)))
        assertFalse(ValueEncoding.Bool.fits(JsonPrimitive("false")))
        assertTrue(ValueEncoding.Date.fits(JsonPrimitive("2026-11-05")))
        assertFalse(ValueEncoding.Date.fits(JsonPrimitive("05/11/2026")))
        assertTrue(ValueEncoding.Text.fits(JsonPrimitive("PO-1")))
        assertFalse(ValueEncoding.Text.fits(JsonPrimitive(true)))
        assertFalse(ValueEncoding.Image.fits(JsonPrimitive("https://x")))
        assertFalse(ValueEncoding.NotStored.fits(JsonPrimitive("x")))
    }
}
