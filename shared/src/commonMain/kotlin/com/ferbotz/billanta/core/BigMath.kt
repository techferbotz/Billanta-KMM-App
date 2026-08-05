package com.ferbotz.billanta.core

/**
 * Exact `round(a × b / c)` with HALF-UP (ties away from zero) rounding, using a 128-bit
 * intermediate product so it never loses precision. This is the one primitive the money engine
 * needs: every step in MONEY.md is of that shape once decimal strings are scaled to integers,
 * and `a×b/c` on integers is an exact rational — so rounding it half-up reproduces the server's
 * arbitrary-precision decimal result digit-for-digit.
 */
object BigMath {

    /** JS Number.MAX_SAFE_INTEGER — the server rejects any paise value beyond this. */
    const val MAX_SAFE_PAISE = 9_007_199_254_740_991L

    class MoneyOverflowException(message: String) : ArithmeticException(message)

    /** round-half-away-from-zero of a*b/c. Throws on c == 0 or if the result overflows Long. */
    fun mulDivHalfUp(a: Long, b: Long, c: Long): Long {
        if (c == 0L) throw MoneyOverflowException("division by zero")
        require(a != Long.MIN_VALUE && b != Long.MIN_VALUE && c != Long.MIN_VALUE) { "value out of range" }
        val negative = (a < 0) xor (b < 0) xor (c < 0)
        val ua = if (a < 0) (-a).toULong() else a.toULong()
        val ub = if (b < 0) (-b).toULong() else b.toULong()
        val uc = if (c < 0) (-c).toULong() else c.toULong()

        val (hi, lo) = mul128(ua, ub)
        val (qHi, qLo, rem) = divRem128by64(hi, lo, uc)

        // Half-up: round away from zero when remainder*2 >= divisor. rem < uc <= 2^63-1, no overflow.
        var resHi = qHi
        var resLo = qLo
        if (rem * 2uL >= uc) {
            resLo++
            if (resLo == 0uL) resHi++
        }
        if (resHi != 0uL || resLo > Long.MAX_VALUE.toULong()) {
            throw MoneyOverflowException("money value overflows 64 bits")
        }
        val magnitude = resLo.toLong()
        return if (negative) -magnitude else magnitude
    }

    /** 64×64 → 128-bit unsigned multiply. Returns (hi, lo). */
    internal fun mul128(a: ULong, b: ULong): Pair<ULong, ULong> {
        val aLo = a and 0xFFFF_FFFFuL
        val aHi = a shr 32
        val bLo = b and 0xFFFF_FFFFuL
        val bHi = b shr 32

        val ll = aLo * bLo
        val lh = aLo * bHi
        val hl = aHi * bLo
        val hh = aHi * bHi

        val mid = (ll shr 32) + (lh and 0xFFFF_FFFFuL) + (hl and 0xFFFF_FFFFuL)
        val lo = (mid shl 32) or (ll and 0xFFFF_FFFFuL)
        val hi = hh + (lh shr 32) + (hl shr 32) + (mid shr 32)
        return hi to lo
    }

    /**
     * Unsigned 128 ÷ 64 → (quotientHi, quotientLo, remainder), by binary long division.
     * Requires divisor <= 2^63-1 so the running remainder can never overflow when shifted.
     */
    internal fun divRem128by64(hi: ULong, lo: ULong, divisor: ULong): Triple<ULong, ULong, ULong> {
        if (hi == 0uL) return Triple(0uL, lo / divisor, lo % divisor)
        var rem = 0uL
        var qHi = 0uL
        var qLo = 0uL
        for (i in 127 downTo 0) {
            val bit = if (i >= 64) (hi shr (i - 64)) and 1uL else (lo shr i) and 1uL
            rem = (rem shl 1) or bit
            val take = rem >= divisor
            if (take) rem -= divisor
            if (i >= 64) {
                if (take) qHi = qHi or (1uL shl (i - 64))
            } else {
                if (take) qLo = qLo or (1uL shl i)
            }
        }
        return Triple(qHi, qLo, rem)
    }
}
