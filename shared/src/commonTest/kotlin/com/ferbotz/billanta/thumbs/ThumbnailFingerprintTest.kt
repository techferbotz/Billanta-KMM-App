package com.ferbotz.billanta.thumbs

import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.fields.FieldOption
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThumbnailFingerprintTest {

    private val states = mapOf("indian_states" to listOf(FieldOption("29", "Karnataka")))
    private val options = ThumbnailFingerprint.optionListsKey(states)

    private fun line(id: String, description: String) = InvoiceLine(
        id = id,
        values = FieldValues.EMPTY
            .withString("description", description)
            .withString("quantity", "1")
            .withString("unit_price", "2500000")
            .withString("tax_rate", "18"),
    )

    private val invoice = Invoice(
        id = "inv-1",
        values = FieldValues.EMPTY
            .withString("invoice_number", "INV-1")
            .withString("customer_name", "Meridian")
            .withString("customer_state", "29"),
        lines = listOf(line("l1", "Logo design"), line("l2", "Brochures")),
        updatedAtMillis = 1L,
    ).recomputed()

    private fun key(
        of: Invoice = invoice,
        version: Long = 1,
        dates: InvoiceDateFormat = InvoiceDateFormat.DAY_MONTH_YEAR,
        optionLists: String = options,
    ) = ThumbnailFingerprint.of(of, "amber", version, dates, optionLists)

    @Test
    fun the_same_invoice_is_filed_under_the_same_key() {
        assertEquals(key(), key(invoice.copy()))
        assertEquals(16, key().length)
    }

    @Test
    fun everything_that_is_drawn_changes_the_key() {
        val changed = mapOf(
            "a value" to key(invoice.copy(values = invoice.values.withString("customer_name", "Kaveri")).recomputed()),
            "a line" to key(invoice.copy(lines = listOf(line("l1", "Logo design v2"), invoice.lines[1])).recomputed()),
            "the line order" to key(invoice.copy(lines = invoice.lines.reversed()).recomputed()),
            "the server's totals" to key(invoice.copy(computed = invoice.computed.copy(grandTotal = invoice.computed.grandTotal + 1))),
            "the template version drawn" to key(version = 2),
            "a colour" to key(invoice.copy(themeOverrides = mapOf("accent" to "#112233"))),
            "a hidden section" to key(invoice.copy(hiddenSections = setOf("notes"))),
            "the date format" to key(dates = InvoiceDateFormat.ISO),
            "an option label" to key(optionLists = ThumbnailFingerprint.optionListsKey(mapOf("indian_states" to listOf(FieldOption("29", "Karnataka (KA)"))))),
        )
        changed.forEach { (what, changedKey) -> assertNotEquals(key(), changedKey, "$what should change the key") }
    }

    @Test
    fun status_is_not_drawn_so_it_keeps_the_key() {
        assertEquals(key(), key(invoice.copy(status = InvoiceDocStatus.Pending)))
    }

    @Test
    fun the_order_overrides_and_hidden_sections_were_stored_in_does_not_matter() {
        val one = invoice.copy(themeOverrides = linkedMapOf("accent" to "#112233", "secondary" to "#445566"), hiddenSections = linkedSetOf("notes", "terms"))
        val other = invoice.copy(themeOverrides = linkedMapOf("secondary" to "#445566", "accent" to "#112233"), hiddenSections = linkedSetOf("terms", "notes"))
        assertEquals(key(one), key(other))
    }
}
