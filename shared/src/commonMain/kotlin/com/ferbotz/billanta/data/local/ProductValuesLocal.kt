package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.ProductValues
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Product
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The product catalogue as values maps over the line ids (BE-021), synced like customers. */
class ProductValuesLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.productValuesQueries

    fun observeList(query: String): Flow<List<Product>> =
        q.list(query.trim()).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toProduct() } }

    suspend fun getById(id: String): Product? = withContext(dispatcher) { q.byId(id).executeAsOneOrNull()?.toProduct() }

    /** The live product whose description normalises to [nameKey], if any. */
    suspend fun byNameKey(nameKey: String): Product? =
        withContext(dispatcher) { q.byNameKey(nameKey).executeAsOneOrNull()?.toProduct() }

    /** A local edit: marks the row dirty, and the ids that changed join those waiting for a PATCH. */
    suspend fun saveLocal(product: Product): Product = withContext(dispatcher) {
        db.transactionWithResult {
            val existing = q.byIdIncludingDeleted(product.id).executeAsOneOrNull()
            val dirtyKeys = decodeKeys(existing?.dirtyKeysJson) +
                changedKeys(FieldValues.parse(existing?.valuesJson), product.values)
            val saved = product.copy(
                dirtyKeys = dirtyKeys,
                createdAtMillis = existing?.createdAtMillis ?: product.createdAtMillis,
                usageCount = existing?.usageCount ?: product.usageCount,
                lastUsedAtMillis = existing?.lastUsedAtMillis ?: product.lastUsedAtMillis,
                pendingSync = true,
            )
            write(saved, dirty = true, isDeleted = false, isSynced = existing?.isSynced?.toBool() ?: false)
            saved
        }
    }

    /** Server state: overwrites cleanly unless the local row has unpushed edits. Usage stays ours. */
    suspend fun applyServer(product: Product) = withContext(dispatcher) {
        db.transaction {
            val local = q.byIdIncludingDeleted(product.id).executeAsOneOrNull()
            if (local != null && local.dirty.toBool()) return@transaction
            write(
                product.copy(
                    dirtyKeys = emptySet(),
                    pendingSync = false,
                    usageCount = local?.usageCount ?: 0,
                    lastUsedAtMillis = local?.lastUsedAtMillis ?: 0,
                ),
                dirty = false, isDeleted = false, isSynced = true,
            )
        }
    }

    /** Picked into a line: bumps this device's ordering only; the product itself is untouched. */
    suspend fun markUsed(id: String, atMillis: Long) = withContext(dispatcher) { q.markUsed(atMillis, id) }

    suspend fun softDelete(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(id).executeAsOneOrNull() ?: return@withContext
        if (local.isSynced.toBool()) q.softDelete(updatedAtMillis = updatedAtMillis, id = id)
        else q.hardDelete(id)
    }

    suspend fun hardDelete(id: String) = withContext(dispatcher) { q.hardDelete(id) }

    suspend fun dirtyRows(): List<ProductValues> = withContext(dispatcher) { q.dirtyRows().executeAsList() }

    suspend fun syncedCleanIds(): List<String> = withContext(dispatcher) { q.syncedCleanIds().executeAsList() }

    suspend fun markSynced(id: String, updatedAtMillis: Long) =
        withContext(dispatcher) { q.markSynced(id = id, updatedAtMillis = updatedAtMillis) }

    suspend fun markAllDirty() = withContext(dispatcher) { q.markAllDirty() }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private fun write(product: Product, dirty: Boolean, isDeleted: Boolean, isSynced: Boolean) {
        q.upsert(
            id = product.id,
            valuesJson = product.values.toJsonString(),
            dirtyKeysJson = encodeKeys(product.dirtyKeys),
            description = product.description,
            nameKey = Product.nameKeyOf(product.description),
            usageCount = product.usageCount,
            lastUsedAtMillis = product.lastUsedAtMillis,
            createdAtMillis = product.createdAtMillis,
            updatedAtMillis = product.updatedAtMillis,
            dirty = dirty.toDbLong(),
            isDeleted = isDeleted.toDbLong(),
            isSynced = isSynced.toDbLong(),
        )
    }
}

internal fun ProductValues.toProduct() = Product(
    id = id,
    values = FieldValues.parse(valuesJson),
    dirtyKeys = decodeKeys(dirtyKeysJson),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    pendingSync = dirty.toBool(),
    usageCount = usageCount,
    lastUsedAtMillis = lastUsedAtMillis,
)
