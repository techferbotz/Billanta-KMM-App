package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.data.db.AccountRow
import com.ferbotz.billanta.data.db.CompanyRow
import com.ferbotz.billanta.data.db.CompiledTemplateRow
import com.ferbotz.billanta.data.db.CustomerRow
import com.ferbotz.billanta.data.db.InvoiceItemRow
import com.ferbotz.billanta.data.db.InvoiceRow
import com.ferbotz.billanta.data.db.ProductRow
import com.ferbotz.billanta.data.db.SettingsRow
import com.ferbotz.billanta.data.db.TemplateRow
import com.ferbotz.billanta.domain.model.CompanyProfile
import com.ferbotz.billanta.domain.model.CompanySnapshot
import com.ferbotz.billanta.domain.model.CompiledTemplate
import com.ferbotz.billanta.domain.model.CustomerRecord
import com.ferbotz.billanta.domain.model.CustomerSnapshot
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceItemRecord
import com.ferbotz.billanta.domain.model.InvoiceRecord
import com.ferbotz.billanta.domain.model.ProductRecord
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.render.TemplateParser
import com.ferbotz.billanta.domain.model.UserAccount
import com.ferbotz.billanta.domain.model.UserSettings
import com.ferbotz.billanta.domain.money.DiscountSpec
import com.ferbotz.billanta.domain.money.DiscountType
import kotlinx.serialization.SerializationException

internal fun Long.toBool(): Boolean = this != 0L
internal fun Boolean.toDbLong(): Long = if (this) 1L else 0L

internal inline fun <reified T> decodeJsonOrNull(json: String?): T? {
    if (json.isNullOrBlank()) return null
    return try {
        BillantaJson.decodeFromString<T>(json)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun AccountRow.toDomain() = UserAccount(
    id = id, email = email, name = name, photoUrl = photoUrl, isPremium = isPremium.toBool(),
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
)

internal fun CompanyRow.toDomain() = CompanyProfile(
    name = name, gstin = gstin, addressLine1 = addressLine1, addressLine2 = addressLine2,
    city = city, state = state, stateCode = stateCode, pincode = pincode, country = country,
    phone = phone, email = email, logo = logo, signature = signature, upiId = upiId, qr = qr,
    bankName = bankName, accountNumber = accountNumber, ifsc = ifsc,
    signatoryName = signatoryName, signatoryDesignation = signatoryDesignation,
)

internal fun SettingsRow.toDomain() = UserSettings(
    defaultCurrency = defaultCurrency,
    defaultTaxPercent = defaultTaxPercent,
    invoiceNumberPrefix = invoiceNumberPrefix,
    nextInvoiceNumber = nextInvoiceNumber,
    defaultTemplateId = defaultTemplateId,
    defaultNotes = defaultNotes,
)

internal fun CustomerRow.toDomain() = CustomerRecord(
    id = id, name = name, phone = phone, email = email, gstin = gstin,
    addressLine1 = addressLine1, addressLine2 = addressLine2, city = city, state = state,
    stateCode = stateCode, pincode = pincode, country = country,
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    pendingSync = dirty.toBool(),
)

internal fun InvoiceItemRow.toDomain() = InvoiceItemRecord(
    description = description, hsnSac = hsnSac, quantity = quantity, unitPricePaise = unitPrice,
    taxRatePercent = taxRatePercent, lineTotalPaise = lineTotal, taxAmountPaise = taxAmount,
)

internal fun ProductRow.toDomain() = ProductRecord(
    id = id, name = name, hsnSac = hsnSac, unitPricePaise = unitPrice,
    taxRatePercent = taxRatePercent, unit = unit, usageCount = usageCount,
    lastUsedAtMillis = lastUsedAtMillis, createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis, pendingSync = dirty.toBool(),
)

/** `{"accent":"#c2410c"}` — hex strings, so the stored form matches what the API will accept. */
internal fun encodeThemeOverrides(overrides: Map<String, Long>): String? =
    if (overrides.isEmpty()) null
    else BillantaJson.encodeToString(overrides.mapValues { TemplateParser.formatHexColor(it.value) })

internal fun decodeThemeOverrides(json: String?): Map<String, Long> =
    decodeJsonOrNull<Map<String, String>>(json)
        ?.mapNotNull { (token, hex) -> TemplateParser.parseHexColor(hex)?.let { token to it } }
        ?.toMap()
        ?: emptyMap()

internal fun encodeHiddenSections(sections: Set<String>): String? =
    if (sections.isEmpty()) null else BillantaJson.encodeToString(sections.toList())

internal fun decodeHiddenSections(json: String?): Set<String> =
    decodeJsonOrNull<List<String>>(json)?.toSet() ?: emptySet()

internal fun InvoiceRow.toDomain(items: List<InvoiceItemRecord> = emptyList()): InvoiceRecord {
    val discountType = discountType?.let { t -> DiscountType.entries.firstOrNull { it.name == t } }
    return InvoiceRecord(
        id = id,
        invoiceNumber = invoiceNumber,
        invoiceDateMillis = invoiceDateMillis,
        dueDateMillis = dueDateMillis,
        currency = currency,
        status = InvoiceDocStatus.entries.firstOrNull { it.name == status } ?: InvoiceDocStatus.Draft,
        templateId = templateId,
        templateVersion = templateVersion,
        customerId = customerId,
        customerName = customerName,
        customerSnapshot = decodeJsonOrNull<CustomerSnapshot>(customerSnapshotJson),
        companySnapshot = decodeJsonOrNull<CompanySnapshot>(companySnapshotJson),
        notes = notes,
        discount = if (discountType != null && !discountValue.isNullOrBlank()) {
            DiscountSpec(discountType, discountValue)
        } else null,
        discountBeforeTax = discountBeforeTax.toBool(),
        items = items,
        subtotalPaise = subtotal,
        discountTotalPaise = discountTotal,
        taxTotalPaise = taxTotal,
        grandTotalPaise = grandTotal,
        pdfPath = pdfPath,
        deletedAtMillis = deletedAtMillis,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        pendingSync = dirty.toBool(),
        syncError = syncError,
        themeOverrides = decodeThemeOverrides(themeOverridesJson),
        hiddenSections = decodeHiddenSections(hiddenSectionsJson),
    )
}

internal fun TemplateRow.toDomain() = TemplateInfo(
    id = id, name = name, category = category, thumbnailUrl = thumbnailUrl,
    isPremium = isPremium.toBool(), currentVersion = currentVersion, checksum = checksum,
)

internal fun CompiledTemplateRow.toDomain() = CompiledTemplate(
    templateId = templateId, version = version, checksum = checksum, json = json,
)
