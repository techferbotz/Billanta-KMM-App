package com.ferbotz.billanta.records

import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.money.InvoiceComputed
import com.ferbotz.billanta.domain.money.InvoiceMath
import com.ferbotz.billanta.domain.money.LineComputed
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.fields.ImageValue
import com.ferbotz.billanta.render.RenderLine
import com.ferbotz.billanta.render.renderContextOf
import kotlinx.serialization.json.JsonObject

/*
 * The records of BE-021. Every one is a values map (field id → value) plus the few fixed fields the
 * contract keeps outside it (FIELDS_APP_GUIDE.md §2). Nothing here names a field id it does not
 * need for its own bookkeeping: what a record holds is the backend's to define.
 */

/** The business profile: `company_*` values. One per user. */
data class BusinessProfile(
    val values: FieldValues = FieldValues.EMPTY,
    /** Ids changed since the last push; they go up as one `PATCH /company`. */
    val dirtyKeys: Set<String> = emptySet(),
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
)

/** The user's settings: `settings_*` values, the defaults a new invoice starts from. */
data class InvoiceSettings(
    val values: FieldValues = FieldValues.EMPTY,
    val dirtyKeys: Set<String> = emptySet(),
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
)

data class Customer(
    val id: String,
    val values: FieldValues = FieldValues.EMPTY,
    val dirtyKeys: Set<String> = emptySet(),
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    /** True while this row has local edits the server hasn't confirmed. */
    val pendingSync: Boolean = false,
) {
    val name: String get() = values.string(NAME).orEmpty()

    companion object {
        const val NAME = "customer_name"
        const val PHONE = "customer_phone"
        const val GSTIN = "customer_gstin"
    }
}

/** A catalogue entry. Its values use the line ids, so picking it copies them into a line. */
data class Product(
    val id: String,
    val values: FieldValues = FieldValues.EMPTY,
    val dirtyKeys: Set<String> = emptySet(),
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    val pendingSync: Boolean = false,
    /** How often this device picked it, and when last; local ordering only, never synced. */
    val usageCount: Long = 0,
    val lastUsedAtMillis: Long = 0,
) {
    val description: String get() = values.string(DESCRIPTION).orEmpty()
    val image: ImageValue? get() = values.image(IMAGE)

    companion object {
        const val DESCRIPTION = "description"
        const val IMAGE = "image"

        /** Case- and whitespace-insensitive identity, so "Logo design " repeats "logo  design". */
        fun nameKeyOf(description: String): String = description.trim().lowercase().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}

/** One line of an invoice, in its own values map. */
data class InvoiceLine(
    val id: String,
    val values: FieldValues = FieldValues.EMPTY,
    /** `line_total` and `tax_amount`: ours until a sync, then the server's. */
    val computed: LineComputed? = null,
)

/** How many lines an invoice has, and the first one's description: "what was this for". */
data class InvoiceLineSummary(val count: Int, val firstDescription: String?)

data class Invoice(
    val id: String,
    val status: InvoiceDocStatus = InvoiceDocStatus.Draft,
    val templateId: String? = null,
    val templateVersion: Long? = null,
    /** The invoice's own fields plus its complete copy of the company and customer values. */
    val values: FieldValues = FieldValues.EMPTY,
    /** Empty on list projections; see [lineSummary]. */
    val lines: List<InvoiceLine> = emptyList(),
    /** The totals: ours until a sync, then the server's, which must agree to the paise. */
    val computed: InvoiceComputed = InvoiceMath.compute(values, lines.map { it.values }),
    /** Sections switched off, by id; opaque to the server. */
    val hiddenSections: Set<String> = emptySet(),
    /** Colour token → `#hex`, as the wire carries it; opaque to the server. */
    val themeOverrides: Map<String, String> = emptyMap(),
    val pdfPath: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    val deletedAtMillis: Long? = null,
    val pendingSync: Boolean = false,
    /** Server-reported sync conflict (a duplicate number, say), shown to the user. */
    val syncError: String? = null,
    /** Set on list projections, where [lines] is not loaded. */
    val itemSummary: InvoiceLineSummary? = null,
) {
    val isDraft: Boolean get() = status == InvoiceDocStatus.Draft
    val invoiceNumber: String get() = values.string(INVOICE_NUMBER).orEmpty()
    val customerId: String? get() = values.string(CUSTOMER)
    val customerName: String? get() = values.string(Customer.NAME)

    val lineSummary: InvoiceLineSummary
        get() = itemSummary ?: InvoiceLineSummary(lines.size, lines.firstOrNull()?.values?.string(Product.DESCRIPTION))

    /** The flat map a template is drawn with (FIELDS.md §8.1). */
    fun renderContext(): JsonObject = renderContextOf(
        values = values,
        lines = lines.mapIndexed { i, line ->
            RenderLine(line.values, (line.computed ?: computed.lines.getOrNull(i))?.toMap().orEmpty())
        },
        totals = computed.totalsMap(),
    )

    /** This invoice with its totals worked out again from its values, as after any local edit. */
    fun recomputed(): Invoice {
        val fresh = InvoiceMath.compute(values, lines.map { it.values })
        return copy(
            computed = fresh,
            lines = lines.mapIndexed { i, line -> line.copy(computed = fresh.lines[i]) },
        )
    }

    companion object {
        const val INVOICE_NUMBER = "invoice_number"
        const val INVOICE_DATE = "invoice_date"
        const val CUSTOMER = "customer"
        const val CURRENCY = "currency"
    }
}
