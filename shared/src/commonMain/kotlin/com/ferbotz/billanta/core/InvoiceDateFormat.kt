package com.ferbotz.billanta.core

/**
 * How dates are written on the invoice.
 *
 * A fixed list rather than a free-form pattern: an invoice is read by someone else, often in another
 * country, and `03/04/2026` is genuinely ambiguous — so the choices here are ones a reader can
 * resolve. Formatting is hand-rolled for the same reason the rest of the date code is: there is no
 * `java.time` in common code, and a locale-dependent format would render differently per device
 * from the same stored invoice.
 *
 * [id] is what gets persisted; an id this build does not know falls back to [Default] rather than
 * failing, so removing an option later cannot strand anyone.
 */
enum class InvoiceDateFormat(val id: String, val label: String) {
    DAY_MONTH_YEAR("d-MMM-yyyy", "6 Aug 2026"),
    DAY_MONTH_YEAR_PADDED("dd-MMM-yyyy", "06 Aug 2026"),
    MONTH_DAY_YEAR("MMM-d-yyyy", "Aug 6, 2026"),
    DAY_FIRST_SLASH("dd/MM/yyyy", "06/08/2026"),
    MONTH_FIRST_SLASH("MM/dd/yyyy", "08/06/2026"),
    ISO("yyyy-MM-dd", "2026-08-06"),
    ;

    fun format(epochMillis: Long): String {
        val (y, m, d) = Iso8601.civilFromUtcMillis(epochMillis)
        val month = MONTHS[m - 1]
        return when (this) {
            DAY_MONTH_YEAR -> "$d $month $y"
            DAY_MONTH_YEAR_PADDED -> "${pad(d)} $month $y"
            MONTH_DAY_YEAR -> "$month $d, $y"
            DAY_FIRST_SLASH -> "${pad(d)}/${pad(m)}/$y"
            MONTH_FIRST_SLASH -> "${pad(m)}/${pad(d)}/$y"
            ISO -> "$y-${pad(m)}-${pad(d)}"
        }
    }

    companion object {
        val Default = DAY_MONTH_YEAR

        fun fromId(id: String?): InvoiceDateFormat = entries.firstOrNull { it.id == id } ?: Default

        private val MONTHS =
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        private fun pad(value: Int) = if (value < 10) "0$value" else value.toString()
    }
}
