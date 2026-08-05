package com.ferbotz.billanta.core

/**
 * A parsed non-negative decimal string (`"2.5"`, `"18"`, `"531000"`), kept exact as
 * `unscaled / 10^scale`. Quantities, tax-rate percents and discount values arrive in this form
 * (see MONEY.md); parsing here is strict so the client rejects exactly what the server would.
 */
data class DecimalString(val unscaled: Long, val scale: Int) {

    /** 10^scale — the divisor that turns [unscaled] back into the real value. */
    val scaleDivisor: Long get() = POW10[scale]

    val isZero: Boolean get() = unscaled == 0L

    /** Rounds to a whole integer (HALF-UP) — e.g. a Flat discount amount in paise. */
    fun toLongHalfUp(): Long = BigMath.mulDivHalfUp(unscaled, 1, scaleDivisor)

    companion object {
        private val POW10 = longArrayOf(
            1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000,
        )
        const val MAX_SCALE = 8

        class ParseException(message: String) : IllegalArgumentException(message)

        /**
         * Parses a non-negative decimal with up to [MAX_SCALE] fraction digits.
         * Rejects signs, exponents, group separators, and empty/garbage input.
         */
        fun parse(text: String, what: String = "number"): DecimalString {
            val t = text.trim()
            if (t.isEmpty()) throw ParseException("$what is empty")
            val dot = t.indexOf('.')
            val intPart = if (dot >= 0) t.substring(0, dot) else t
            var fracPart = if (dot >= 0) t.substring(dot + 1) else ""
            if (intPart.isEmpty() && fracPart.isEmpty()) throw ParseException("$what is not a number: \"$text\"")
            if (intPart.any { it !in '0'..'9' } || fracPart.any { it !in '0'..'9' }) {
                throw ParseException("$what is not a plain decimal: \"$text\"")
            }
            // Trailing fraction zeros carry no value — trimming keeps the scale small.
            fracPart = fracPart.trimEnd('0')
            if (fracPart.length > MAX_SCALE) throw ParseException("$what has too many decimal places: \"$text\"")

            val digits = (intPart.trimStart('0') + fracPart)
            if (digits.length > 18) throw ParseException("$what is too large: \"$text\"")
            val unscaled = if (digits.isEmpty()) 0L else digits.toLong()
            return DecimalString(unscaled, fracPart.length)
        }

        fun parseOrNull(text: String): DecimalString? = try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Parses a wire money value — an integer (or decimal) string of paise — to a Long, HALF-UP. */
fun parsePaise(text: String, what: String = "amount"): Long {
    val parsed = DecimalString.parse(text, what)
    val value = parsed.toLongHalfUp()
    if (value > BigMath.MAX_SAFE_PAISE) throw DecimalString.Companion.ParseException("$what exceeds the maximum supported amount")
    return value
}
