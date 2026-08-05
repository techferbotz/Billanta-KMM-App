package com.ferbotz.billanta.model

import com.ferbotz.billanta.domain.model.InvoiceDocStatus

/** The invoice-list filter chips, mapped straight onto the API's status values. */
enum class InvoiceFilter(val label: String, val status: InvoiceDocStatus?) {
    ALL("All", null),
    DRAFT("Draft", InvoiceDocStatus.Draft),
    PENDING("Pending", InvoiceDocStatus.Pending),
    PAID("Paid", InvoiceDocStatus.Paid),
}

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
