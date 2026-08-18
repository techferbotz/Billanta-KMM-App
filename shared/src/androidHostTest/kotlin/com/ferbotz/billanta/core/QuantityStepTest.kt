package com.ferbotz.billanta.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The +/- buttons on a line item step this. Quantities stay exact decimal strings all the way to
 * the server, so stepping must not introduce a float, drift a fraction, or leave a zero-quantity
 * line on the invoice.
 */
class QuantityStepTest {

    private fun step(qty: String, delta: Int): String? =
        DecimalString.parse(qty).plusWhole(delta)?.toString()

    @Test
    fun whole_quantities_step_by_one() {
        assertEquals("2", step("1", +1))
        assertEquals("1", step("2", -1))
        assertEquals("100", step("99", +1))
    }

    @Test
    fun a_fraction_is_preserved_exactly() {
        // 2.5 + 1 must be 3.5 — not 3.4999999, and not 3.
        assertEquals("3.5", step("2.5", +1))
        assertEquals("1.5", step("2.5", -1))
        assertEquals("1.25", step("0.25", +1))
        assertEquals("0.001", step("1.001", -1))
    }

    @Test
    fun stepping_the_last_one_off_reports_gone_rather_than_zero() {
        // The caller removes the line on null; a "0 x rate" row would be nonsense on an invoice.
        assertNull(step("1", -1), "1 - 1 should mean the line is gone")
        assertNull(step("0.5", -1), "dropping below zero should mean gone, not negative")
        assertNull(step("2", -5), "a larger step down should still just mean gone")
    }

    @Test
    fun the_wire_form_carries_no_trailing_noise() {
        // "3.50" and "3" are the same number but different strings, and the server sees the string.
        assertEquals("3", DecimalString.parse("3.00").plusWhole(0)?.toString())
        assertEquals("4", step("3.0", +1))
        assertEquals("2.5", DecimalString.parse("2.50").toString())
        assertEquals("0", DecimalString.parse("0").toString())
    }

    @Test
    fun a_stepped_quantity_is_still_a_valid_quantity() {
        listOf("1", "2.5", "0.125", "10").forEach { start ->
            val up = step(start, +1)!!
            assertEquals(
                up,
                DecimalString.parse(up).toString(),
                "$start stepped to $up, which does not round-trip through the parser",
            )
        }
    }
}
