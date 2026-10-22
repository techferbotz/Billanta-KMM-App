package com.ferbotz.billanta.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ferbotz.billanta.data.db.BillantaDb
import com.ferbotz.billanta.data.db.CustomerValues
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.Customer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Customers as values maps (BE-021), offline-first. */
class CustomerValuesLocal(
    private val db: BillantaDb,
    private val dispatcher: CoroutineDispatcher,
) {
    private val q get() = db.customerValuesQueries

    fun observeList(query: String): Flow<List<Customer>> =
        q.list(query.trim()).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toCustomer() } }

    fun observeById(id: String): Flow<Customer?> =
        q.byId(id).asFlow().mapToList(dispatcher).map { rows -> rows.firstOrNull()?.toCustomer() }

    suspend fun getById(id: String): Customer? = withContext(dispatcher) { q.byId(id).executeAsOneOrNull()?.toCustomer() }

    /** A local edit: marks the row dirty, and the ids that changed join those waiting for a PATCH. */
    suspend fun saveLocal(customer: Customer): Customer = withContext(dispatcher) {
        db.transactionWithResult {
            val existing = q.byIdIncludingDeleted(customer.id).executeAsOneOrNull()
            val dirtyKeys = decodeKeys(existing?.dirtyKeysJson) +
                changedKeys(FieldValues.parse(existing?.valuesJson), customer.values)
            val saved = customer.copy(
                dirtyKeys = dirtyKeys,
                createdAtMillis = existing?.createdAtMillis ?: customer.createdAtMillis,
                pendingSync = true,
            )
            write(saved, dirty = true, isDeleted = false, isSynced = existing?.isSynced?.toBool() ?: false)
            saved
        }
    }

    /** Server state: overwrites cleanly unless the local row has unpushed edits. */
    suspend fun applyServer(customer: Customer) = withContext(dispatcher) {
        db.transaction {
            val local = q.byIdIncludingDeleted(customer.id).executeAsOneOrNull()
            if (local != null && local.dirty.toBool()) return@transaction
            write(customer.copy(dirtyKeys = emptySet(), pendingSync = false), dirty = false, isDeleted = false, isSynced = true)
        }
    }

    suspend fun softDelete(id: String, updatedAtMillis: Long) = withContext(dispatcher) {
        val local = q.byIdIncludingDeleted(id).executeAsOneOrNull() ?: return@withContext
        if (local.isSynced.toBool()) q.softDelete(updatedAtMillis = updatedAtMillis, id = id)
        else q.hardDelete(id) // the server never saw it — nothing to replay
    }

    suspend fun hardDelete(id: String) = withContext(dispatcher) { q.hardDelete(id) }

    suspend fun dirtyRows(): List<CustomerValues> = withContext(dispatcher) { q.dirtyRows().executeAsList() }

    /** Ids the server knows about and we have no local edits for — used to reconcile deletions. */
    suspend fun syncedCleanIds(): List<String> = withContext(dispatcher) { q.syncedCleanIds().executeAsList() }

    suspend fun markSynced(id: String, updatedAtMillis: Long) =
        withContext(dispatcher) { q.markSynced(id = id, updatedAtMillis = updatedAtMillis) }

    suspend fun markAllDirty() = withContext(dispatcher) { q.markAllDirty() }

    suspend fun clearAll() = withContext(dispatcher) { q.clear() }

    private fun write(customer: Customer, dirty: Boolean, isDeleted: Boolean, isSynced: Boolean) {
        val values = customer.values
        q.upsert(
            id = customer.id,
            valuesJson = values.toJsonString(),
            dirtyKeysJson = encodeKeys(customer.dirtyKeys),
            name = customer.name,
            searchText = listOfNotNull(customer.name, values.string(Customer.PHONE), values.string(Customer.GSTIN))
                .joinToString(" ").lowercase(),
            createdAtMillis = customer.createdAtMillis,
            updatedAtMillis = customer.updatedAtMillis,
            dirty = dirty.toDbLong(),
            isDeleted = isDeleted.toDbLong(),
            isSynced = isSynced.toDbLong(),
        )
    }
}

internal fun CustomerValues.toCustomer() = Customer(
    id = id,
    values = FieldValues.parse(valuesJson),
    dirtyKeys = decodeKeys(dirtyKeysJson),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    pendingSync = dirty.toBool(),
)
