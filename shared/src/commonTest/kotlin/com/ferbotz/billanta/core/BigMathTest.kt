package com.ferbotz.billanta.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BigMathTest {

    @Test
    fun exact_division() {
        assertEquals(50, BigMath.mulDivHalfUp(100, 1, 2))
        assertEquals(360, BigMath.mulDivHalfUp(2000, 18, 100))
    }

    @Test
    fun half_up_ties_round_away_from_zero() {
        assertEquals(3, BigMath.mulDivHalfUp(5, 1, 2))    // 2.5 → 3
        assertEquals(2, BigMath.mulDivHalfUp(3, 1, 2))    // 1.5 → 2
        assertEquals(1, BigMath.mulDivHalfUp(1, 50, 100)) // 0.5 → 1 (MONEY.md half-up example)
        assertEquals(0, BigMath.mulDivHalfUp(49, 1, 100)) // 0.49 → 0
        assertEquals(-3, BigMath.mulDivHalfUp(-5, 1, 2))  // −2.5 → −3 (away from zero)
        assertEquals(-2, BigMath.mulDivHalfUp(-3, 1, 2))
    }

    @Test
    fun products_beyond_64_bits_stay_exact() {
        // (3e9+7) × (4e9+9) = 12,000,000,055,000,000,063 — overflows a signed 64-bit Long.
        assertEquals(12_000_000_055_000_000L, BigMath.mulDivHalfUp(3_000_000_007, 4_000_000_009, 1000))
        // Largest safe paise value times 100% keeps its value.
        assertEquals(BigMath.MAX_SAFE_PAISE, BigMath.mulDivHalfUp(BigMath.MAX_SAFE_PAISE, 100, 100))
        // Apportionment shape: discountTotal × cumAmount / subtotal at the safe bound.
        assertEquals(
            BigMath.MAX_SAFE_PAISE / 2,
            BigMath.mulDivHalfUp(BigMath.MAX_SAFE_PAISE / 2, BigMath.MAX_SAFE_PAISE, BigMath.MAX_SAFE_PAISE),
        )
    }

    @Test
    fun overflowing_result_throws() {
        assertFailsWith<BigMath.MoneyOverflowException> {
            BigMath.mulDivHalfUp(Long.MAX_VALUE, Long.MAX_VALUE, 1)
        }
        assertFailsWith<BigMath.MoneyOverflowException> {
            BigMath.mulDivHalfUp(1, 1, 0)
        }
    }

    @Test
    fun mul128_known_values() {
        val (hi, lo) = BigMath.mul128(0xFFFF_FFFF_FFFF_FFFFuL, 0xFFFF_FFFF_FFFF_FFFFuL)
        // (2^64 − 1)^2 = 2^128 − 2^65 + 1 → hi = 2^64 − 2, lo = 1
        assertEquals(0xFFFF_FFFF_FFFF_FFFEuL, hi)
        assertEquals(1uL, lo)

        val (hi2, lo2) = BigMath.mul128(123456789uL, 987654321uL)
        assertEquals(0uL, hi2)
        assertEquals(121932631112635269uL, lo2)
    }

    @Test
    fun divRem128_known_values() {
        // 2^64 / 3 = 6148914691236517205 rem 1
        val (qHi, qLo, r) = BigMath.divRem128by64(1uL, 0uL, 3uL)
        assertEquals(0uL, qHi)
        assertEquals(6148914691236517205uL, qLo)
        assertEquals(1uL, r)
    }
}
