package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.InvoiceLineValues
import com.ferbotz.billanta.data.db.InvoiceValues
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.money.InvoiceComputed
import com.ferbotz.billanta.domain.money.LineComputed
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.Invoice
import com.ferbotz.billanta.records.InvoiceLine
import com.ferbotz.billanta.records.InvoiceLineSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Invoices as values maps with their lines (BE-021). */
class InvoiceValuesLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.invoiceValuesQueries
    private val linesQ get() = db.invoiceLineValuesQueries

    /**
     * Live list, tombstones excluded. Lines are not loaded: each invoice carries an
     * [InvoiceLineSummary] from its own derived columns, so a list never reads every invoice's lines.
     */
    fun observeList(query: String): Flow<List<Invoice>> =
        q.list(query.trim()).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toInvoice(lines = null) } }

    /** One invoice with its lines. Lines are always written with their invoice, so its row is the trigger. */
    fun observeById(id: String): Flow<Invoice?> =
        q.byId(id).asFlow().mapToList(dispatcher).map { rows ->
            rows.firstOrNull()?.let { row -> withContext(dispatcher) { row.toInvoice(loadLines(row.id)) } }
        }

    suspend fun getById(id: String): Invoice? = withContext(dispatcher) {
        q.byId(id).executeAsOneOrNull()?.let { it.toInvoice(loadLines(it.id)) }
    }

    suspend fun getByIdIncludingDeleted(id: String): Invoice? = withContext(dispatcher) {
        q.byIdIncludingDeleted(id).executeAsOneOrNull()?.let { it.toInvoice(loadLines(it.id)) }
    }

    /** The whole invoice and its lines, replaced in one transaction: a local edit (dirty) or a pull. */
    suspend fun upsert(invoice: Invoice, dirty: Boolean) = withContext(dispatcher) {
        db.transaction {
            write(invoice, dirty)
            linesQ.deleteForInvoice(invoice.id)
            invoice.lines.forEachIndexed { position, line ->
                linesQ.insert(
                    invoiceId = invoice.id,
                    id = line.id,
                    position = position.toLong(),
                    valuesJson = line.values.toJsonString(),
                    computedJson = encodeStringMap(
                        (line.computed ?: invoice.computed.lines.getOrNull(position))?.toMap().orEmpty(),
                    ),
                )
            }
        }
    }

    /** Every Draft, with its lines: the invoices whose company and customer copy follow the records. */
    suspend fun drafts(): List<Invoice> = withContext(dispatcher) {
        q.drafts().executeAsList().map { it.toInvoice(loadLines(it.id)) }
    }

    suspend fun dirtyInvoices(): List<Invoice> = withContext(dispatcher) {
        q.dirtyRows().executeAsList().map { it.toInvoice(loadLines(it.id)) }
    }

    /** Every live invoice number that starts with [prefix], for numbering the next one. */
    suspend fun numbersStartingWith(prefix: String): List<String> =
        withContext(dispatcher) { q.numbersStartingWith(prefix).executeAsList() }

    suspend fun numberInUse(number: String, excludingId: String): Boolean =
        withContext(dispatcher) { q.numberInUse(number, excludingId).executeAsOne() > 0 }

    suspend fun markClean(id: String, updatedAtMillis: Long) = withContext(dispatcher) { q.markClean(id, updatedAtMillis) }

    suspend fun setSyncError(id: String, error: String) = withContext(dispatcher) { q.setSyncError(error, id) }

    suspend fun softDelete(id: String, atMillis: Long) =
        withContext(dispatcher) { q.softDelete(deletedAtMillis = atMillis, updatedAtMillis = atMillis, id = id) }

    suspend fun hardDelete(id: String) = withContext(dispatcher) {
        db.transaction {
            q.hardDelete(id)
            linesQ.deleteForInvoice(id)
        }
    }

    suspend fun markAllDirty() = withContext(dispatcher) { q.markAllDirty() }

    suspend fun clearAll() = withContext(dispatcher) {
        db.transaction {
            q.clear()
            linesQ.clear()
        }
    }

    private fun loadLines(invoiceId: String): List<InvoiceLineValues> = linesQ.forInvoice(invoiceId).executeAsList()

    private fun write(invoice: Invoice, dirty: Boolean) {
        val values = invoice.values
        val summary = invoice.lineSummary
        q.upsert(
            id = invoice.id,
            status = invoice.status.name,
            templateId = invoice.templateId,
            templateVersion = invoice.templateVersion,
            valuesJson = values.toJsonString(),
            computedJson = encodeStringMap(invoice.computed.totalsMap()),
            hiddenSectionsJson = invoice.hiddenSections.takeIf { it.isNotEmpty() }?.let(::encodeKeys),
            themeOverridesJson = invoice.themeOverrides.takeIf { it.isNotEmpty() }?.let(::encodeStringMap),
            pdfPath = invoice.pdfPath,
            invoiceNumber = invoice.invoiceNumber,
            invoiceDate = values.string(Invoice.INVOICE_DATE),
            customerId = invoice.customerId,
            customerName = invoice.customerName,
            searchText = listOfNotNull(invoice.invoiceNumber, invoice.customerName, values.string(Customer.PHONE))
                .joinToString(" ").lowercase(),
            grandTotal = invoice.computed.grandTotal,
            lineCount = summary.count.toLong(),
            firstLineDescription = summary.firstDescription,
            createdAtMillis = invoice.createdAtMillis,
            updatedAtMillis = invoice.updatedAtMillis,
            deletedAtMillis = invoice.deletedAtMillis,
            dirty = dirty.toDbLong(),
            syncError = invoice.syncError,
        )
    }
}

/** [lines] null means a list projection: no lines, a summary instead. */
internal fun InvoiceValues.toInvoice(lines: List<InvoiceLineValues>?): Invoice {
    val lineRecords = lines.orEmpty().map { row ->
        val computed = decodeStringMap(row.computedJson)
        InvoiceLine(
            id = row.id,
            values = FieldValues.parse(row.valuesJson),
            computed = LineComputed(
                computed[LineComputed.LINE_TOTAL]?.toLongOrNull() ?: 0L,
                computed[LineComputed.TAX_AMOUNT]?.toLongOrNull() ?: 0L,
            ),
        )
    }
    return Invoice(
        id = id,
        status = InvoiceDocStatus.entries.firstOrNull { it.name == status } ?: InvoiceDocStatus.Draft,
        templateId = templateId,
        templateVersion = templateVersion,
        values = FieldValues.parse(valuesJson),
        lines = lineRecords,
        computed = InvoiceComputed.fromMaps(decodeStringMap(computedJson), lineRecords.map { it.computed!!.toMap() }),
        hiddenSections = decodeKeys(hiddenSectionsJson),
        themeOverrides = decodeStringMap(themeOverridesJson),
        pdfPath = pdfPath,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
        pendingSync = dirty.toBool(),
        syncError = syncError,
        itemSummary = if (lines == null) InvoiceLineSummary(lineCount.toInt(), firstLineDescription) else null,
    )
}
