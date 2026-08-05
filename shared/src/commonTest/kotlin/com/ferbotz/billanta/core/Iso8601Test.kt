package com.ferbotz.billanta.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Iso8601Test {

    @Test
    fun epoch_zero() {
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0))
        assertEquals(0L, Iso8601.parseOrNull("1970-01-01"))
        assertEquals(86_400_000L, Iso8601.parseOrNull("1970-01-02"))
    }

    @Test
    fun round_trip() {
        val millis = Iso8601.epochMillisFor(2026, 7, 25, 13, 45, 12, 345)
        assertEquals(millis, Iso8601.parseOrNull(Iso8601.format(millis)))
        assertEquals("2026-07-25T13:45:12.345Z", Iso8601.format(millis))
    }

    @Test
    fun parses_server_variants() {
        val midnight = Iso8601.epochMillisFor(2026, 7, 25)
        assertEquals(midnight, Iso8601.parseOrNull("2026-07-25T00:00:00Z"))
        assertEquals(midnight, Iso8601.parseOrNull("2026-07-25T00:00:00.000Z"))
        assertEquals(midnight, Iso8601.parseOrNull("2026-07-25"))
        // +05:30 offset: 05:30 IST == 00:00 UTC
        assertEquals(midnight, Iso8601.parseOrNull("2026-07-25T05:30:00+05:30"))
        assertNull(Iso8601.parseOrNull("garbage"))
        assertNull(Iso8601.parseOrNull("2026-13-01"))
    }

    @Test
    fun leap_years() {
        val feb29 = Iso8601.parseOrNull("2024-02-29")!!
        assertEquals("2024-02-29", Iso8601.formatDate(feb29))
        assertEquals("2024-03-01", Iso8601.formatDate(feb29 + 86_400_000L))
    }

    @Test
    fun display_format_matches_ui() {
        assertEquals("28 Jul 2026", Iso8601.formatDisplayDate(Iso8601.epochMillisFor(2026, 7, 28)))
        assertEquals("4 Aug 2026", Iso8601.formatDisplayDate(Iso8601.epochMillisFor(2026, 8, 4)))
    }

    @Test
    fun month_range() {
        val range = Iso8601.monthRange(Iso8601.epochMillisFor(2026, 7, 28, 10, 0))
        assertEquals(Iso8601.epochMillisFor(2026, 7, 1), range.first)
        assertEquals(Iso8601.epochMillisFor(2026, 8, 1) - 1, range.last)
        // December rolls into the next year.
        val dec = Iso8601.monthRange(Iso8601.epochMillisFor(2025, 12, 31))
        assertEquals(Iso8601.epochMillisFor(2026, 1, 1) - 1, dec.last)
    }
}
