package com.ferbotz.billanta.model

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money is stored as an integer number of paise to keep tax math exact. Formatting uses the Indian
 * grouping system (…,##,##,###) and is implemented by hand so it works identically on Android and
 * iOS without pulling in `java.text`.
 */
@JvmInline
value class Paise(val value: Long)

fun rupees(whole: Long, paise: Long = 0): Paise = Paise(whole * 100 + paise)
fun rupeesOf(amount: Double): Paise = Paise((amount * 100).roundToLong())

private fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val head = digits.substring(0, digits.length - 3)
    val tail = digits.substring(digits.length - 3)
    val sb = StringBuilder()
    // Group the head in 2s from the right.
    var i = head.length
    while (i > 0) {
        val start = maxOf(0, i - 2)
        if (sb.isNotEmpty()) sb.insert(0, ',')
        sb.insert(0, head.substring(start, i))
        i = start
    }
    return "$sb,$tail"
}

/** e.g. 10856000 paise -> "₹1,08,560.00". */
fun Paise.format(withPaise: Boolean = true, withSymbol: Boolean = true): String {
    val negative = value < 0
    val absPaise = abs(value)
    val whole = absPaise / 100
    val frac = absPaise % 100
    val grouped = groupIndian(whole.toString())
    val body = if (withPaise) {
        val f = frac.toString().padStart(2, '0')
        "$grouped.$f"
    } else {
        grouped
    }
    val sign = if (negative) "-" else ""
    val symbol = if (withSymbol) "₹" else ""
    return "$sign$symbol$body"
}

operator fun Paise.plus(other: Paise) = Paise(value + other.value)
operator fun Paise.minus(other: Paise) = Paise(value - other.value)
operator fun Paise.times(qty: Int) = Paise(value * qty)

/** Formats a raw paise Long (the data layer's money representation). */
fun Long.formatPaise(withPaise: Boolean = true, withSymbol: Boolean = true): String =
    Paise(this).format(withPaise = withPaise, withSymbol = withSymbol)

/**
 * Whole rupees when there are no paise — `₹2,36,000` — and exact when there are — `₹18,880.50`.
 * For a list, where ".00" on every amount is noise but a real 50 paise must not be rounded away.
 */
fun Long.formatPaiseTrimmed(): String = formatPaise(withPaise = this % 100 != 0L)

/**
 * Indian compact form for a narrow column: `₹37,760` below a lakh, `₹4.56 L`, `₹1.2 Cr`. Rounded
 * to two decimals of the unit, so never use it where the exact amount matters.
 */
fun Long.formatPaiseCompact(): String {
    val sign = if (this < 0) "-" else ""
    val rupees = abs(this) / 100
    fun scaled(unit: Long): String {
        val hundredths = (rupees * 100 + unit / 2) / unit
        val whole = hundredths / 100
        val frac = hundredths % 100
        return when {
            frac == 0L -> "$whole"
            frac % 10 == 0L -> "$whole.${frac / 10}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }
    return when {
        rupees >= CRORE -> "$sign₹${scaled(CRORE)} Cr"
        // 99,99,999 would round up to "100 L": that is a crore, so say so.
        rupees >= LAKH -> if ((rupees * 100 + LAKH / 2) / LAKH >= 10_000) "$sign₹1 Cr" else "$sign₹${scaled(LAKH)} L"
        else -> formatPaise(withPaise = false)
    }
}

private const val LAKH = 1_00_000L
private const val CRORE = 1_00_00_000L

/** `"1500.5"` (rupees, plain decimal, no grouping) → 150050 paise. Null when malformed. */
fun parseRupeesToPaise(text: String): Long? {
    val parsed = com.ferbotz.billanta.core.DecimalString.parseOrNull(text.trim()) ?: return null
    return try {
        com.ferbotz.billanta.core.BigMath.mulDivHalfUp(parsed.unscaled, 100, parsed.scaleDivisor)
    } catch (_: ArithmeticException) {
        null
    }
}
