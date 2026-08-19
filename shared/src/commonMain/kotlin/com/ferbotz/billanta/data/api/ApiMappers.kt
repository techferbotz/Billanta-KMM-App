package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.core.parsePaise
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.ProductRecord
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.model.UserSettings
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import com.ferbotz.billanta.render.TemplateParser
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private fun isoToMillis(iso: String?): Long? = iso?.let { Iso8601.parseOrNull(it) }

/** Server money strings are trusted, but a malformed one must not crash the client. */
private fun paiseOrZero(text: String?): Long = text?.let {
    try {
        parsePaise(it)
    } catch (_: IllegalArgumentException) {
        null
    }
} ?: 0L

// ---- user --------------------------------------------------------------------------------------

fun UserDto.toDomain() = UserAccount(
    id = id, email = email, name = name, photoUrl = photoUrl, isPremium = isPremium,
    createdAtMillis = isoToMillis(createdAt), updatedAtMillis = isoToMillis(updatedAt),
)

// ---- company -----------------------------------------------------------------------------------

fun CompanyDto.toDomain() = CompanyProfile(
    name = name, gstin = gstin, addressLine1 = addressLine1, addressLine2 = addressLine2,
    city = city, state = state, stateCode = stateCode, pincode = pincode, country = country,
    phone = phone, email = email, logo = logo, signature = signature, upiId = upiId, qr = qr,
    bankName = bankName, accountNumber = accountNumber, ifsc = ifsc,
    signatoryName = signatoryName, signatoryDesignation = signatoryDesignation,
)

fun CompanyProfile.toDto() = CompanyDto(
    name = name, gstin = gstin, addressLine1 = addressLine1, addressLine2 = addressLine2,
    city = city, state = state, stateCode = stateCode, pincode = pincode, country = country,
    phone = phone, email = email, logo = logo, signature = signature, upiId = upiId, qr = qr,
    bankName = bankName, accountNumber = accountNumber, ifsc = ifsc,
    signatoryName = signatoryName, signatoryDesignation = signatoryDesignation,
)

// ---- settings ----------------------------------------------------------------------------------

fun SettingsDto.toDomain() = UserSettings(
    defaultCurrency = defaultCurrency, defaultTaxPercent = defaultTaxPercent,
    invoiceNumberPrefix = invoiceNumberPrefix, nextInvoiceNumber = nextInvoiceNumber,
    defaultTemplateId = defaultTemplateId, defaultNotes = defaultNotes,
)

fun UserSettings.toDto() = SettingsDto(
    defaultCurrency = defaultCurrency, defaultTaxPercent = defaultTaxPercent,
    invoiceNumberPrefix = invoiceNumberPrefix, nextInvoiceNumber = nextInvoiceNumber,
    defaultTemplateId = defaultTemplateId, defaultNotes = defaultNotes,
)

// ---- customers ---------------------------------------------------------------------------------

fun CustomerDto.toDomain(fallbackUpdatedAtMillis: Long): CustomerRecord = CustomerRecord(
    id = requireNotNull(id) { "customer from server has no id" },
    name = name, phone = phone, email = email, gstin = gstin,
    addressLine1 = addressLine1, addressLine2 = addressLine2, city = city, state = state,
    stateCode = stateCode, pincode = pincode, country = country,
    createdAtMillis = isoToMillis(createdAt),
    updatedAtMillis = isoToMillis(updatedAt) ?: fallbackUpdatedAtMillis,
    pendingSync = false,
)

fun CustomerRecord.toDto() = CustomerDto(
    id = id, name = name, phone = phone, email = email, gstin = gstin,
    addressLine1 = addressLine1, addressLine2 = addressLine2, city = city, state = state,
    stateCode = stateCode, pincode = pincode, country = country,
)

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}

/**
 * PATCH body with every editable field present (nulls explicit), so a field cleared offline is
 * also cleared on the server — a partial body would silently keep the old value.
 */
fun CustomerRecord.toPatchBody(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    putNullable("phone", phone)
    putNullable("email", email)
    putNullable("gstin", gstin)
    putNullable("addressLine1", addressLine1)
    putNullable("addressLine2", addressLine2)
    putNullable("city", city)
    putNullable("state", state)
    putNullable("stateCode", stateCode)
    putNullable("pincode", pincode)
    putNullable("country", country)
}

// ---- products ----------------------------------------------------------------------------------

fun ProductDto.toDomain(fallbackUpdatedAtMillis: Long): ProductRecord = ProductRecord(
    id = requireNotNull(id) { "product from server has no id" },
    name = name,
    hsnSac = hsnSac,
    unitPricePaise = paiseOrZero(unitPrice),
    taxRatePercent = taxRatePercent,
    unit = unit,
    createdAtMillis = isoToMillis(createdAt),
    updatedAtMillis = isoToMillis(updatedAt) ?: fallbackUpdatedAtMillis,
    pendingSync = false,
)

