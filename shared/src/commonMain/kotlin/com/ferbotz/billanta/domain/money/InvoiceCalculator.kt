package com.ferbotz.billanta.domain.money

import com.ferbotz.billanta.core.BigMath
import com.ferbotz.billanta.core.DecimalString

/** Wire names match the API exactly (`discountType: "Flat" | "Percentage"`). */
enum class DiscountType { Flat, Percentage }

/** `value` is a decimal string: a percent for [DiscountType.Percentage], paise for [DiscountType.Flat]. */
data class DiscountSpec(val type: DiscountType, val value: String)

/** One line as the user entered it — quantity/rate as decimal strings, price in integer paise. */
data class CalcLine(
    val quantity: String,
    val unitPricePaise: Long,
    val taxRatePercent: String,
)

data class CalcLineResult(
    /** quantity × unitPrice, pre-discount pre-tax (stored per InvoiceItem). */
    val lineTotal: Long,
    /** This line's apportioned share of the discount (display only, never stored). */
    val lineDiscount: Long,
    val taxAmount: Long,
)

data class InvoiceTotals(
    val subtotal: Long,
    val discountTotal: Long,
    val taxTotal: Long,
    val grandTotal: Long,
    val lines: List<CalcLineResult>,
)

data class GstSplit(
    val intraState: Boolean,
    val cgst: Long,
    val sgst: Long,
    val igst: Long,
)

class MoneyValidationException(message: String) : IllegalArgumentException(message)

/**
 * The exact MONEY.md algorithm — must stay byte-for-byte identical to the server's
 * `src/common/money.ts` so offline totals match what the server recomputes and stores.
 *
 * All money is integer paise; every rounding is HALF-UP (ties away from zero) on an exact
 * rational, via [BigMath.mulDivHalfUp].
 */
object InvoiceCalculator {

    fun compute(
        items: List<CalcLine>,
        discount: DiscountSpec?,
        discountBeforeTax: Boolean,
    ): InvoiceTotals {
        // ---- Parse & validate inputs (mirrors the server's rejections) -------------------------
        val quantities = items.map { DecimalString.parse(it.quantity, "quantity") }
        val rates = items.map { parseTaxRate(it.taxRatePercent) }
        items.forEach {
            if (it.unitPricePaise < 0) throw MoneyValidationException("unit price cannot be negative")
            checkSafe(it.unitPricePaise, "unit price")
        }

        // ---- Step 1: line amounts --------------------------------------------------------------
        val lineTotals = items.mapIndexed { i, item ->
            val q = quantities[i]
            val lineTotal = BigMath.mulDivHalfUp(q.unscaled, item.unitPricePaise, q.scaleDivisor)
            checkSafe(lineTotal, "line total")
            lineTotal
        }
        var subtotal = 0L
        for (t in lineTotals) {
            subtotal += t
            checkSafe(subtotal, "subtotal")
        }

        return if (discountBeforeTax) {
            computeDiscountBeforeTax(lineTotals, rates, subtotal, discount)
        } else {
            computeDiscountAfterTax(lineTotals, rates, subtotal, discount)
        }
    }

    /** Mode A — GST-correct: discount off the subtotal, apportioned, tax on the discounted value. */
    private fun computeDiscountBeforeTax(
        lineTotals: List<Long>,
        rates: List<DecimalString>,
        subtotal: Long,
        discount: DiscountSpec?,
    ): InvoiceTotals {
        val discountTotal = discountAmount(discount, base = subtotal)

        // Apportion pro-rata by lineTotal with cumulative largest-remainder: the pieces sum
        // exactly to discountTotal and each stays within [0, lineTotal].
        val lineDiscounts = LongArray(lineTotals.size)
        if (discountTotal > 0L && subtotal > 0L) {
            var cumAmount = 0L
            var prevCum = 0L
            for (i in lineTotals.indices) {
                cumAmount += lineTotals[i]
                val cumDiscount = BigMath.mulDivHalfUp(discountTotal, cumAmount, subtotal)
                lineDiscounts[i] = cumDiscount - prevCum
                prevCum = cumDiscount
            }
        }

        var taxTotal = 0L
        val lines = lineTotals.mapIndexed { i, lineTotal ->
            val taxable = lineTotal - lineDiscounts[i]
            val rate = rates[i]
            val taxAmount = BigMath.mulDivHalfUp(taxable, rate.unscaled, 100L * rate.scaleDivisor)
            taxTotal += taxAmount
            checkSafe(taxTotal, "tax total")
            CalcLineResult(lineTotal = lineTotal, lineDiscount = lineDiscounts[i], taxAmount = taxAmount)
        }

        val grandTotal = subtotal - discountTotal + taxTotal
        checkSafe(grandTotal, "grand total")
        return InvoiceTotals(subtotal, discountTotal, taxTotal, grandTotal, lines)
    }

