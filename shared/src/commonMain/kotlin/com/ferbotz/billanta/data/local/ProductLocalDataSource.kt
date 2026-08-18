package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.ProductRow
import com.ferbotz.billanta.domain.model.ProductRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ProductLocalDataSource(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.productsQueries

    fun observeList(query: String = "", limit: Long = DEFAULT_LIMIT): Flow<List<ProductRecord>> =
        q.list(q = query.trim(), limit = limit).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getById(id: String): ProductRecord? = withContext(dispatcher) {
        q.byId(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun byNameKey(nameKey: String): ProductRecord? = withContext(dispatcher) {
        q.byNameKey(nameKey).executeAsOneOrNull()?.toDomain()
    }

    suspend fun upsert(record: ProductRecord, dirty: Boolean, isSynced: Boolean) =
        withContext(dispatcher) {
            q.upsert(
                id = record.id,
                name = record.name,
                nameKey = ProductRecord.nameKeyOf(record.name),
                hsnSac = record.hsnSac,
                unitPrice = record.unitPricePaise,
                taxRatePercent = record.taxRatePercent,
                unit = record.unit,
                usageCount = record.usageCount,
                lastUsedAtMillis = record.lastUsedAtMillis,
                createdAtMillis = record.createdAtMillis,
                updatedAtMillis = record.updatedAtMillis,
                dirty = dirty.toDbLong(),
                isDeleted = 0L,
                isSynced = isSynced.toDbLong(),
            )
        }

    suspend fun softDelete(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        q.softDelete(updatedAtMillis = updatedAtMillis, id = id)
    }

    /** Rows with local edits the server has not confirmed. */
    suspend fun dirtyRows(): List<ProductRow> = withContext(dispatcher) { q.dirtyRows().executeAsList() }

    /** Ids the server knows about and we have no local edits for — used to spot remote deletions. */
    suspend fun syncedCleanIds(): List<String> = withContext(dispatcher) { q.syncedCleanIds().executeAsList() }

    suspend fun markSynced(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        q.markSynced(id = id, updatedAtMillis = updatedAtMillis)
    }

    /**
     * Server state. A row with unpushed local edits is left alone — the push half of the pass owns
     * it, and overwriting here would lose whatever the user just typed.
     */
    suspend fun applyServer(record: ProductRecord) = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(record.id).executeAsOneOrNull()
        if (local != null && local.dirty.toBool()) return@withContext
        q.upsert(
            id = record.id,
            name = record.name,
            nameKey = ProductRecord.nameKeyOf(record.name),
            hsnSac = record.hsnSac,
            unitPrice = record.unitPricePaise,
            taxRatePercent = record.taxRatePercent,
            unit = record.unit,
            // Usage stats are this device's own ranking signal; the server does not carry them.
            usageCount = local?.usageCount ?: record.usageCount,
            lastUsedAtMillis = local?.lastUsedAtMillis ?: record.lastUsedAtMillis,
            createdAtMillis = record.createdAtMillis,
            updatedAtMillis = record.updatedAtMillis,
            dirty = 0L,
            isDeleted = 0L,
            isSynced = 1L,
        )
    }

    suspend fun hardDelete(id: String) = withContext(dispatcher) { q.hardDelete(id) }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private companion object {
        const val DEFAULT_LIMIT = 100L
    }
}
