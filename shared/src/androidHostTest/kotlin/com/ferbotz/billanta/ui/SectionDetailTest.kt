package com.ferbotz.billanta.ui

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.render.SectionEdits
import com.ferbotz.billanta.ui.screens.addressLines
import com.ferbotz.billanta.ui.screens.detail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each section row previews. This is a promise about the *document*: the lines shown here are
 * the lines the section will print, so they have to come from the invoice's frozen snapshots rather
 * than from whatever the customer record happens to say today.
 */
class SectionDetailTest {

    private fun invoice() = InvoiceRecord(
        id = "inv-1",
        invoiceNumber = "INV-7",
        invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
        dueDateMillis = Iso8601.epochMillisFor(2026, 8, 20),
        customerId = "cust-1",
        customerName = "Stale Name From The List",
        customerSnapshot = CustomerSnapshot(
            name = "Kavya Iyer",
            gstin = "27ABCDE1234F1Z5",
            phone = "9876543210",
            email = "kavya@example.com",
            addressLine1 = "12 Linking Road",
            city = "Mumbai",
            state = "Maharashtra",
            pincode = "400050",
        ),
        companySnapshot = CompanySnapshot(name = "Studio Nine", gstin = "27ZZZZZ0000Z1Z5"),
        items = listOf(
            InvoiceItemRecord(
                description = "Design work", quantity = "2", unitPricePaise = 100_000,
                taxRatePercent = "18", lineTotalPaise = 200_000, taxAmountPaise = 36_000,
            ),
        ),
        subtotalPaise = 200_000,
        taxTotalPaise = 36_000,
        grandTotalPaise = 236_000,
        updatedAtMillis = 1L,
    )

    @Test
    fun the_billed_to_section_shows_the_whole_party() {
        val lines = SectionEdits.Customer.detail(invoice())

        assertTrue(lines.any { it == "Kavya Iyer" }, "name is missing: $lines")
        assertTrue(lines.any { it.contains("27ABCDE1234F1Z5") }, "GSTIN is missing: $lines")
        assertTrue(lines.any { it.contains("9876543210") }, "phone is missing: $lines")
        assertTrue(lines.any { it.contains("kavya@example.com") }, "email is missing: $lines")
        assertTrue(lines.any { it.contains("Linking Road") }, "address is missing: $lines")
        assertTrue(lines.any { it.contains("Mumbai") && it.contains("400050") }, "locality is missing: $lines")
    }

    /**
     * The snapshot is what the PDF prints. If this ever read the live customer instead, the editor
     * would quietly disagree with the document the customer received.
     */
    @Test
    fun the_billed_to_section_reads_the_snapshot_not_the_denormalised_name() {
        val lines = SectionEdits.Customer.detail(invoice())
        assertTrue(
            lines.none { it.contains("Stale Name") },
            "the row used the cached list name instead of the invoice's snapshot: $lines",
        )
    }

    /** An invoice made before a customer was chosen still has the denormalised name to fall back on. */
    @Test
    fun a_missing_snapshot_falls_back_rather_than_showing_nothing() {
        val record = invoice().copy(customerSnapshot = null)
        assertEquals(listOf("Stale Name From The List"), SectionEdits.Customer.detail(record))

        val blank = record.copy(customerName = null)
        assertTrue(SectionEdits.Customer.detail(blank).isEmpty(), "with neither, the section is empty")
    }

    @Test
    fun the_items_section_shows_every_line_with_its_money() {
        val lines = SectionEdits.Items.detail(invoice())
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Design work"), "description missing: ${lines[0]}")
        assertTrue(lines[0].contains("2"), "quantity missing: ${lines[0]}")
        assertTrue(lines[0].contains("₹1,000"), "unit price missing: ${lines[0]}")
        assertTrue(lines[0].contains("₹2,000"), "line total missing: ${lines[0]}")
    }

    @Test
    fun every_item_gets_a_line_of_its_own() {
        val many = invoice().copy(
            items = (1..4).map {
                InvoiceItemRecord(
                    description = "Item $it", quantity = "1", unitPricePaise = 1000L * it,
                    taxRatePercent = "18", lineTotalPaise = 1000L * it,
                )
            },
        )
        assertEquals(4, SectionEdits.Items.detail(many).size)
    }

    @Test
    fun the_totals_section_shows_the_arithmetic() {
        val lines = SectionEdits.Discount.detail(invoice())
        assertTrue(lines.any { it.startsWith("Subtotal") }, "subtotal missing: $lines")
        assertTrue(lines.any { it.startsWith("Tax") }, "tax missing: $lines")
        assertTrue(lines.any { it.startsWith("Total") }, "grand total missing: $lines")

        val discounted = invoice().copy(
            discount = DiscountSpec(DiscountType.Percentage, "10"),
            discountTotalPaise = 20_000,
        )
        val withDiscount = SectionEdits.Discount.detail(discounted)
        assertTrue(withDiscount.any { it.contains("10% discount") }, "the discount itself is missing: $withDiscount")
        assertTrue(withDiscount.any { it.startsWith("Less") }, "the deduction is missing: $withDiscount")
    }

    @Test
    fun an_empty_invoice_reports_every_editable_section_as_empty() {
        val blank = InvoiceRecord(
            id = "inv-1",
            invoiceNumber = "",
            invoiceDateMillis = Iso8601.epochMillisFor(2026, 8, 6),
            updatedAtMillis = 1L,
        )
        assertTrue(SectionEdits.Customer.detail(blank).isEmpty())
        assertTrue(SectionEdits.Items.detail(blank).isEmpty())
        assertTrue(SectionEdits.Notes.detail(blank).isEmpty())
        assertTrue(SectionEdits.Company.detail(blank).isEmpty())
        // Details always has the date, so it is never blank — the dot should not claim otherwise.
        assertTrue(SectionEdits.InvoiceDetails.detail(blank).isNotEmpty())
    }

    @Test
    fun an_address_folds_onto_as_few_lines_as_the_filled_fields_allow() {
        assertEquals(
            listOf("12 Linking Road", "Mumbai, Maharashtra, 400050"),
            addressLines("12 Linking Road", null, "Mumbai", "Maharashtra", "400050"),
        )
        assertEquals(listOf("Mumbai"), addressLines(null, null, "Mumbai", null, null))
        assertTrue(addressLines(null, null, null, null, null).isEmpty(), "nothing in, nothing out")
        // Blank strings are not addresses; they must not become an empty bullet line.
        assertTrue(addressLines("", "  ", "", "", "").isEmpty(), "blank fields should be dropped")
    }
}
