package com.ferbotz.billanta.render

import com.ferbotz.billanta.core.InvoiceDateFormat
import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.model.formatPaise

/** Typed leaf values so format hints (`currency`, `date`) can do the right thing. */
data class MoneyVal(val paise: Long)
data class DateVal(val millis: Long)

/**
 * Resolves the template binding namespace against ONE invoice. All data comes from the invoice
 * row and its frozen company/customer snapshots — never live records — so a rendered invoice
 * looks identical forever.
 */
class BindingContext(
    private val root: Map<String, Any?>,
    private val currency: String,
    /** How `format: "date"` bindings are written. Passed in, so one invoice renders one way. */
    private val dateFormat: InvoiceDateFormat = InvoiceDateFormat.Default,
) {

    fun resolveRaw(path: String, aliases: Map<String, Any?> = emptyMap()): Any? {
        val parts = path.split('.')
        if (parts.isEmpty()) return null
        var current: Any? = if (aliases.containsKey(parts.first())) {
            aliases[parts.first()]
        } else {
            root[parts.first()]
        }
        for (part in parts.drop(1)) {
            current = (current as? Map<*, *>)?.get(part) ?: return null
        }
        return current
    }

    /** Formats a binding for display, falling back to the template's literal fallback. */
    fun resolveText(bind: TValue.Bind, aliases: Map<String, Any?> = emptyMap()): String {
        val value = resolveRaw(bind.path, aliases)
        val formatted = when (value) {
            null -> ""
            is MoneyVal -> when (bind.format) {
                "currency" -> formatCurrency(value.paise)
                else -> value.paise.toString()
            }
            is DateVal -> when (bind.format) {
                "date" -> dateFormat.format(value.millis)
                else -> Iso8601.formatDate(value.millis)
            }
            is String -> when (bind.format) {
                "number" -> trimNumber(value)
                else -> value
            }
            is Boolean -> if (value) "true" else ""
            else -> value.toString()
        }
        return formatted.ifBlank { bind.fallback }
    }

    /**
     * JS-flavoured truthiness for `conditional` gates, with one useful divergence: money values
     * are truthy by AMOUNT (so `data-if="invoice.discount"` hides a zero-discount row).
     */
    fun isTruthy(path: String, aliases: Map<String, Any?> = emptyMap()): Boolean =
        when (val value = resolveRaw(path, aliases)) {
            null -> false
            is String -> value.isNotEmpty()
            is Boolean -> value
            is MoneyVal -> value.paise != 0L
            is List<*> -> value.isNotEmpty()
            else -> true
        }

    private fun formatCurrency(paise: Long): String =
        if (currency.equals("INR", ignoreCase = true) || currency.isBlank()) {
            paise.formatPaise()
        } else {
            "$currency ${paise.formatPaise(withSymbol = false)}"
        }

    /** `"2.50"` → `"2.5"`, `"2.0"` → `"2"` — quantities read naturally. */
    private fun trimNumber(text: String): String =
        if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/** Builds the full binding namespace (TEMPLATE_JSON.md) for one invoice. */
fun bindingDataFor(record: InvoiceRecord): Map<String, Any?> {
    val company = record.companySnapshot
    val customer = record.customerSnapshot
    return mapOf(
        "company" to mapOf(
            "name" to company?.name,
            "gstin" to company?.gstin,
            "addressLine1" to company?.addressLine1,
            "addressLine2" to company?.addressLine2,
            "city" to company?.city,
            "state" to company?.state,
            "stateCode" to company?.stateCode,
            "pincode" to company?.pincode,
            "country" to company?.country,
            "phone" to company?.phone,
            "email" to company?.email,
            "logo" to company?.logo,
            "signature" to company?.signature,
            "upiId" to company?.upiId,
            "qr" to company?.qr,
            "bankName" to company?.bankName,
            "accountNumber" to company?.accountNumber,
            "ifsc" to company?.ifsc,
        ),
        "customer" to mapOf(
            "name" to customer?.name,
            "gstin" to customer?.gstin,
            "phone" to customer?.phone,
            "email" to customer?.email,
            "addressLine1" to customer?.addressLine1,
            "addressLine2" to customer?.addressLine2,
            "city" to customer?.city,
            "state" to customer?.state,
            "stateCode" to customer?.stateCode,
            "pincode" to customer?.pincode,
        ),
        "invoice" to mapOf(
            "number" to record.invoiceNumber,
            "date" to DateVal(record.invoiceDateMillis),
            "dueDate" to record.dueDateMillis?.let { DateVal(it) },
            "currency" to record.currency,
            "subtotal" to MoneyVal(record.subtotalPaise),
            "tax" to MoneyVal(record.taxTotalPaise),
            "discount" to MoneyVal(record.discountTotalPaise),
            "total" to MoneyVal(record.grandTotalPaise),
            "notes" to record.notes,
            "status" to record.status.name,
        ),
        "items" to record.items.map { item ->
            mapOf(
                "description" to item.description,
                "hsnSac" to item.hsnSac,
                "quantity" to item.quantity,
                "unitPrice" to MoneyVal(item.unitPricePaise),
                "taxRate" to item.taxRatePercent,
                "amount" to MoneyVal(item.lineTotalPaise),
            )
        },
        "payment" to mapOf(
            "upi" to company?.upiId,
            "qr" to company?.qr,
            "bankName" to company?.bankName,
            "accountNumber" to company?.accountNumber,
            "ifsc" to company?.ifsc,
        ),
        "signature" to mapOf(
            "url" to company?.signature,
        ),
    )
}
