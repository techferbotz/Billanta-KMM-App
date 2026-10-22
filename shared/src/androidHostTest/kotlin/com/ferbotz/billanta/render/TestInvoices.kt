package com.ferbotz.billanta.render

import com.ferbotz.billanta.fields.Be021Fixtures
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import kotlin.test.assertNotNull

/**
 * Invoices for renderer tests, built the way the app builds them — a values map, lines, totals
 * worked out by the money rules — and drawn on the backend's own compiled Classic (BE-021).
 */
object TestInvoices {

    val classic: TemplateDoc by lazy {
        assertNotNull(TemplateParser.parse(Be021Fixtures.text("template-classic.compiled.json")), "classic did not parse")
    }

    val optionLists get() = Be021Fixtures.forms.optionLists

    val company: FieldValues = FieldValues.EMPTY
        .withString("company_name", "Studio Nine")
        .withString("company_gstin", "27ABCDE1234F1Z5")
        .withString("company_address_line1", "A-901, Oberoi Springs")
        .withString("company_city", "Mumbai")
        .withString("company_pincode", "400053")
        .withString("company_state", "27")
        .withString("company_email", "hello@studionine.in")
        .withString("company_upi_id", "studionine@okhdfcbank")
        .withString("company_bank_name", "HDFC Bank")
        .withString("company_bank_account_number", "50100234567821")
        .withString("company_bank_ifsc", "HDFC0000123")

    val customer: FieldValues = FieldValues.EMPTY
        .withString("customer", "cust-1")
        .withString("customer_name", "Kavya Iyer")
        .withString("customer_gstin", "27AAJCB1111C1Z2")
        .withString("customer_address_line1", "12 Carter Road, Bandra West")
        .withString("customer_city", "Mumbai")
        .withString("customer_pincode", "400050")
        .withString("customer_state", "27")

    /** A Studio Nine invoice to Kavya Iyer with [itemCount] lines of ₹68,000 at 18%. */
    fun invoice(
        itemCount: Int,
        customer: FieldValues = this.customer,
        notes: String? = "Payable within 14 days via UPI or bank transfer.",
        description: (Int) -> String = { "Brand identity design work, phase $it" },
    ): Invoice = Invoice(
        id = "inv-1",
        values = company.withAll(customer)
            .withString("invoice_number", "INV-2026-0042")
            .withString("invoice_date", "2026-08-05")
            .withString("due_date", "2026-08-19")
            .withString("currency", "INR")
            .withString("notes", notes),
        lines = (1..itemCount).map { i ->
            InvoiceLine(
                id = "line-$i",
                values = FieldValues.EMPTY
                    .withString("description", description(i))
                    .withString("hsn_sac", "998311")
                    .withString("quantity", "1")
                    .withString("unit_price", "6800000")
                    .withString("tax_rate", "18"),
            )
        },
        updatedAtMillis = 1L,
    ).recomputed()

    /** Bindings over [invoice], with select labels from the template and the forms. */
    fun bindings(doc: TemplateDoc, invoice: Invoice): BindingContext =
        BindingContext(invoice.renderContext(), "INR", selectLabels = SelectLabels(doc.sections, optionLists))
}
