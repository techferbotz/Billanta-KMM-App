package com.ferbotz.billanta.state

import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the discount field shows for a discount already on the invoice.
 *
 * A flat discount travels and is stored in paise but is typed in rupees, so this conversion is the
 * one place the two units meet. It matters more now that the section auto-saves: the seeded value is
 * written straight back the moment anything else on the screen changes, so a wrong reading here
 * would not merely look odd — it would multiply the customer's discount by a hundred.
 */
class DiscountInputTest {

    private fun flat(paise: String) = discountValueInput(DiscountSpec(DiscountType.Flat, paise))

    @Test
    fun a_flat_discount_comes_back_in_rupees() {
        assertEquals("500", flat("50000"))
        assertEquals("1", flat("100"))
        assertEquals("12345", flat("1234500"))
    }

    @Test
    fun paise_are_kept_and_padded() {
        assertEquals("500.50", flat("50050"))
        assertEquals("500.05", flat("50005"))
        assertEquals("0.99", flat("99"))
    }

    @Test
    fun a_percentage_is_shown_exactly_as_it_is_stored() {
        // Not a money amount, so there is nothing to convert — "12.5" must stay "12.5".
        assertEquals("12.5", discountValueInput(DiscountSpec(DiscountType.Percentage, "12.5")))
        assertEquals("10", discountValueInput(DiscountSpec(DiscountType.Percentage, "10")))
    }

    @Test
    fun no_discount_leaves_the_field_empty() {
        // An empty field is what tells the write there is no discount, so this must not be "0".
        assertEquals("", discountValueInput(null))
    }
}
