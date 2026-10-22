package com.ferbotz.billanta.ui.fields

import com.ferbotz.billanta.fields.FieldType
import com.ferbotz.billanta.fields.FormatChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules behind the inputs: money in rupees, decimals constrained, formats only warned about. */
class FieldInputRulesTest {

    @Test
    fun money_is_typed_in_rupees_and_stored_in_paise() {
        assertEquals(150_050L, rupeesToPaise("1500.5"))
        assertEquals(150_000L, rupeesToPaise("1500"))
        assertEquals(5L, rupeesToPaise("0.05"))
        assertEquals("1500.50", rupeesText("150050"))
        assertEquals("1500", rupeesText("150000"))
        assertEquals("0.05", rupeesText("5"))
        assertEquals("", rupeesText("not paise"))
    }

    @Test
    fun decimals_are_constrained_while_typing() {
        assertTrue(acceptsDecimal("", decimals = 2, max = null))
        assertTrue(acceptsDecimal("18.", decimals = 2, max = 100.0))
        assertTrue(acceptsDecimal("100", decimals = 2, max = 100.0))
        assertFalse(acceptsDecimal("100.01", decimals = 2, max = 100.0), "a tax rate over 100 is refused by the server")
        assertFalse(acceptsDecimal("2.555", decimals = 2, max = null))
        assertFalse(acceptsDecimal("-1", decimals = 2, max = null))
        assertFalse(acceptsDecimal("1.2.3", decimals = 2, max = null))
        assertFalse(acceptsDecimal("7.5", decimals = 0, max = null), "a whole-number field takes no dot")
    }

    @Test
    fun formats_only_ever_warn() {
        assertNull(FormatChecks.warningFor(FieldType.Gstin, "27AAPFU0939F1ZV"))
        assertNotNull(FormatChecks.warningFor(FieldType.Gstin, "29ABCDE1234F1Z5"), "a wrong check digit is pointed out")
        assertNotNull(FormatChecks.warningFor(FieldType.Gstin, "29ABC"))
        assertNull(FormatChecks.warningFor(FieldType.Pan, "abcde1234f"))
        assertNull(FormatChecks.warningFor(FieldType.Ifsc, "HDFC0001234"))
        assertNotNull(FormatChecks.warningFor(FieldType.Ifsc, "HDFC1001234"))
        assertNull(FormatChecks.warningFor(FieldType.Pincode, "560038"))
        assertNotNull(FormatChecks.warningFor(FieldType.Pincode, "056003"))
        assertNull(FormatChecks.warningFor(FieldType.Email, "a@b.co"))
        assertNull(FormatChecks.warningFor(FieldType.Phone, "+91 98450 00042"))
        assertNull(FormatChecks.warningFor(FieldType.UpiId, "kavericraft@okhdfc"))
        assertNull(FormatChecks.warningFor(FieldType.HsnSac, "998391"))
        // Empty is never a problem: nothing is required.
        assertNull(FormatChecks.warningFor(FieldType.Gstin, ""))
        // A type with no format has nothing to warn about.
        assertNull(FormatChecks.warningFor(FieldType.Text, "anything"))
    }
}
