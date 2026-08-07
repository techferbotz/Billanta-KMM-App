package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.core.BillantaJson
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.domain.model.InvoiceDocStatus
import com.ferbotz.billanta.domain.model.InvoiceRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class InvoiceLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.invoicesQueries
    private val itemsQ get() = db.invoiceItemsQueries

    /** Live list (tombstones excluded). Items are not populated in list projections. */
    fun observeList(status: InvoiceDocStatus?, query: String): Flow<List<InvoiceRecord>> =
        q.list(status = status?.name, q = query.trim())
            .asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    fun observeById(id: String): Flow<InvoiceRecord?> =
        q.byId(id).asFlow().mapToList(dispatcher).map { rows ->
            rows.firstOrNull()?.let { row -> row.toDomain(loadItems(row.id)) }
        }

    suspend fun getById(id: String): InvoiceRecord? = withContext(dispatcher) {
        q.byId(id).executeAsOneOrNull()?.let { it.toDomain(loadItems(it.id)) }
    }

    suspend fun listForCustomer(customerId: String): List<InvoiceRecord> = withContext(dispatcher) {
        q.listForCustomer(customerId).executeAsList().map { it.toDomain() }
    }

    /** Full row + items replace, used both for local edits (dirty=true) and server pulls (dirty=false). */
    suspend fun upsert(record: InvoiceRecord, dirty: Boolean) = withContext(dispatcher) {
        db.transaction {
            q.upsert(
                id = record.id,
                invoiceNumber = record.invoiceNumber,
                invoiceDateMillis = record.invoiceDateMillis,
                dueDateMillis = record.dueDateMillis,
                currency = record.currency,
                status = record.status.name,
                templateId = record.templateId,
                templateVersion = record.templateVersion,
                customerId = record.customerId,
                customerName = record.customerName ?: record.customerSnapshot?.name,
                customerSnapshotJson = record.customerSnapshot?.let { BillantaJson.encodeToString(it) },
                companySnapshotJson = record.companySnapshot?.let { BillantaJson.encodeToString(it) },
                notes = record.notes,
                discountType = record.discount?.type?.name,
                discountValue = record.discount?.value,
                discountBeforeTax = record.discountBeforeTax.toDbLong(),
                subtotal = record.subtotalPaise,
                discountTotal = record.discountTotalPaise,
                taxTotal = record.taxTotalPaise,
                grandTotal = record.grandTotalPaise,
                pdfPath = record.pdfPath,
                deletedAtMillis = record.deletedAtMillis,
                createdAtMillis = record.createdAtMillis,
                updatedAtMillis = record.updatedAtMillis,
                dirty = dirty.toDbLong(),
                syncError = if (dirty) null else record.syncError,
                themeOverridesJson = encodeThemeOverrides(record.themeOverrides),
                hiddenSectionsJson = encodeHiddenSections(record.hiddenSections),
            )
            itemsQ.deleteForInvoice(record.id)
            record.items.forEachIndexed { idx, item ->
                itemsQ.insertItem(
                    invoiceId = record.id,
                    orderIdx = idx.toLong(),
                    description = item.description,
                    hsnSac = item.hsnSac,
                    quantity = item.quantity,
                    unitPrice = item.unitPricePaise,
                    taxRatePercent = item.taxRatePercent,
                    lineTotal = item.lineTotalPaise,
                    taxAmount = item.taxAmountPaise,
                )
            }
        }
    }

    suspend fun softDelete(id: String, deletedAtMillis: Long, updatedAtMillis: Long) =
        withContext(dispatcher) { q.softDelete(deletedAtMillis = deletedAtMillis, updatedAtMillis = updatedAtMillis, id = id) }

    suspend fun hardDelete(id: String) = withContext(dispatcher) {
        db.transaction {
            itemsQ.deleteForInvoice(id)
            q.hardDelete(id)
        }
    }

    suspend fun dirtyRecords(): List<InvoiceRecord> = withContext(dispatcher) {
        q.dirtyRows().executeAsList().map { it.toDomain(loadItems(it.id)) }
    }

    /** Clears dirty only if the row hasn't been edited again since it was read (updatedAt matches). */
    suspend fun markClean(id: String, updatedAtMillis: Long) =
        withContext(dispatcher) { q.markClean(id = id, updatedAtMillis = updatedAtMillis) }

    suspend fun setSyncError(id: String, error: String) =
        withContext(dispatcher) { q.setSyncError(syncError = error, id = id) }

    suspend fun isNumberInUse(number: String, excludeId: String): Boolean = withContext(dispatcher) {
        q.numberInUse(number = number, excludeId = excludeId).executeAsOne() > 0
    }

    suspend fun statsForCustomer(customerId: String): Pair<Long, Long> = withContext(dispatcher) {
        val count = q.countForCustomer(customerId).executeAsOne()
        val total = q.sumGrandTotalForCustomer(customerId).executeAsOne()
        count to total
    }

    suspend fun clearAll() = withContext(dispatcher) {
        db.transaction {
            itemsQ.clear()
            q.clear()
        }
    }

    private fun loadItems(invoiceId: String) =
        itemsQ.forInvoice(invoiceId).executeAsList().map { it.toDomain() }
}