    /** Mode B — tax on full lines, then the discount comes off `subtotal + taxTotal`. */
    private fun computeDiscountAfterTax(
        lineTotals: List<Long>,
        rates: List<DecimalString>,
        subtotal: Long,
        discount: DiscountSpec?,
    ): InvoiceTotals {
        var taxTotal = 0L
        val lines = lineTotals.mapIndexed { i, lineTotal ->
            val rate = rates[i]
            val taxAmount = BigMath.mulDivHalfUp(lineTotal, rate.unscaled, 100L * rate.scaleDivisor)
            taxTotal += taxAmount
            checkSafe(taxTotal, "tax total")
            CalcLineResult(lineTotal = lineTotal, lineDiscount = 0L, taxAmount = taxAmount)
        }

        val discountTotal = discountAmount(discount, base = subtotal + taxTotal)
        val grandTotal = subtotal + taxTotal - discountTotal
        checkSafe(grandTotal, "grand total")
        return InvoiceTotals(subtotal, discountTotal, taxTotal, grandTotal, lines)
    }

    /** Step 2 — the discount against its base, clamped to [0, base]. */
    private fun discountAmount(discount: DiscountSpec?, base: Long): Long {
        if (discount == null) return 0L
        val value = DecimalString.parse(discount.value, "discount value")
        val raw = when (discount.type) {
            DiscountType.Percentage -> BigMath.mulDivHalfUp(base, value.unscaled, 100L * value.scaleDivisor)
            DiscountType.Flat -> value.toLongHalfUp()
        }
        return raw.coerceIn(0L, base)
    }

    /**
     * Presentation-only GST split. Same 2-digit state on both snapshots → CGST+SGST (odd paise
     * goes to SGST); different or unknown → IGST. Never stored — derived at display time.
     */
    fun gstSplit(taxTotal: Long, sellerStateCode: String?, buyerStateCode: String?): GstSplit {
        val seller = normalizeStateCode(sellerStateCode)
        val buyer = normalizeStateCode(buyerStateCode)
        val intra = seller != null && buyer != null && seller == buyer
        return if (intra) {
            val cgst = taxTotal / 2
            GstSplit(intraState = true, cgst = cgst, sgst = taxTotal - cgst, igst = 0L)
        } else {
            GstSplit(intraState = false, cgst = 0L, sgst = 0L, igst = taxTotal)
        }
    }

    private fun normalizeStateCode(code: String?): String? {
        val t = code?.trim().orEmpty()
        if (t.isEmpty() || t.any { it !in '0'..'9' }) return null
        val significant = t.trimStart('0')
        if (significant.isEmpty() || significant.length > 2) return null
        return significant.padStart(2, '0')
    }

    private fun parseTaxRate(text: String): DecimalString {
        val rate = DecimalString.parse(text, "tax rate")
        // rate > 100% ⇔ unscaled > 100 × 10^scale
        if (rate.unscaled > 100L * rate.scaleDivisor) throw MoneyValidationException("tax rate must be 0–100")
        return rate
    }

    private fun checkSafe(value: Long, what: String) {
        if (value < -BigMath.MAX_SAFE_PAISE || value > BigMath.MAX_SAFE_PAISE) {
            throw BigMath.MoneyOverflowException("$what exceeds the maximum supported amount")
        }
    }
}
