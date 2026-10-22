package com.ferbotz.billanta.fields

import com.ferbotz.billanta.core.BillantaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The field parser against the backend's own `forms.json` and compiled Classic template. */
class Be021FieldsFixtureTest {

    private val forms = Be021Fixtures.forms

    @Test
    fun forms_json_has_the_four_forms_and_the_states() {
        assertEquals(listOf("business", "address", "bank", "upi", "signature"), forms.company.map { it.id })
        assertEquals(listOf("customer", "address"), forms.customer.map { it.id })
        assertEquals(listOf("product"), forms.product.map { it.id })
        assertEquals(listOf("invoice_numbering", "defaults"), forms.settings.map { it.id })
        assertTrue((forms.company + forms.customer + forms.product + forms.settings).none { it.hidable })

        val states = forms.optionLists.getValue("indian_states")
        assertEquals(38, states.size)
        assertEquals("Karnataka", states.single { it.value == "29" }.label)
    }

    @Test
    fun every_form_field_is_known_to_this_build() {
        val all = (forms.company + forms.customer + forms.product + forms.settings).flatMap { it.fields }
        assertEquals(emptyList(), all.filter { it.type == null }.map { it.rawType })
        // FIELDS.md §3.3: 23 company ids, 11 customer, 6 product (the line ids minus quantity), 7 settings.
        assertEquals(23, forms.company.sumOf { it.fields.size })
        assertEquals(11, forms.customer.sumOf { it.fields.size })
        assertEquals(listOf("description", "image", "hsn_sac", "unit", "unit_price", "tax_rate"), forms.product.flatMap { it.fields }.map { it.id })
        assertEquals(7, forms.settings.sumOf { it.fields.size })
    }

    @Test
    fun form_attributes_come_through() {
        val customerState = forms.customer.flatMap { it.fields }.single { it.id == "customer_state" }
        assertEquals(FieldType.Select, customerState.type)
        assertEquals(FieldOptions.Named("indian_states"), customerState.options)
        assertEquals("Also the place of supply", customerState.help)

        val taxRate = forms.product.flatMap { it.fields }.single { it.id == "tax_rate" }
        assertEquals(FieldDefault.Setting("settings_default_tax_rate"), taxRate.default)
        assertEquals(0.0, taxRate.min)
        assertEquals(100.0, taxRate.max)

        val next = forms.settings.flatMap { it.fields }.single { it.id == "settings_next_invoice_number" }
        assertEquals(FieldDefault.Value(JsonPrimitive("1")), next.default)
        assertEquals(0, next.decimals)

        val country = forms.company.flatMap { it.fields }.single { it.id == "company_country" }
        assertEquals(FieldDefault.Value(JsonPrimitive("India")), country.default)
    }

    @Test
    fun classic_declares_seven_sections_in_editor_order() {
        val doc = BillantaJson.parseToJsonElement(Be021Fixtures.text("template-classic.compiled.json")) as JsonObject
        val sections = FieldParser.parseSections(doc["sections"], hidableByDefault = true)

        assertEquals(
            listOf("Invoice details", "Your business", "Bill to", "Items", "Total", "Payment details", "Notes"),
            sections.map { it.label },
        )
        assertEquals(listOf("payment", "notes"), sections.filter { it.hidable }.map { it.id })

        val header = sections.single { it.id == "header" }
        assertEquals(listOf("invoice_number", "invoice_date", "due_date"), header.fields.map { it.id })
        val due = header.fields.single { it.id == "due_date" }
        assertEquals(FieldType.DueDate, due.type)
        assertEquals(listOf(0, 7, 15, 30), due.presets)
        assertEquals("invoice_date", due.dueDateBase)
        assertTrue(due.remembers)

        val lineItems = sections.single { it.id == "items" }.fields.single()
        assertEquals(FieldType.LineItems, lineItems.type)
        assertEquals(listOf("description", "quantity", "unit_price", "tax_rate", "line_total"), lineItems.columns.map { it.id })
        assertEquals(FieldOwner.LineComputed, lineItems.columns.last().owner)
        assertEquals("currency", lineItems.columns.last().format)

        val parties = sections.single { it.id == "parties" }
        assertEquals(FieldType.Customer, parties.fields.first().type)
        assertEquals(FieldOwner.Customer, parties.fields.single { it.id == "customer_state" }.owner)
    }
}
