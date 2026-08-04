package com.ferbotz.billanta.model

enum class InvoiceStatus(val label: String) {
    DRAFT("Draft"),
    PENDING("Pending"),
    PAID("Paid"),
}

enum class InvoiceFilter(val label: String) {
    ALL("All"),
    DRAFT("Draft"),
    PENDING("Pending"),
    PAID("Paid"),
}

data class Customer(
    val id: String,
    val name: String,
    val company: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val gstin: String? = null,
    val address: String? = null,
    val stateCode: String = "27", // Maharashtra — drives CGST+SGST vs IGST
) {
    val initials: String
        get() = name.trim().split(" ").filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }
}

data class LineItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val quantity: Int,
    val rate: Paise,          // per-unit
    val gstBasisPoints: Int = 1800, // 18%
) {
    val amount: Paise get() = rate * quantity
}

data class Invoice(
    val id: String,
    val number: String,
    val customer: Customer,
    val issueDate: String,     // pre-formatted "28 Jul 2026" for the prototype
    val dueDate: String,
    val items: List<LineItem>,
    val status: InvoiceStatus,
    val sellerStateCode: String = "27",
    val notes: String? = null,
) {
    val subtotal: Paise get() = items.fold(Paise(0)) { acc, i -> acc + i.amount }

    /** Same-state supply → CGST + SGST (each half of the GST). */
    val sameState: Boolean get() = customer.stateCode == sellerStateCode

    val gstTotal: Paise
        get() = items.fold(Paise(0)) { acc, i -> acc + i.amount.percent(i.gstBasisPoints) }

    val cgst: Paise get() = if (sameState) Paise(gstTotal.value / 2) else Paise(0)
    val sgst: Paise get() = if (sameState) gstTotal - cgst else Paise(0)
    val igst: Paise get() = if (sameState) Paise(0) else gstTotal

    val total: Paise get() = subtotal + gstTotal
}

data class BusinessProfile(
    val name: String,
    val tagline: String?,
    val ownerName: String,
    val email: String,
    val phone: String,
    val gstin: String,
    val address: String,
    val stateCode: String,
    val upiId: String,
    val bankName: String,
    val accountLast4: String,
)

enum class TemplateTier { FREE, PREMIUM }

data class InvoiceTemplate(
    val id: String,
    val name: String,
    val description: String,
    val tier: TemplateTier,
    val built: Boolean, // whether a full A4 render exists (vs. thumbnail only)
)
