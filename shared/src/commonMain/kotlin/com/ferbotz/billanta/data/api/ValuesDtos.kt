@file:OptIn(ExperimentalSerializationApi::class)

package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.money.InvoiceComputed
import com.ferbotz.billanta.domain.money.InvoiceMath
import com.ferbotz.billanta.domain.money.LineComputed
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.records.Product
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * The BE-021 wire shapes (API.md "Values maps"; FIELDS.md §7). Every record is a `values` map of
 * field id → value. The JSON is passed through untouched in both directions: the app never
 * renames a key, which is what removes the APP-011 class of silent data loss for good.
 *
 * `BillantaJson` omits default values, so anything the server requires is marked
 * `@EncodeDefault`: it must reach the wire even when it is empty.
 */

/** A `PUT` / `PATCH` body for any record: `{ "values": { … } }`. */
@Serializable
data class ValuesBodyDto(val values: JsonObject)

/** `GET /company` and `GET /settings`, and what their writes return. */
@Serializable
data class ValuesRecordDto(
    val values: JsonObject = JsonObject(emptyMap()),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** A customer or a product: `{ id, values }`, plus timestamps on the way back. */
@Serializable
data class EntityValuesDto(
    val id: String,
    @EncodeDefault val values: JsonObject = JsonObject(emptyMap()),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class InvoiceLineValuesDto(
    val id: String,
    @EncodeDefault val values: JsonObject = JsonObject(emptyMap()),
    /** `line_total` and `tax_amount`, server-calculated; never sent. */
    val computed: Map<String, String>? = null,
)

/** `POST /invoices`, one element of `/invoices/sync`, and what both return. */
@Serializable
data class InvoiceValuesDto(
    val id: String,
    val templateId: String? = null,
    val templateVersion: Long? = null,
    @EncodeDefault val status: String = InvoiceDocStatus.Draft.name,
    @EncodeDefault val values: JsonObject = JsonObject(emptyMap()),
    @EncodeDefault val lineItems: List<InvoiceLineValuesDto> = emptyList(),
    /** Server-calculated totals; any the client sends are ignored, so it sends none. */
    val computed: Map<String, String>? = null,
    @EncodeDefault val hiddenSections: List<String> = emptyList(),
    @EncodeDefault val themeOverrides: Map<String, String> = emptyMap(),
    val pdfPath: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class InvoiceValuesSyncRequestDto(
    @EncodeDefault val invoices: List<InvoiceValuesDto> = emptyList(),
    val since: String? = null,
)

@Serializable
data class InvoiceValuesSyncResponseDto(
    val changed: List<InvoiceValuesDto> = emptyList(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

// ---- mapping ------------------------------------------------------------------------------------

/**
 * A `PATCH` body carrying only [keys]: each key's current value, or `null` where it was cleared,
 * which the server reads as "remove this key" (FIELDS.md §7.1).
 */
fun FieldValues.patchBody(keys: Set<String>): ValuesBodyDto =
    ValuesBodyDto(JsonObject(keys.associateWith { this[it] ?: JsonNull }))

fun Customer.toDto() = EntityValuesDto(id = id, values = values.json)

fun EntityValuesDto.toCustomer(fallbackUpdatedAtMillis: Long) = Customer(
    id = id,
    values = FieldValues.of(values),
    createdAtMillis = createdAt?.let(Iso8601::parseOrNull),
    updatedAtMillis = updatedAt?.let(Iso8601::parseOrNull) ?: fallbackUpdatedAtMillis,
)

fun Product.toDto() = EntityValuesDto(id = id, values = values.json)

fun EntityValuesDto.toProduct(fallbackUpdatedAtMillis: Long) = Product(
    id = id,
    values = FieldValues.of(values),
    createdAtMillis = createdAt?.let(Iso8601::parseOrNull),
    updatedAtMillis = updatedAt?.let(Iso8601::parseOrNull) ?: fallbackUpdatedAtMillis,
)

/** The `POST /invoices` shape. Lines travel in `lineItems`, never as a `line_items` value. */
fun Invoice.toDto() = InvoiceValuesDto(
    id = id,
    templateId = templateId,
    templateVersion = templateVersion,
    status = status.name,
    values = values.without(LINE_ITEMS).json,
    lineItems = lines.map { InvoiceLineValuesDto(id = it.id, values = it.values.json) },
    hiddenSections = hiddenSections.sorted(),
    themeOverrides = themeOverrides,
    pdfPath = pdfPath,
    updatedAt = Iso8601.format(updatedAtMillis),
)

/**
 * The server's copy of an invoice. Its `computed` totals are authoritative; one that arrives
 * without them (no server should) is worked out locally, by the same rules.
 */
fun InvoiceValuesDto.toInvoice(fallbackUpdatedAtMillis: Long): Invoice {
    val lines = lineItems.map { line ->
        InvoiceLine(
            id = line.id,
            values = FieldValues.of(line.values),
            computed = line.computed?.let {
                LineComputed(it[LineComputed.LINE_TOTAL]?.toLongOrNull() ?: 0L, it[LineComputed.TAX_AMOUNT]?.toLongOrNull() ?: 0L)
            },
        )
    }
    val invoiceValues = FieldValues.of(values)
    val computed = computed
        ?.let { totals -> InvoiceComputed.fromMaps(totals, lines.map { it.computed?.toMap().orEmpty() }) }
        ?: InvoiceMath.compute(invoiceValues, lines.map { it.values })
    return Invoice(
        id = id,
        status = InvoiceDocStatus.entries.firstOrNull { it.name == status } ?: InvoiceDocStatus.Draft,
        templateId = templateId,
        templateVersion = templateVersion,
        values = invoiceValues,
        lines = lines.mapIndexed { i, line -> line.copy(computed = line.computed ?: computed.lines.getOrNull(i)) },
        computed = computed,
        hiddenSections = hiddenSections.toSet(),
        themeOverrides = themeOverrides,
        pdfPath = pdfPath,
        createdAtMillis = createdAt?.let(Iso8601::parseOrNull),
        updatedAtMillis = updatedAt?.let(Iso8601::parseOrNull) ?: fallbackUpdatedAtMillis,
        deletedAtMillis = deletedAt?.let(Iso8601::parseOrNull),
    )
}

private const val LINE_ITEMS = "line_items"
