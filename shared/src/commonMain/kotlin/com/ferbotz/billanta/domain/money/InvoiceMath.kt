package com.ferbotz.billanta.domain.money

import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.core.parsePaise
import com.ferbotz.billanta.fields.DiscountValue
import com.ferbotz.billanta.fields.FieldValues

/**
 * MONEY.md read from values maps, the way the server reads them (FIELDS.md §7.7). The calculation
 * itself is [InvoiceCalculator], unchanged; this only says where its inputs live:
 *
 * | input | field id | if absent |
 * | --- | --- | --- |
 * | quantity | line `quantity` | 0 |
 * | unit price | line `unit_price` (paise string) | 0 |
 * | tax rate | line `tax_rate` | 0 |
 * | discount | invoice `discount` | none |
 * | discount before tax | `discount.beforeTax` | true |
 * | seller / buyer state | `company_state` / `customer_state` | IGST |
 *
 * A malformed input reads as absent rather than failing: the inputs are constrained where they are
 * typed, the server rejects a malformed one anyway, and a preview must never die on one bad value.
 */
object InvoiceMath {

    fun compute(values: FieldValues, lines: List<FieldValues>): InvoiceComputed {
        val discount = values.discount()
        val totals = InvoiceCalculator.compute(
            items = lines.map { line ->
                CalcLine(
                    quantity = line.decimal(QUANTITY)?.toString() ?: "0",
                    unitPricePaise = line.string(UNIT_PRICE)?.let(::paiseOrNull) ?: 0L,
                    taxRatePercent = line.decimal(TAX_RATE)?.takeIf { it.unscaled <= 100L * it.scaleDivisor }
                        ?.toString() ?: "0",
                )
            },
            discount = discount?.toSpec(),
            discountBeforeTax = discount?.beforeTax ?: true,
        )
        val split = InvoiceCalculator.gstSplit(totals.taxTotal, values.string(COMPANY_STATE), values.string(CUSTOMER_STATE))
        return InvoiceComputed(
            subtotal = totals.subtotal,
            discountTotal = totals.discountTotal,
            taxTotal = totals.taxTotal,
            cgstTotal = split.cgst,
            sgstTotal = split.sgst,
            igstTotal = split.igst,
            grandTotal = totals.grandTotal,
            lines = totals.lines.map { LineComputed(it.lineTotal, it.taxAmount) },
        )
    }

    /** A discount whose value is not a plain non-negative decimal is no discount. */
    private fun DiscountValue.toSpec(): DiscountSpec? {
        if (DecimalString.parseOrNull(value) == null) return null
        return when (kind) {
            DiscountValue.Kind.Flat -> DiscountSpec(DiscountType.Flat, value)
            DiscountValue.Kind.Percent -> DiscountSpec(DiscountType.Percentage, value)
        }
    }

    private fun paiseOrNull(text: String): Long? = try {
        parsePaise(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    const val QUANTITY = "quantity"
    const val UNIT_PRICE = "unit_price"
    const val TAX_RATE = "tax_rate"
    const val COMPANY_STATE = "company_state"
    const val CUSTOMER_STATE = "customer_state"
}

/** An invoice's computed values (FIELDS.md §3.3), in paise. */
data class InvoiceComputed(
    val subtotal: Long,
    val discountTotal: Long,
    val taxTotal: Long,
    val cgstTotal: Long,
    val sgstTotal: Long,
    val igstTotal: Long,
    val grandTotal: Long,
    val lines: List<LineComputed>,
) {
    /** The invoice's `computed` map as the server sends it: field id → paise string. */
    fun totalsMap(): Map<String, String> = linkedMapOf(
        SUBTOTAL to subtotal.toString(),
        DISCOUNT_TOTAL to discountTotal.toString(),
        TAX_TOTAL to taxTotal.toString(),
        CGST_TOTAL to cgstTotal.toString(),
        SGST_TOTAL to sgstTotal.toString(),
        IGST_TOTAL to igstTotal.toString(),
        GRAND_TOTAL to grandTotal.toString(),
    )

    companion object {
        const val SUBTOTAL = "subtotal"
        const val DISCOUNT_TOTAL = "discount_total"
        const val TAX_TOTAL = "tax_total"
        const val CGST_TOTAL = "cgst_total"
        const val SGST_TOTAL = "sgst_total"
        const val IGST_TOTAL = "igst_total"
        const val GRAND_TOTAL = "grand_total"

        /** Reads a `computed` map back, as stored or as the server sent it; a missing value is 0. */
        fun fromMaps(totals: Map<String, String>, lines: List<Map<String, String>>): InvoiceComputed {
            fun Map<String, String>.paise(id: String) = this[id]?.toLongOrNull() ?: 0L
            return InvoiceComputed(
                subtotal = totals.paise(SUBTOTAL),
                discountTotal = totals.paise(DISCOUNT_TOTAL),
                taxTotal = totals.paise(TAX_TOTAL),
                cgstTotal = totals.paise(CGST_TOTAL),
                sgstTotal = totals.paise(SGST_TOTAL),
                igstTotal = totals.paise(IGST_TOTAL),
                grandTotal = totals.paise(GRAND_TOTAL),
                lines = lines.map { LineComputed(it.paise(LineComputed.LINE_TOTAL), it.paise(LineComputed.TAX_AMOUNT)) },
            )
        }
    }
}

/** One line's computed values: `line_total` (quantity × rate) and `tax_amount`. */
data class LineComputed(val lineTotal: Long, val taxAmount: Long) {

    fun toMap(): Map<String, String> = linkedMapOf(
        LINE_TOTAL to lineTotal.toString(),
        TAX_AMOUNT to taxAmount.toString(),
    )

    companion object {
        const val LINE_TOTAL = "line_total"
        const val TAX_AMOUNT = "tax_amount"
    }
}
