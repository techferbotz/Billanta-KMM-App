package com.ferbotz.billanta.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DecimalStringTest {

    @Test
    fun parses_plain_forms() {
        assertEquals(DecimalString(25, 1), DecimalString.parse("2.5"))
        assertEquals(DecimalString(18, 0), DecimalString.parse("18"))
        assertEquals(DecimalString(531000, 0), DecimalString.parse("531000"))
        assertEquals(DecimalString(105, 2), DecimalString.parse("1.05"))
        assertEquals(DecimalString(5, 1), DecimalString.parse(".5"))
        assertEquals(DecimalString(0, 0), DecimalString.parse("0"))
        assertEquals(DecimalString(0, 0), DecimalString.parse("0.000"))
        // Trailing zeros are value-preserving: 2.50 == 2.5
        assertEquals(DecimalString(25, 1), DecimalString.parse("2.50"))
    }

    @Test
    fun rejects_garbage() {
        assertFailsWith<IllegalArgumentException> { DecimalString.parse("") }
        assertFailsWith<IllegalArgumentException> { DecimalString.parse(".") }
        assertFailsWith<IllegalArgumentException> { DecimalString.parse("-5") }
        assertFailsWith<IllegalArgumentException> { DecimalString.parse("1,000") }
        assertFailsWith<IllegalArgumentException> { DecimalString.parse("1e3") }
        assertFailsWith<IllegalArgumentException> { DecimalString.parse("2.123456789") } // > 8 dp
        assertNull(DecimalString.parseOrNull("abc"))
    }

    @Test
    fun paise_parsing_rounds_half_up() {
        assertEquals(531000L, parsePaise("531000"))
        assertEquals(11L, parsePaise("10.5"))
        assertEquals(10L, parsePaise("10.49"))
        assertFailsWith<IllegalArgumentException> { parsePaise("9007199254740992") } // > MAX_SAFE
    }
}
