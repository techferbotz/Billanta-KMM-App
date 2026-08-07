package com.ferbotz.billanta.domain.model

import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType

/** The signed-in user (`GET /users/me`). */
data class UserAccount(
    val id: String,
    val email: String,
    val name: String? = null,
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
)

/** The user's business (`GET /company`). Field names mirror the API/binding namespace. */
data class CompanyProfile(
    val name: String,
    val gstin: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val logo: String? = null,
    val signature: String? = null,
    val upiId: String? = null,
    val qr: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val ifsc: String? = null,
)

/** `GET /settings` — server auto-creates defaults on first call. */
data class UserSettings(
    val defaultCurrency: String = "INR",
    val defaultTaxPercent: String = "18",
    val invoiceNumberPrefix: String = "INV-",
    val nextInvoiceNumber: Long = 1,
    val defaultTemplateId: String? = null,
    val defaultNotes: String? = null,
) {
    fun formatNextInvoiceNumber(): String = "$invoiceNumberPrefix$nextInvoiceNumber"
}

data class CustomerRecord(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val gstin: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val stateCode: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    /** True while this row has local edits the server hasn't confirmed. */
    val pendingSync: Boolean = false,
)

/** Wire names are exact (`"Draft" | "Pending" | "Paid"`). */
enum class InvoiceDocStatus { Draft, Pending, Paid }

data class InvoiceItemRecord(
    val description: String,
    val hsnSac: String? = null,
    /** Decimal string, e.g. `"2.5"`. */
    val quantity: String,
    val unitPricePaise: Long,
    /** Decimal string percent, e.g. `"18"`. */
    val taxRatePercent: String,
    /** quantity × unitPrice (pre-discount, pre-tax), server-parity computed. */
    val lineTotalPaise: Long = 0,
    val taxAmountPaise: Long = 0,
)

data class InvoiceRecord(
    val id: String,
    val invoiceNumber: String,
    val invoiceDateMillis: Long,
    val dueDateMillis: Long? = null,
    val currency: String = "INR",
    val status: InvoiceDocStatus = InvoiceDocStatus.Draft,
    val templateId: String? = null,
    val templateVersion: Long? = null,
    val customerId: String? = null,
    /** Denormalized from the customer snapshot for lists/search. */
    val customerName: String? = null,
    val customerSnapshot: CustomerSnapshot? = null,
    val companySnapshot: CompanySnapshot? = null,
    val notes: String? = null,
    val discount: DiscountSpec? = null,
    val discountBeforeTax: Boolean = true,
    /** Empty in list projections; populated when loading a single invoice. */
    val items: List<InvoiceItemRecord> = emptyList(),
    val subtotalPaise: Long = 0,
    val discountTotalPaise: Long = 0,
    val taxTotalPaise: Long = 0,
    val grandTotalPaise: Long = 0,
    val pdfPath: String? = null,
    val deletedAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    val pendingSync: Boolean = false,
    /** Server-reported sync conflict (e.g. duplicate invoice number), shown to the user. */
    val syncError: String? = null,
    /** Template colour tokens the user has changed: token name → ARGB. */
    val themeOverrides: Map<String, Long> = emptyMap(),
    /** Template sections the user has switched off, by section id. */
    val hiddenSections: Set<String> = emptySet(),
)

/**
 * A reusable product or service, built from the line items actually invoiced and offered back
 * when adding the next one. [nameKey] is the normalised name used to recognise a repeat.
 */
data class ProductRecord(
    val id: String,
    val name: String,
    val hsnSac: String? = null,
    val unitPricePaise: Long = 0,
    val taxRatePercent: String = "18",
    val unit: String? = null,
    val usageCount: Long = 1,
    val lastUsedAtMillis: Long = 0,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long = 0,
    val pendingSync: Boolean = false,
) {
    companion object {
        /** Case- and whitespace-insensitive identity, so "Logo design " repeats "logo  design". */
        fun nameKeyOf(name: String): String =
            name.trim().lowercase().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}

/** What the user edits in the create/edit flow — totals are computed, never entered. */
data class InvoiceDraft(
    val id: String? = null,
    val invoiceNumber: String,
    val invoiceDateMillis: Long,
    val dueDateMillis: Long? = null,
    val currency: String = "INR",
    val status: InvoiceDocStatus = InvoiceDocStatus.Draft,
    val templateId: String? = null,
    val templateVersion: Long? = null,
    val customerId: String? = null,
    val notes: String? = null,
    val discountType: DiscountType? = null,
    val discountValue: String? = null,
    val discountBeforeTax: Boolean = true,
    val items: List<DraftItem> = emptyList(),
) {
    data class DraftItem(
        val description: String,
        val hsnSac: String? = null,
        val quantity: String = "1",
        val unitPricePaise: Long = 0,
        val taxRatePercent: String = "18",
    )

    val discount: DiscountSpec?
        get() = if (discountType != null && !discountValue.isNullOrBlank()) {
            DiscountSpec(discountType, discountValue)
        } else null
}

data class TemplateInfo(
    val id: String,
    val name: String,
    val category: String? = null,
    val thumbnailUrl: String? = null,
    val isPremium: Boolean = false,
    val currentVersion: Long = 1,
    val checksum: String = "",
)

/** A cached compiled render tree (Billanta Template JSON) — immutable per (templateId, version). */
data class CompiledTemplate(
    val templateId: String,
    val version: Long,
    val checksum: String,
    /** The raw Template JSON document; the renderer parses it. */
    val json: String,
)
