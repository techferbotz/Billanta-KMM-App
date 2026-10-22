package com.ferbotz.billanta.fields

import com.ferbotz.billanta.core.BillantaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The forward-compatibility rules of FIELDS.md §4.6, on hand-written definitions. */
class FieldParserTest {

    private fun field(json: String): FieldDef? =
        FieldParser.parseField(BillantaJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun an_unknown_type_draws_its_known_fallback() {
        val f = assertNotNull(field("""{"id":"rating","owner":"invoice","type":"stars","fallbackType":"number","label":"Rating"}"""))
        assertNull(f.type)
        assertEquals("stars", f.rawType)
        assertEquals(FieldType.Number, f.effectiveType)
    }

    @Test
    fun an_unknown_type_without_a_known_fallback_is_hidden_but_kept() {
        val f = assertNotNull(field("""{"id":"rating","owner":"invoice","type":"stars","fallbackType":"hearts","label":"Rating"}"""))
        assertNull(f.effectiveType)
        assertEquals("rating", f.id)
    }

    @Test
    fun an_unknown_owner_is_saved_on_the_invoice() {
        assertEquals(FieldOwner.Invoice, field("""{"id":"x","owner":"warehouse","type":"text","label":"X"}""")?.owner)
    }

    @Test
    fun an_unknown_default_kind_means_no_default() {
        assertNull(field("""{"id":"x","owner":"invoice","type":"text","label":"X","default":{"kind":"yesterday"}}""")?.default)
    }

    @Test
    fun unknown_attributes_are_ignored() {
        val f = assertNotNull(field("""{"id":"x","owner":"invoice","type":"text","label":"X","sparkle":true,"maxLength":40}"""))
        assertEquals(40, f.maxLength)
    }

    @Test
    fun defaults_parse_in_all_three_forms() {
        assertEquals(
            FieldDefault.Value(JsonPrimitive("1")),
            field("""{"id":"quantity","owner":"line","type":"number","label":"Qty","default":{"kind":"value","value":"1"}}""")?.default,
        )
        assertEquals(
            FieldDefault.Today,
            field("""{"id":"invoice_date","owner":"invoice","type":"date","label":"Date","default":{"kind":"today"}}""")?.default,
        )
        assertEquals(
            FieldDefault.Setting("settings_default_notes"),
            field("""{"id":"notes","owner":"invoice","type":"multiline","label":"Notes","default":{"kind":"setting","id":"settings_default_notes"}}""")?.default,
        )
    }

    @Test
    fun a_stored_field_needs_an_id_but_info_does_not() {
        assertNull(field("""{"owner":"invoice","type":"text","label":"No id"}"""))
        val info = assertNotNull(field("""{"type":"info","label":"","text":"Prices include GST"}"""))
        assertNull(info.id)
        assertEquals("Prices include GST", info.text)
        assertTrue(!info.isStored)
    }

    @Test
    fun remember_is_honoured_only_where_the_contract_allows_it() {
        assertTrue(field("""{"id":"po_number","owner":"invoice","type":"text","label":"PO","remember":true}""")!!.remembers)
        // Company values live on the profile, and the invoice number comes from settings.
        assertTrue(!field("""{"id":"company_name","owner":"company","type":"text","label":"Name","remember":true}""")!!.remembers)
        assertTrue(!field("""{"id":"invoice_number","owner":"invoice","type":"invoice_number","label":"No.","remember":true}""")!!.remembers)
    }

    @Test
    fun options_are_inline_or_named() {
        val named = field("""{"id":"customer_state","owner":"customer","type":"select","label":"State","options":"indian_states"}""")
        assertEquals(FieldOptions.Named("indian_states"), named?.options)
        val inline = field("""{"id":"terms_kind","owner":"invoice","type":"select","label":"Terms","options":[{"value":"net15","label":"Net 15"},{"value":"net30"}]}""")
        assertEquals(
            FieldOptions.Inline(listOf(FieldOption("net15", "Net 15"), FieldOption("net30", "net30"))),
            inline?.options,
        )
    }

    @Test
    fun malformed_sections_are_dropped_not_fatal() {
        val sections = FieldParser.parseSections(
            BillantaJson.parseToJsonElement("""[{"label":"no id"},"junk",{"id":"notes","fields":[{"id":"notes","owner":"invoice","type":"multiline","label":"Notes"}]}]"""),
            hidableByDefault = true,
        )
        assertEquals(listOf("notes"), sections.map { it.id })
        assertTrue(sections.single().hidable)
        assertEquals("notes", sections.single().label)
    }

    @Test
    fun forms_parse_with_or_without_the_envelope() {
        val bare = """{"schemaVersion":1,"optionLists":{"indian_states":[{"value":"29","label":"Karnataka"}]},
            "forms":{"company":{"sections":[{"id":"business","label":"Business details","fields":[]}]}}}"""
        val enveloped = """{"success":true,"data":$bare}"""
        for (json in listOf(bare, enveloped)) {
            val doc = assertNotNull(FormsDoc.parse(json))
            assertEquals("Karnataka", doc.optionsFor(FieldOptions.Named("indian_states")).single().label)
            assertEquals(listOf("business"), doc.company.map { it.id })
            assertTrue(doc.customer.isEmpty())
        }
        assertNull(FormsDoc.parse("""{"success":true,"data":{"something":"else"}}"""))
        assertNull(FormsDoc.parse("garbage"))
    }
}
