package com.ferbotz.billanta.core

/**
 * Minimal, dependency-free UTC date handling.
 *
 * The wire format is ISO-8601 instants (`2026-07-25T00:00:00.000Z`); the DB stores epoch millis.
 * Invoice/due dates are date-only values pinned to UTC midnight, so all civil-calendar math here is
 * UTC — deterministic on both platforms. Conversions use Howard Hinnant's days-from-civil algorithm.
 */
object Iso8601 {

    private const val MILLIS_PER_DAY = 86_400_000L

    /** days since 1970-01-01 for a proleptic-Gregorian civil date. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                     // [0, 399]
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1  // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy             // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /** Inverse of [daysFromCivil]. Returns (year, month, day). */
    fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097                                  // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)           // [0, 365]
        val mp = (5 * doy + 2) / 153                                // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()              // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()           // [1, 12]
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    fun epochMillisFor(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        daysFromCivil(year, month, day) * MILLIS_PER_DAY +
            hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis

    /** Formats as `yyyy-MM-ddTHH:mm:ss.SSSZ` (always UTC, always with millis). */
    fun format(epochMillis: Long): String {
        var days = epochMillis / MILLIS_PER_DAY
        var msOfDay = epochMillis % MILLIS_PER_DAY
        if (msOfDay < 0) { msOfDay += MILLIS_PER_DAY; days -= 1 }
        val (y, mo, d) = civilFromDays(days)
        val h = (msOfDay / 3_600_000L).toInt()
        val mi = (msOfDay / 60_000L % 60).toInt()
        val s = (msOfDay / 1_000L % 60).toInt()
        val ms = (msOfDay % 1_000L).toInt()
        return buildString {
            append(y.toString().padStart(4, '0')); append('-')
            append(mo.toString().padStart(2, '0')); append('-')
            append(d.toString().padStart(2, '0')); append('T')
            append(h.toString().padStart(2, '0')); append(':')
            append(mi.toString().padStart(2, '0')); append(':')
            append(s.toString().padStart(2, '0')); append('.')
            append(ms.toString().padStart(3, '0')); append('Z')
        }
    }

    /** Formats the date part only: `yyyy-MM-dd`. */
    fun formatDate(epochMillis: Long): String = format(epochMillis).substringBefore('T')

    /**
     * Parses `yyyy-MM-dd`, or a full instant `yyyy-MM-ddTHH:mm:ss[.fff](Z|±HH:mm|±HHmm)`.
     * Returns null on malformed input.
     */
    fun parseOrNull(text: String): Long? {
        val t = text.trim()
        if (t.length < 10) return null
        val year = t.substring(0, 4).toIntOrNull() ?: return null
        if (t[4] != '-' || t[7] != '-') return null
        val month = t.substring(5, 7).toIntOrNull() ?: return null
        val day = t.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        if (t.length == 10) return epochMillisFor(year, month, day)
        if (t[10] != 'T' && t[10] != 't' && t[10] != ' ') return null
        var rest = t.substring(11)

        // Split off the zone suffix.
        var offsetMillis = 0L
        when {
            rest.endsWith("Z") || rest.endsWith("z") -> rest = rest.dropLast(1)
            else -> {
                val signIdx = rest.indexOfLast { it == '+' || it == '-' }
                if (signIdx > 0) {
                    val zone = rest.substring(signIdx)
                    rest = rest.substring(0, signIdx)
                    val sign = if (zone[0] == '-') -1 else 1
                    val hhmm = zone.drop(1).replace(":", "")
                    if (hhmm.length != 4) return null
                    val zh = hhmm.substring(0, 2).toIntOrNull() ?: return null
                    val zm = hhmm.substring(2, 4).toIntOrNull() ?: return null
                    offsetMillis = sign * (zh * 3_600_000L + zm * 60_000L)
                }
            }
        }

        var fraction = 0
        val dotIdx = rest.indexOf('.')
        if (dotIdx >= 0) {
            val frac = rest.substring(dotIdx + 1)
            if (frac.isEmpty() || frac.any { it !in '0'..'9' }) return null
            fraction = frac.take(3).padEnd(3, '0').toInt()
            rest = rest.substring(0, dotIdx)
        }

        val parts = rest.split(':')
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) (parts[2].toIntOrNull() ?: return null) else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        return epochMillisFor(year, month, day, hour, minute, second, fraction) - offsetMillis
    }

    private val MONTHS_SHORT =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Calendar date of an instant, in UTC. Floors rather than truncates, so pre-1970 works. */
    fun civilFromUtcMillis(epochMillis: Long): Triple<Int, Int, Int> {
        var days = epochMillis / MILLIS_PER_DAY
        if (epochMillis % MILLIS_PER_DAY < 0) days -= 1
        return civilFromDays(days)
    }

    /** `28 Jul 2026` — the display format the UI already uses. */
    fun formatDisplayDate(epochMillis: Long): String {
        val (y, m, d) = civilFromUtcMillis(epochMillis)
        return "$d ${MONTHS_SHORT[m - 1]} $y"
    }

    /** UTC month bounds around an instant: [startInclusive, endExclusive). */
    fun monthRange(epochMillis: Long): LongRange {
        var days = epochMillis / MILLIS_PER_DAY
        if (epochMillis % MILLIS_PER_DAY < 0) days -= 1
        val (y, m, _) = civilFromDays(days)
        val start = epochMillisFor(y, m, 1)
        val end = if (m == 12) epochMillisFor(y + 1, 1, 1) else epochMillisFor(y, m + 1, 1)
        return start until end
    }
}
