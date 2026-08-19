package com.ferbotz.billanta.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The date the customer reads. Every option has to be unambiguous to *them*, produce the same
 * string on every device, and survive an id it no longer recognises.
 */
class InvoiceDateFormatTest {

    /** 6 August 2026 — a single-digit day, so padding differences actually show. */
    private val sixth = Iso8601.epochMillisFor(2026, 8, 6)

    @Test
    fun each_option_writes_the_date_it_advertises() {
        assertEquals("6 Aug 2026", InvoiceDateFormat.DAY_MONTH_YEAR.format(sixth))
        assertEquals("06 Aug 2026", InvoiceDateFormat.DAY_MONTH_YEAR_PADDED.format(sixth))
        assertEquals("Aug 6, 2026", InvoiceDateFormat.MONTH_DAY_YEAR.format(sixth))
        assertEquals("06/08/2026", InvoiceDateFormat.DAY_FIRST_SLASH.format(sixth))
        assertEquals("08/06/2026", InvoiceDateFormat.MONTH_FIRST_SLASH.format(sixth))
        assertEquals("2026-08-06", InvoiceDateFormat.ISO.format(sixth))
    }

    /** The chips show a sample rather than a name, so the label has to *be* the format. */
    @Test
    fun the_label_matches_what_the_option_produces() {
        val labelDate = Iso8601.epochMillisFor(2026, 8, 6)
        InvoiceDateFormat.entries.forEach { format ->
            assertEquals(
                format.label,
                format.format(labelDate),
                "${format.id} is labelled '${format.label}' but writes '${format.format(labelDate)}'",
            )
        }
    }

    @Test
    fun a_two_digit_day_is_not_padded_twice() {
        val twentysixth = Iso8601.epochMillisFor(2026, 12, 26)
        assertEquals("26 Dec 2026", InvoiceDateFormat.DAY_MONTH_YEAR.format(twentysixth))
        assertEquals("26 Dec 2026", InvoiceDateFormat.DAY_MONTH_YEAR_PADDED.format(twentysixth))
        assertEquals("26/12/2026", InvoiceDateFormat.DAY_FIRST_SLASH.format(twentysixth))
        assertEquals("2026-12-26", InvoiceDateFormat.ISO.format(twentysixth))
    }

    /**
     * A stored id is a persisted preference; dropping an option later must not strand whoever
     * chose it, and a corrupted value must not crash the renderer.
     */
    @Test
    fun an_unknown_id_falls_back_instead_of_failing() {
        assertEquals(InvoiceDateFormat.Default, InvoiceDateFormat.fromId(null))
        assertEquals(InvoiceDateFormat.Default, InvoiceDateFormat.fromId(""))
        assertEquals(InvoiceDateFormat.Default, InvoiceDateFormat.fromId("dd MMMM yyyy 'at' HH:mm"))
    }

    @Test
    fun every_id_round_trips_through_storage() {
        InvoiceDateFormat.entries.forEach {
            assertEquals(it, InvoiceDateFormat.fromId(it.id), "${it.id} did not round-trip")
        }
        assertEquals(
            InvoiceDateFormat.entries.size,
            InvoiceDateFormat.entries.map { it.id }.toSet().size,
            "two options share an id, so one of them can never be restored",
        )
    }

    /** The formatter must not drift a day at the boundaries the invoice date actually lands on. */
    @Test
    fun midnight_and_the_last_millisecond_of_a_day_are_the_same_date() {
        val midnight = Iso8601.epochMillisFor(2026, 3, 1)
        val justBeforeMidnight = midnight + 86_400_000L - 1
        InvoiceDateFormat.entries.forEach {
            assertEquals(
                it.format(midnight),
                it.format(justBeforeMidnight),
                "${it.id} rolled over inside a single day",
            )
        }
    }

    @Test
    fun a_date_before_1970_does_not_wrap() {
        // Negative epoch millis floor rather than truncate, or the day comes out one late.
        val past = Iso8601.epochMillisFor(1969, 12, 31)
        assertEquals("31 Dec 1969", InvoiceDateFormat.DAY_MONTH_YEAR.format(past))
        assertEquals("1969-12-31", InvoiceDateFormat.ISO.format(past))
    }

    @Test
    fun leap_day_survives() {
        val leap = Iso8601.epochMillisFor(2028, 2, 29)
        assertEquals("29 Feb 2028", InvoiceDateFormat.DAY_MONTH_YEAR.format(leap))
        assertEquals("2028-02-29", InvoiceDateFormat.ISO.format(leap))
    }

    @Test
    fun the_options_are_actually_distinguishable() {
        // Six identical-looking choices would be a worse menu than one. On a day where day and
        // month differ, every option must produce a different string.
        val rendered = InvoiceDateFormat.entries.map { it.format(sixth) }
        assertTrue(
            rendered.size == rendered.toSet().size,
            "two formats render identically, so the choice is meaningless: $rendered",
        )
    }
}
