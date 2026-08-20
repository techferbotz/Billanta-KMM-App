package com.ferbotz.billanta.ui

import com.ferbotz.billanta.domain.model.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two small rules that are easy to get wrong and annoying to live with: what back does, and what an
 * invoice is called out of the box.
 */
class BackNavigationTest {

    @Test
    fun an_invoice_number_has_no_prefix_until_the_user_asks_for_one() {
        assertEquals("1", UserSettings().formatNextInvoiceNumber(), "a bare running number is the default")
        assertEquals("", UserSettings().invoiceNumberPrefix)
    }

    @Test
    fun a_prefix_the_user_sets_is_still_honoured() {
        val settings = UserSettings(invoiceNumberPrefix = "INV-", nextInvoiceNumber = 42)
        assertEquals("INV-42", settings.formatNextInvoiceNumber())
    }

    @Test
    fun the_running_number_still_advances_without_a_prefix() {
        assertEquals("7", UserSettings(nextInvoiceNumber = 7).formatNextInvoiceNumber())
        assertEquals("108", UserSettings(nextInvoiceNumber = 108).formatNextInvoiceNumber())
    }
}
