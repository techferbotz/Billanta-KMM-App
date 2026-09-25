package com.ferbotz.billanta.model

import com.ferbotz.billanta.core.Iso8601
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the list cards print: amounts, dates, and what a customer or product is. */
class ListFormattingTest {

    @Test
    fun whole_rupee_amounts_drop_the_paise_and_real_paise_stay() {
        assertEquals("₹2,36,000", 23_600_000L.formatPaiseTrimmed())
        assertEquals("₹18,880.50", 1_888_050L.formatPaiseTrimmed())
        assertEquals("₹0", 0L.formatPaiseTrimmed())
    }

    @Test
    fun compact_amounts_use_lakhs_and_crores() {
        assertEquals("₹37,760", 3_776_000L.formatPaiseCompact())
        assertEquals("₹1 L", 10_000_000L.formatPaiseCompact())
        assertEquals("₹4.56 L", 45_600_000L.formatPaiseCompact())
        assertEquals("₹12.5 L", 125_000_000L.formatPaiseCompact())
        assertEquals("₹1.2 Cr", 1_200_000_000L.formatPaiseCompact())
        // Just under a crore rounds to a crore, never to "100 L".
        assertEquals("₹1 Cr", 999_999_900L.formatPaiseCompact())
        assertEquals("-₹4.56 L", (-45_600_000L).formatPaiseCompact())
    }

    @Test
    fun list_dates_are_short_and_month_headings_are_long() {
        val day = Iso8601.epochMillisFor(2026, 9, 24)
        assertEquals("24 Sep", Iso8601.formatDayMonth(day))
        assertEquals("September 2026", Iso8601.formatMonthYear(day))
    }

    @Test
    fun a_sac_code_is_a_service_and_any_other_code_is_goods() {
        assertEquals(ProductKind.Service, ProductKind.of("998391"))
        assertEquals(ProductKind.Service, ProductKind.of("9983"))
        assertEquals(ProductKind.Goods, ProductKind.of("4911"))
        assertEquals(ProductKind.Goods, ProductKind.of(" 4819 10 "))
        assertEquals(ProductKind.Unspecified, ProductKind.of(null))
        assertEquals(ProductKind.Unspecified, ProductKind.of(" "))
    }

    @Test
    fun same_state_is_only_claimed_when_both_states_are_known() {
        assertEquals(true, isSameState("27", "27"))
        assertEquals(false, isSameState("27", "32"))
        assertNull(isSameState(null, "27"))
        assertNull(isSameState("27", " "))
    }
}
