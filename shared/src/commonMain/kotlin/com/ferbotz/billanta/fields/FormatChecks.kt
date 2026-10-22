package com.ferbotz.billanta.fields

/**
 * Format warnings for the identity-like types (FIELDS_APP_GUIDE.md §4.2). They only ever *warn*:
 * the server stores whatever was typed (FIELDS.md §7.1), and a user is never blocked from saving or
 * sharing by a check that might be wrong about their real-world number.
 */
object FormatChecks {

    /** A short reason the value looks wrong, or null when it looks fine or is empty. */
    fun warningFor(type: FieldType?, value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when (type) {
            FieldType.Gstin -> when {
                !GSTIN.matches(v.uppercase()) -> "A GSTIN is 15 characters, like 29ABCDE1234F1Z5"
                !gstinChecksumOk(v.uppercase()) -> "This GSTIN's last character doesn't match — check for a typo"
                else -> null
            }
            FieldType.Pan -> if (PAN.matches(v.uppercase())) null else "A PAN is 10 characters, like ABCDE1234F"
            FieldType.Ifsc -> if (IFSC.matches(v.uppercase())) null else "An IFSC is 11 characters, like HDFC0001234"
            FieldType.Pincode -> if (PINCODE.matches(v)) null else "A PIN code is 6 digits"
            FieldType.Email -> if (EMAIL.matches(v)) null else "This doesn't look like an email address"
            FieldType.Phone -> if (v.count { it.isDigit() } in 10..15 && PHONE.matches(v)) null else "This doesn't look like a phone number"
            FieldType.UpiId -> if (UPI.matches(v)) null else "A UPI ID looks like name@bank"
            FieldType.HsnSac -> if (HSN_SAC.matches(v)) null else "HSN/SAC codes are 4 to 8 digits"
            else -> null
        }
    }

    /**
     * The GSTIN check digit: a mod-36 Luhn variant over the first 14 characters, as the GST
     * network computes it.
     */
    fun gstinChecksumOk(gstin: String): Boolean {
        if (gstin.length != 15) return false
        var sum = 0
        for (i in 0 until 14) {
            val value = CHARSET.indexOf(gstin[i]).takeIf { it >= 0 } ?: return false
            val product = value * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        return CHARSET[(36 - sum % 36) % 36] == gstin[14]
    }

    private const val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val GSTIN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")
    private val PAN = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")
    private val IFSC = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
    private val PINCODE = Regex("^[1-9][0-9]{5}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^[+0-9 ()-]+$")
    private val UPI = Regex("^[A-Za-z0-9._-]{2,}@[A-Za-z][A-Za-z0-9]{1,}$")
    private val HSN_SAC = Regex("^[0-9]{4,8}$")
}
