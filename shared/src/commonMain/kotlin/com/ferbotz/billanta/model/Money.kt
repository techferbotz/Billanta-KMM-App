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

/** GST is expressed in basis points (e.g. 18% == 1800) so the split stays exact. */
fun Paise.percent(basisPoints: Int): Paise = Paise((value * basisPoints) / 10_000)
