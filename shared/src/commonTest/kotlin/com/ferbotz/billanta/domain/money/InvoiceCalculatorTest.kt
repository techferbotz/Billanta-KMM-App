package com.ferbotz.billanta.domain.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every worked example from MONEY.md, verbatim — parity with the server is the contract. */
class InvoiceCalculatorTest {

    private fun line(qty: String, unitPrice: Long, rate: String = "18") =
        CalcLine(quantity = qty, unitPricePaise = unitPrice, taxRatePercent = rate)

    @Test
    fun basic_two_units_at_18_percent() {
        val totals = InvoiceCalculator.compute(
            items = listOf(line("2", 1000)),
            discount = null,
            discountBeforeTax = true,
        )
        assertEquals(2000, totals.lines[0].lineTotal)
        assertEquals(360, totals.lines[0].taxAmount)
        assertEquals(2000, totals.subtotal)
        assertEquals(0, totals.discountTotal)
        assertEquals(360, totals.taxTotal)
        assertEquals(2360, totals.grandTotal)
    }

    @Test
    fun before_tax_10_percent_discount() {
        val totals = InvoiceCalculator.compute(
            items = listOf(line("1", 1000), line("1", 4000)),
            discount = DiscountSpec(DiscountType.Percentage, "10"),
            discountBeforeTax = true,
        )
        assertEquals(5000, totals.subtotal)
        assertEquals(500, totals.discountTotal)
        assertEquals(listOf(100L, 400L), totals.lines.map { it.lineDiscount })
        assertEquals(listOf(162L, 648L), totals.lines.map { it.taxAmount })
        assertEquals(810, totals.taxTotal)
        assertEquals(5310, totals.grandTotal)
    }

    @Test
    fun after_tax_10_percent_discount_same_grand_different_tax() {
        val totals = InvoiceCalculator.compute(
            items = listOf(line("1", 1000), line("1", 4000)),
            discount = DiscountSpec(DiscountType.Percentage, "10"),
            discountBeforeTax = false,
        )
        assertEquals(5000, totals.subtotal)
        assertEquals(listOf(180L, 720L), totals.lines.map { it.taxAmount })
        assertEquals(900, totals.taxTotal)
        assertEquals(590, totals.discountTotal) // round(5900 × 10%)
        assertEquals(5310, totals.grandTotal)   // same grand as before-tax, different tax figure
    }

    @Test
    fun half_up_rounding_single_paise() {
        val totals = InvoiceCalculator.compute(
            items = listOf(line("1", 1, rate = "50")),
            discount = null,
            discountBeforeTax = true,
        )
        assertEquals(1, totals.lines[0].lineTotal)
        assertEquals(1, totals.taxTotal) // roundHalfUp(0.5) = 1
        assertEquals(2, totals.grandTotal)
    }

    @Test
    fun apportionment_never_overshoots_any_line() {
        // 4 lines of 1 paise, Flat discount of 2 paise, before tax. Cumulative largest-remainder
        // gives shares 1,0,1,0 — never a negative or an over-full share.
        val totals = InvoiceCalculator.compute(
            items = List(4) { line("1", 1, rate = "100") },
            discount = DiscountSpec(DiscountType.Flat, "2"),
            discountBeforeTax = true,
        )
        assertEquals(4, totals.subtotal)
        assertEquals(2, totals.discountTotal)
        assertEquals(listOf(1L, 0L, 1L, 0L), totals.lines.map { it.lineDiscount })
        // At 100% tax the taxAmount equals the taxable value: 0,1,0,1.
        assertEquals(listOf(0L, 1L, 0L, 1L), totals.lines.map { it.taxAmount })
        assertEquals(2, totals.taxTotal)
        assertEquals(4, totals.grandTotal) // 4 − 2 + 2
    }

    @Test
    fun fractional_quantity() {
        val totals = InvoiceCalculator.compute(
            items = listOf(line("2.5", 1000, rate = "0")),
            discount = null,
            discountBeforeTax = true,
        )
        assertEquals(2500, totals.subtotal)
        assertEquals(0, totals.taxTotal)
    }

    @Test
    fun discount_clamps_to_base() {
        val over = InvoiceCalculator.compute(
            items = listOf(line("1", 1000, rate = "0")),
            discount = DiscountSpec(DiscountType.Flat, "999999"),
            discountBeforeTax = true,
        )
        assertEquals(1000, over.discountTotal)
        assertEquals(0, over.grandTotal)

        val percentOver = InvoiceCalculator.compute(
            items = listOf(line("1", 1000, rate = "0")),
            discount = DiscountSpec(DiscountType.Percentage, "150"),
            discountBeforeTax = true,
        )
        assertEquals(1000, percentOver.discountTotal)
    }

    @Test
    fun empty_invoice_is_all_zero() {
        val totals = InvoiceCalculator.compute(emptyList(), DiscountSpec(DiscountType.Percentage, "10"), true)
        assertEquals(0, totals.subtotal)
        assertEquals(0, totals.discountTotal)
        assertEquals(0, totals.taxTotal)
        assertEquals(0, totals.grandTotal)
    }

    @Test
    fun percentage_discount_with_decimal_value() {
        // 12.5% of 8000 = 1000
        val totals = InvoiceCalculator.compute(
            items = listOf(line("1", 8000, rate = "0")),
            discount = DiscountSpec(DiscountType.Percentage, "12.5"),
            discountBeforeTax = true,
        )
        assertEquals(1000, totals.discountTotal)
    }

    @Test
    fun rejects_bad_inputs() {
        assertFailsWith<IllegalArgumentException> {
            InvoiceCalculator.compute(listOf(line("abc", 100)), null, true)
        }
        assertFailsWith<IllegalArgumentException> {
            InvoiceCalculator.compute(listOf(line("1", -5)), null, true)
        }
        assertFailsWith<IllegalArgumentException> {
            InvoiceCalculator.compute(listOf(line("1", 100, rate = "101")), null, true)
        }
    }

    // ---- GST split -----------------------------------------------------------------------------

    @Test
    fun gst_split_intra_state_odd_paise_goes_to_sgst() {
        val split = InvoiceCalculator.gstSplit(811, "27", "27")
        assertEquals(true, split.intraState)
        assertEquals(405, split.cgst)
        assertEquals(406, split.sgst)
        assertEquals(0, split.igst)
        assertEquals(811, split.cgst + split.sgst)
    }

    @Test
    fun gst_split_even() {
        val split = InvoiceCalculator.gstSplit(810, "27", "27")
        assertEquals(405, split.cgst)
        assertEquals(405, split.sgst)
    }

    @Test
    fun gst_split_inter_state_or_unknown_is_igst() {
        val inter = InvoiceCalculator.gstSplit(810, "27", "29")
        assertEquals(false, inter.intraState)
        assertEquals(810, inter.igst)
        assertEquals(0, inter.cgst)

        val unknown = InvoiceCalculator.gstSplit(810, "27", null)
        assertEquals(false, unknown.intraState)
        assertEquals(810, unknown.igst)

        val blank = InvoiceCalculator.gstSplit(810, "27", "  ")
        assertEquals(false, blank.intraState)
    }

    @Test
    fun gst_split_normalizes_leading_zeros() {
        val split = InvoiceCalculator.gstSplit(100, "07", "7")
        assertEquals(true, split.intraState)
    }
}
