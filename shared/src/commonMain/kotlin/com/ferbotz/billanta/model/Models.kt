package com.ferbotz.billanta.model

fun initialsOf(name: String): String = name.trim()
    .split(" ")
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }

/** The first two GSTIN characters are the registration state code (`27ABCDE…` → `"27"`). */
fun stateCodeFromGstin(gstin: String): String? {
    val t = gstin.trim()
    if (t.length < 2) return null
    val code = t.substring(0, 2)
    return if (code.all { it in '0'..'9' }) code else null
}

/**
 * What a catalogue line is, read from its HSN/SAC code. Services are classified under SAC, which
 * is chapter 99; everything else with a code is goods under HSN.
 */
enum class ProductKind {
    Service, Goods, Unspecified;

    companion object {
        fun of(hsnSac: String?): ProductKind {
            val digits = hsnSac?.filter(Char::isDigit).orEmpty()
            return when {
                digits.isEmpty() -> Unspecified
                digits.startsWith("99") -> Service
                else -> Goods
            }
        }
    }
}

/**
 * Whether a sale stays inside the seller's state (CGST + SGST) or crosses a state line (IGST).
 * Null when either state is unknown — then nothing can honestly be said.
 */
fun isSameState(sellerStateCode: String?, buyerStateCode: String?): Boolean? {
    val seller = sellerStateCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val buyer = buyerStateCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return seller == buyer
}