fun ProductRecord.toDto() = ProductDto(
    id = id,
    name = name,
    hsnSac = hsnSac,
    unitPrice = unitPricePaise.toString(),
    taxRatePercent = taxRatePercent,
    unit = unit,
)

/** Full-field patch (explicit nulls) so clearing a field offline clears it on the server too. */
fun ProductRecord.toPatchBody(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    putNullable("hsnSac", hsnSac)
    put("unitPrice", JsonPrimitive(unitPricePaise.toString()))
    put("taxRatePercent", JsonPrimitive(taxRatePercent))
    putNullable("unit", unit)
}

// ---- invoices ----------------------------------------------------------------------------------

fun InvoiceItemDto.toDomain() = InvoiceItemRecord(
    description = description, hsnSac = hsnSac, quantity = quantity,
    unitPricePaise = paiseOrZero(unitPrice), taxRatePercent = taxRatePercent,
    lineTotalPaise = paiseOrZero(lineTotal), taxAmountPaise = paiseOrZero(taxAmount),
)

fun InvoiceItemRecord.toDto() = InvoiceItemDto(
    description = description, hsnSac = hsnSac, quantity = quantity,
    unitPrice = unitPricePaise.toString(), taxRatePercent = taxRatePercent,
    lineTotal = lineTotalPaise.toString(), taxAmount = taxAmountPaise.toString(),
)

fun InvoiceDto.toDomain(): InvoiceRecord {
    val discountType = discountType?.let { t -> DiscountType.entries.firstOrNull { it.name == t } }
    return InvoiceRecord(
        id = id,
        invoiceNumber = invoiceNumber,
        invoiceDateMillis = isoToMillis(invoiceDate) ?: 0L,
        dueDateMillis = isoToMillis(dueDate),
        currency = currency,
        status = InvoiceDocStatus.entries.firstOrNull { it.name == status } ?: InvoiceDocStatus.Draft,
        templateId = templateId,
        templateVersion = templateVersion,
        customerId = customerId,
        customerName = customerSnapshot?.name,
        customerSnapshot = customerSnapshot,
        companySnapshot = companySnapshot,
        notes = notes,
        discount = if (discountType != null && !discountValue.isNullOrBlank()) {
            DiscountSpec(discountType, discountValue)
        } else null,
        discountBeforeTax = discountBeforeTax,
        items = items.map { it.toDomain() },
        subtotalPaise = paiseOrZero(subtotal),
        discountTotalPaise = paiseOrZero(discountTotal),
        taxTotalPaise = paiseOrZero(taxTotal),
        grandTotalPaise = paiseOrZero(grandTotal),
        themeOverrides = themeOverrides
            ?.mapNotNull { (token, hex) -> TemplateParser.parseHexColor(hex)?.let { token to it } }
            ?.toMap()
            ?: emptyMap(),
        hiddenSections = hiddenSections?.toSet() ?: emptySet(),
        pdfPath = pdfPath,
        deletedAtMillis = isoToMillis(deletedAt),
        createdAtMillis = isoToMillis(createdAt),
        updatedAtMillis = isoToMillis(updatedAt) ?: 0L,
        pendingSync = false,
    )
}

/** Push shape for POST /invoices and /invoices/sync — `updatedAt` carries the LWW edit time. */
fun InvoiceRecord.toDto() = InvoiceDto(
    id = id,
    invoiceNumber = invoiceNumber,
    invoiceDate = Iso8601.format(invoiceDateMillis),
    dueDate = dueDateMillis?.let { Iso8601.format(it) },
    currency = currency,
    status = status.name,
    templateId = templateId,
    templateVersion = templateVersion,
    customerId = customerId,
    customerSnapshot = customerSnapshot,
    companySnapshot = companySnapshot,
    notes = notes,
    discountType = discount?.type?.name,
    discountValue = discount?.value,
    discountBeforeTax = discountBeforeTax,
    items = items.map { it.toDto() },
    themeOverrides = themeOverrides.mapValues { TemplateParser.formatHexColor(it.value) },
    hiddenSections = hiddenSections.toList(),
    updatedAt = Iso8601.format(updatedAtMillis),
)

// ---- templates ---------------------------------------------------------------------------------

fun TemplateDto.toDomain() = TemplateInfo(
    id = id, name = name, category = category, thumbnailUrl = thumbnailUrl,
    isPremium = isPremium, currentVersion = currentVersion, checksum = checksum,
)
